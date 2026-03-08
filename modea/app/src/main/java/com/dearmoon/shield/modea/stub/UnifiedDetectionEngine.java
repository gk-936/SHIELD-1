package com.dearmoon.shield.modea.stub;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * STUB — stands in for com.dearmoon.shield.detection.UnifiedDetectionEngine.
 *
 * In the standalone Mode-A APK this stub:
 *   - Receives FileSystemEvent objects from ModeAFileCollector.
 *   - Logs every event to logcat under SHIELD_MODE_A_DETECTION.
 *   - Appends formatted event strings to a static in-memory list
 *     that MainActivity reads to populate the live event view.
 *   - Does NOT perform entropy / KL / SPRT analysis (that lives in the
 *     real engine; these are prototype-only observations).
 *
 * When merging Mode-A into the main SHIELD project:
 *   1. Delete this file.
 *   2. Update imports in ModeAService and ModeAFileCollector to use
 *      com.dearmoon.shield.detection.UnifiedDetectionEngine instead.
 *   3. Pass the real UnifiedDetectionEngine instance to ModeAFileCollector.
 */
public class UnifiedDetectionEngine {

    private static final String TAG = "SHIELD_MODE_A_DETECTION";

    /** Shared live event log — read by MainActivity for display. */
    public static final List<String> EVENT_LOG = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_ENTRIES   = 500;

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final Context        context;
    private final HandlerThread  detectionThread;
    private final Handler        detectionHandler;

    public UnifiedDetectionEngine(Context context) {
        this(context, null);
    }

    public UnifiedDetectionEngine(Context context, SnapshotManager snapshotManager) {
        this.context = context.getApplicationContext();

        detectionThread = new HandlerThread("StubDetectionThread");
        detectionThread.start();
        detectionHandler = new Handler(detectionThread.getLooper());

        Log.i(TAG, "[STUB] UnifiedDetectionEngine initialised");
    }

    /**
     * Entry point — identical signature to the real engine.
     * Events are processed on the internal HandlerThread.
     */
    public void processFileEvent(FileSystemEvent event) {
        detectionHandler.post(() -> logEvent(event));
    }

    private void logEvent(FileSystemEvent event) {
        try {
            String operation = event.toJSON().optString("operation", "?");
            String filePath  = event.toJSON().optString("filePath",  "?");
            int    uid       = event.getUid();
            long   size      = event.toJSON().optLong("fileSizeAfter", 0);
            String ts        = SDF.format(new Date());

            String line = String.format(Locale.US,
                    "[%s] %s  uid=%-6d  bytes=%-8d  %s",
                    ts, padRight(operation, 6), uid, size, filePath);

            Log.i(TAG, line);

            EVENT_LOG.add(line);
            // Trim to avoid unbounded memory growth
            while (EVENT_LOG.size() > MAX_LOG_ENTRIES) {
                EVENT_LOG.remove(0);
            }

        } catch (Exception e) {
            Log.e(TAG, "[STUB] Error logging event: " + e.getMessage());
        }
    }

    public void setSnapshotManager(SnapshotManager manager) { /* no-op */ }

    public void shutdown() {
        detectionThread.quitSafely();
        Log.i(TAG, "[STUB] UnifiedDetectionEngine shut down");
    }

    private static String padRight(String s, int n) {
        return String.format(Locale.US, "%-" + n + "s", s);
    }
}
