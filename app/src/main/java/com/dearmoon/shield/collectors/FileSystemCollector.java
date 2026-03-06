package com.dearmoon.shield.collectors;

import android.os.FileObserver;
import android.util.Log;
import androidx.annotation.Nullable;
import com.dearmoon.shield.ShieldStats;
import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.snapshot.SnapshotManager;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class FileSystemCollector extends FileObserver {
    private static final String TAG = "FileSystemCollector";
    private final TelemetryStorage storage;
    private final String monitoredPath;
    private UnifiedDetectionEngine detectionEngine;
    private SnapshotManager snapshotManager;
    private ShieldStats shieldStats;   // optional — set via setShieldStats()

    // LRU cache with max 1000 entries to prevent memory leak
    private final Map<String, Long> lastEventMap = new LinkedHashMap<String, Long>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 1000;
        }
    };
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

    public void setShieldStats(ShieldStats stats) {
        this.shieldStats = stats;
    }

    @Override
    public void onEvent(int event, @Nullable String path) {
        if (path == null)
            return;

        // Efficiently ignore common events that don't need processing
        if ((event & (OPEN | ACCESS | CLOSE_NOWRITE)) != 0) {
            return;
        }

        String fullPath = monitoredPath + File.separator + path;

        // Logical fix: Handle multi-flag events
        boolean isCloseWrite = (event & CLOSE_WRITE) != 0;
        boolean isDelete = (event & DELETE) != 0;
        boolean isCreate = (event & CREATE) != 0;
        boolean isMove = (event & MOVED_TO) != 0;

        // 1. Logic for Logging (User Request: "deleted, modified or compressed")
        boolean shouldLog = false;
        String logOperation = "UNKNOWN";

        if (isDelete) {
            shouldLog = true;
            logOperation = "DELETED";
        } else if (isCloseWrite) {
            shouldLog = true;
            logOperation = "MODIFY";
        } else if (isCreate && isArchive(path)) {
            shouldLog = true;
            logOperation = "COMPRESSED";
        }

        if (shouldLog) {
            // Only perform expensive File operations if we are actually logging/processing
            File file = new File(fullPath);
            long size = file.exists() ? file.length() : 0;

            String key = fullPath + "|" + logOperation;
            long now = System.currentTimeMillis();
            Long lastTime = lastEventMap.get(key);

            if (lastTime == null || (now - lastTime > DEBOUNCE_DELAY_MS)) {
                lastEventMap.put(key, now);
                FileSystemEvent logEvent = new FileSystemEvent(fullPath, logOperation, size, size);
                storage.store(logEvent);
                // Increment the live "files scanned" counter on the home dashboard.
                if (shieldStats != null) shieldStats.incrementFilesScanned(1);
                Log.i(TAG, "LOGGED: " + logOperation + " - " + fullPath + " (" + size + " bytes)");
            }
        }

        // 2. Logic for Detection Engine - forward CLOSE_WRITE as MODIFY for analysis
        if (detectionEngine != null && isCloseWrite) {
            File file = new File(fullPath);
            long size = file.exists() ? file.length() : 0;
            FileSystemEvent detectionEvent = new FileSystemEvent(fullPath, "MODIFY", size, size);
            detectionEngine.processFileEvent(detectionEvent);
            Log.d(TAG, "Forwarded to detection engine: MODIFY - " + fullPath);
        }

        // 3. Logic for Snapshot Manager - track all file changes
        if (snapshotManager != null) {
            if (isCloseWrite || isDelete) {
                snapshotManager.trackFileChange(fullPath);
                Log.d(TAG, "Tracked file change in snapshot: " + fullPath);
            } else if (isCreate || isMove) {
                File file = new File(fullPath);
                if (file.exists() && file.length() > 0) {
                    snapshotManager.trackFileChange(fullPath);
                    Log.d(TAG, "Tracked new file in snapshot: " + fullPath);
                }
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
        StringBuilder sb = new StringBuilder();
        if ((event & CREATE) != 0) sb.append("CREATE ");
        if ((event & MODIFY) != 0) sb.append("MODIFY ");
        if ((event & CLOSE_WRITE) != 0) sb.append("CLOSE_WRITE ");
        if ((event & DELETE) != 0) sb.append("DELETE ");
        if ((event & MOVED_TO) != 0) sb.append("MOVED_TO ");
        if ((event & MOVED_FROM) != 0) sb.append("MOVED_FROM ");

        String result = sb.toString().trim();
        return result.isEmpty() ? "UNKNOWN" : result;
    }
}
