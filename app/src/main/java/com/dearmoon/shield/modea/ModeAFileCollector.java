package com.dearmoon.shield.modea;

import android.util.Log;

import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;

/**
 * ModeAFileCollector — converts raw kernel telemetry events (received as
 * {@link ShieldEventData} structs from the root daemon) into the same
 * {@link FileSystemEvent} type that Mode-B's FileSystemCollector produces,
 * then forwards them directly to {@link UnifiedDetectionEngine}.
 *
 * This is the critical integration point between Mode-A and the existing
 * detection pipeline. No detection logic lives here — this class is purely
 * a translation / bridging layer.
 *
 * Operation mapping
 * -----------------
 *   eBPF "BURST" / "WRITE"  → FileSystemEvent operation "MODIFY"
 *   eBPF "READ"             → FileSystemEvent operation "READ"
 *   eBPF "FSYNC"            → FileSystemEvent operation "FSYNC"
 *   eBPF "EXEC"             → FileSystemEvent operation "EXEC"
 *
 * The UnifiedDetectionEngine acts on "MODIFY" events for entropy analysis.
 * All other types are stored for BehaviorCorrelationEngine's 5-second window.
 */
public class ModeAFileCollector {

    private static final String TAG = "SHIELD_MODE_A";

    private final UnifiedDetectionEngine detectionEngine;

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
        if (data == null) {
            return;
        }

        // Filename may be empty when the process exited before the 500ms daemon poll.
        // We still forward the event so SPRT and behavior scoring can act on the
        // write-rate signal even without a resolvable file path.
        String filename  = (data.filename != null && !data.filename.isEmpty())
                ? data.filename : "";
        String operation = mapOperation(data.operation);
        long sizeApprox  = Math.max(0L, data.bytes);

        Log.i(TAG, String.format(
                "%s pid=%d uid=%d file=%s bytes=%d",
                data.operation, data.pid, data.uid,
                filename.isEmpty() ? "<unknown>" : filename, data.bytes));

        FileSystemEvent event = new FileSystemEvent(
                filename,
                operation,
                sizeApprox,   // sizeBefore
                sizeApprox    // sizeAfter
        );

        // Enrich with kernel-attributed UID — the key advantage over Mode-B,
        // which must rely on heuristics for UID attribution.
        event.setUid(data.uid);

        // Tag event as MODE_A so it's distinguishable in the database.
        // TelemetryEvent.mode has no public setter so we use reflection.
        try {
            java.lang.reflect.Field modeField =
                    event.getClass().getSuperclass().getDeclaredField("mode");
            modeField.setAccessible(true);
            modeField.set(event, "MODE_A");
        } catch (Exception ignored) {
            // Non-critical: event still flows correctly through the pipeline.
        }

        detectionEngine.processFileEvent(event);
    }

    private static String mapOperation(String bpfOp) {
        if (bpfOp == null) return "UNKNOWN";
        switch (bpfOp) {
            case "WRITE":
            case "BURST": return "MODIFY";
            case "READ":   return "READ";
            case "FSYNC":  return "FSYNC";
            case "EXEC":   return "EXEC";
            default:       return bpfOp;
        }
    }
}
