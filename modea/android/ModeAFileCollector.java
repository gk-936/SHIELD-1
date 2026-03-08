package com.dearmoon.shield.modea;

import android.util.Log;

import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;

/**
 * ModeAFileCollector — converts raw kernel telemetry events (received as
 * {@link ShieldEventData} structs from the root daemon) into the same
 * {@link FileSystemEvent} type that Mode-B's {@link com.dearmoon.shield.collectors.FileSystemCollector}
 * produces, then forwards them directly to {@link UnifiedDetectionEngine}.
 *
 * This is the critical integration point between Mode-A and the existing
 * detection pipeline.  No detection logic lives here — this class is purely
 * a translation / bridging layer.
 *
 * Operation mapping
 * -----------------
 *   eBPF "WRITE"  → FileSystemEvent operation "MODIFY"
 *   eBPF "READ"   → FileSystemEvent operation "READ"    (burst only)
 *   eBPF "FSYNC"  → FileSystemEvent operation "FSYNC"
 *   eBPF "EXEC"   → FileSystemEvent operation "EXEC"
 *
 * The UnifiedDetectionEngine currently acts on "MODIFY" events for entropy
 * analysis.  All other operation types are stored in the database for
 * BehaviorCorrelationEngine to use within its 5-second sliding window.
 */
public class ModeAFileCollector {

    private static final String TAG = "SHIELD_MODE_A";

    private final UnifiedDetectionEngine detectionEngine;

    /**
     * @param detectionEngine  the shared, already-started detection engine
     *                         (same instance used by Mode-B)
     */
    public ModeAFileCollector(UnifiedDetectionEngine detectionEngine) {
        this.detectionEngine = detectionEngine;
    }

    /**
     * Convert a single kernel telemetry event and inject it into the
     * detection pipeline.
     *
     * @param data  raw event received from the root daemon
     */
    public void onKernelEvent(ShieldEventData data) {
        if (data == null || data.filename == null || data.filename.isEmpty()) {
            return;
        }

        // Map eBPF operation tag → FileSystemEvent operation string
        String operation = mapOperation(data.operation);

        // Build a FileSystemEvent compatible with the existing pipeline.
        // sizeBefore / sizeAfter are approximated from the bytes field.
        // The detection engine uses File.length() directly for entropy
        // analysis, so the size values here are informational only.
        long sizeApprox = Math.max(0L, data.bytes);
        FileSystemEvent event = new FileSystemEvent(
                data.filename,
                operation,
                sizeApprox,          // sizeBefore
                sizeApprox           // sizeAfter
        );

        // Enrich with kernel-attributed UID — this is the key advantage
        // over Mode-B which relies on heuristics for UID attribution.
        event.setUid(data.uid);

        // TelemetryEvent.mode is a protected field with no public setter.
        // We set it via reflection so Mode-A events are tagged correctly
        // in the database without modifying the existing base class.
        try {
            java.lang.reflect.Field modeField =
                    event.getClass().getSuperclass().getDeclaredField("mode");
            modeField.setAccessible(true);
            modeField.set(event, "MODE_A");
        } catch (Exception ignored) {
            // Non-critical: event still flows correctly through the pipeline.
        }

        Log.i(TAG, String.format(
                "%s pid=%d uid=%d file=%s bytes=%d",
                data.operation, data.pid, data.uid,
                data.filename, data.bytes));

        // Forward to detection engine (runs on its internal HandlerThread)
        detectionEngine.processFileEvent(event);
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    private static String mapOperation(String bpfOp) {
        if (bpfOp == null) return "UNKNOWN";
        switch (bpfOp) {
            case "WRITE":  return "MODIFY";  // triggers entropy analysis
            case "READ":   return "READ";
            case "FSYNC":  return "FSYNC";
            case "EXEC":   return "EXEC";
            default:       return bpfOp;
        }
    }
}
