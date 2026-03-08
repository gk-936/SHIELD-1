package com.dearmoon.shield.modea;

import android.util.Log;

import com.dearmoon.shield.modea.stub.FileSystemEvent;
import com.dearmoon.shield.modea.stub.UnifiedDetectionEngine;

/**
 * ModeAFileCollector — converts kernel telemetry events into FileSystemEvent
 * objects and forwards them to the detection engine.
 *
 * Standalone APK version: uses stub FileSystemEvent and stub
 * UnifiedDetectionEngine from the .stub package.
 *
 * -----------------------------------------------------------------------
 * MERGE NOTE — when integrating into the main SHIELD project:
 *   Replace:
 *     import com.dearmoon.shield.modea.stub.FileSystemEvent;
 *     import com.dearmoon.shield.modea.stub.UnifiedDetectionEngine;
 *   With:
 *     import com.dearmoon.shield.data.FileSystemEvent;
 *     import com.dearmoon.shield.detection.UnifiedDetectionEngine;
 *   Remove the reflection block (setMode) — add setMode() to TelemetryEvent instead,
 *   or keep the reflection approach if modifying the base class is undesirable.
 * -----------------------------------------------------------------------
 */
public class ModeAFileCollector {

    private static final String TAG = "SHIELD_MODE_A";

    private final UnifiedDetectionEngine detectionEngine;

    public ModeAFileCollector(UnifiedDetectionEngine detectionEngine) {
        this.detectionEngine = detectionEngine;
    }

    /**
     * Called by ModeAService for each event received from the root daemon.
     */
    public void onKernelEvent(ShieldEventData data) {
        if (data == null) return;

        String filename   = (data.filename != null && !data.filename.isEmpty())
                            ? data.filename : "<unknown>";
        String operation  = mapOperation(data.operation);
        long   sizeApprox = Math.max(0L, data.bytes);

        FileSystemEvent event = new FileSystemEvent(
                filename, operation, sizeApprox, sizeApprox);

        // Accurate kernel-attributed UID — key advantage over Mode-B
        event.setUid(data.uid);
        // Standalone APK has a public setMode() in the stub TelemetryEvent
        event.setMode("MODE_A");

        Log.i(TAG, String.format(
                "%s pid=%d uid=%d file=%s bytes=%d",
                data.operation, data.pid, data.uid, filename, data.bytes));

        detectionEngine.processFileEvent(event);
    }

    private static String mapOperation(String bpfOp) {
        if (bpfOp == null) return "UNKNOWN";
        switch (bpfOp) {
            case "WRITE": return "MODIFY";
            case "READ":  return "READ";
            case "FSYNC": return "FSYNC";
            case "EXEC":  return "EXEC";
            default:      return bpfOp;
        }
    }
}
