package com.dearmoon.shield.collectors;

import android.os.FileObserver;
import android.util.Log;
import androidx.annotation.Nullable;
import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.snapshot.SnapshotManager;
import java.io.File;

public class FileSystemCollector extends FileObserver {
    private static final String TAG = "FileSystemCollector";
    private final TelemetryStorage storage;
    private final String monitoredPath;
    private UnifiedDetectionEngine detectionEngine;
    private SnapshotManager snapshotManager;

    private final java.util.Map<String, Long> lastEventMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEBOUNCE_DELAY_MS = 500;

    public FileSystemCollector(String path, TelemetryStorage storage) {
        super(path, CREATE | MODIFY | CLOSE_WRITE | DELETE | ALL_EVENTS);
        this.monitoredPath = path;
        this.storage = storage;
        Log.d(TAG, "FileSystemCollector created for: " + path);
    }

    public void setDetectionEngine(UnifiedDetectionEngine engine) {
        this.detectionEngine = engine;
    }

    public void setSnapshotManager(SnapshotManager manager) {
        this.snapshotManager = manager;
    }

    @Override
    public void onEvent(int event, @Nullable String path) {
        if (path == null)
            return;

        // Explicitly ignore OPEN events if they slip through, and CLOSE_NOWRITE
        if ((event & OPEN) != 0 || (event & 0x00000010) != 0) {
            return;
        }

        String fullPath = monitoredPath + File.separator + path;
        String rawOperation = getOperationName(event);

        File file = new File(fullPath);
        long size = file.exists() ? file.length() : 0;

        // 1. Logic for Logging (User Request: "deleted, modified or compressed")
        boolean shouldLog = false;
        String logOperation = rawOperation;

        if (rawOperation.equals("DELETE")) {
            shouldLog = true;
            logOperation = "DELETED";
        } else if (rawOperation.equals("CLOSE_WRITE")) {
            shouldLog = true;
            logOperation = "MODIFY";
        } else if (rawOperation.equals("CREATE")) {
            if (isArchive(path)) {
                shouldLog = true;
                logOperation = "COMPRESSED";
            }
        }

        Log.d(TAG, "FS Event detected: " + rawOperation + " on " + fullPath + " shouldLog=" + shouldLog);

        // Debounce
        if (shouldLog) {
            String key = fullPath + "|" + logOperation;
            long now = System.currentTimeMillis();
            Long lastTime = lastEventMap.get(key);

            if (lastTime == null || (now - lastTime > DEBOUNCE_DELAY_MS)) {
                lastEventMap.put(key, now);
                FileSystemEvent logEvent = new FileSystemEvent(fullPath, logOperation, size, size);
                storage.store(logEvent);
                Log.i(TAG, "LOGGED: " + logOperation + " - " + fullPath + " (" + size + " bytes)");
            }
        }

        // 2. Logic for Detection Engine - forward CLOSE_WRITE as MODIFY for analysis
        if (detectionEngine != null && rawOperation.equals("CLOSE_WRITE")) {
            FileSystemEvent detectionEvent = new FileSystemEvent(fullPath, "MODIFY", size, size);
            detectionEngine.processFileEvent(detectionEvent);
            Log.d(TAG, "Forwarded to detection engine: MODIFY - " + fullPath);
        }

        // 3. Logic for Snapshot Manager - track all file changes
        if (snapshotManager != null) {
            if (rawOperation.equals("CLOSE_WRITE") || rawOperation.equals("DELETE")) {
                snapshotManager.trackFileChange(fullPath);
                Log.d(TAG, "Tracked file change in snapshot: " + rawOperation + " - " + fullPath);
            } else if (rawOperation.equals("CREATE") && file.exists() && file.length() > 0) {
                // Track newly created files after they're written
                snapshotManager.trackFileChange(fullPath);
                Log.d(TAG, "Tracked new file in snapshot: " + fullPath);
            }
        }
    }

    private boolean isArchive(String path) {
        String lowerPath = path.toLowerCase();
        return lowerPath.endsWith(".zip") || lowerPath.endsWith(".rar") ||
                lowerPath.endsWith(".7z") || lowerPath.endsWith(".tar") ||
                lowerPath.endsWith(".gz") || lowerPath.endsWith(".bz2");
    }

    private String getOperationName(int event) {
        // Bitmask handling: check for specific flags
        if ((event & CREATE) != 0)
            return "CREATE";
        if ((event & OPEN) != 0)
            return "OPEN";
        if ((event & MODIFY) != 0)
            return "MODIFY";
        if ((event & CLOSE_WRITE) != 0)
            return "CLOSE_WRITE";
        if ((event & MOVED_TO) != 0)
            return "MOVED_TO";
        if ((event & DELETE) != 0)
            return "DELETE";
        return "UNKNOWN";
    }
}
