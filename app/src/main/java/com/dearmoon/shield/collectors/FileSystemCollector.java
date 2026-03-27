package com.dearmoon.shield.collectors;

import android.content.Context;
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
    private com.dearmoon.shield.data.EventMerger eventMerger = new com.dearmoon.shield.data.EventMerger();
    private final String monitoredPath;
    private final com.dearmoon.shield.data.ShieldEventBus eventBus = com.dearmoon.shield.data.ShieldEventBus.getInstance();
    private SnapshotManager snapshotManager;
    private ShieldStats shieldStats;   // optional — set via setShieldStats()

    // LRU cache 1000 entries
    private final Map<String, Long> lastEventMap = new LinkedHashMap<String, Long>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 1000;
        }
    };
    private static final long DEBOUNCE_DELAY_MS = 500;
    // Internal path self-exclusion
    private final String[] selfExcludeDirs;

    // Reserved SHIELD extensions
    private static final String[] SHIELD_EXTENSIONS = { ".key", ".enc" };

    public FileSystemCollector(String path, TelemetryStorage storage) {
        super(path, CREATE | MODIFY | CLOSE_WRITE | DELETE | ALL_EVENTS);
        this.monitoredPath = path;
        this.storage = storage;
        this.selfExcludeDirs = new String[0]; // No context: no exclusion
        Log.d(TAG, "FileSystemCollector created (no ctx) for: " + path);
    }

    public FileSystemCollector(String path, TelemetryStorage storage, Context context) {
        super(path, CREATE | MODIFY | CLOSE_WRITE | DELETE | ALL_EVENTS);
        this.monitoredPath = path;
        this.storage = storage;
        this.selfExcludeDirs = buildSelfExcludeDirs(context);
        Log.d(TAG, "FileSystemCollector created for: " + path);
    }

    private static String[] buildSelfExcludeDirs(Context ctx) {
        // Runtime files path
        // ctx.getDatabaseDir() → /data/user/0/<pkg>/databases
        String filesDir = ctx.getFilesDir().getAbsolutePath();
        String dbDir    = ctx.getDatabasePath("").getParent();
        String dataDir  = ctx.getFilesDir().getParentFile().getAbsolutePath();
        // Also include the legacy /data/data/ symlink just in case
        String legacyDataDir = "/data/data/" + ctx.getPackageName();
        return new String[] { filesDir, dbDir, dataDir, legacyDataDir };
    }

    @Deprecated
    public void setDetectionEngine(UnifiedDetectionEngine engine) {
        // Obsolete: events are now published to ShieldEventBus
    }

    public void setSnapshotManager(SnapshotManager manager) {
        this.snapshotManager = manager;
    }

    // Shared event merger
    public void setEventMerger(com.dearmoon.shield.data.EventMerger merger) {
        this.eventMerger = merger;
    }

    public void setShieldStats(ShieldStats stats) {
        this.shieldStats = stats;
    }

    @Override
    public void onEvent(int event, @Nullable String path) {
        if (path == null)
            return;

        // Ignore common events
        if ((event & (OPEN | ACCESS | CLOSE_NOWRITE)) != 0) {
            return;
        }

        String fullPath = monitoredPath + File.separator + path;

        // Exclude internal paths
        for (String excludeDir : selfExcludeDirs) {
            if (excludeDir != null && !excludeDir.isEmpty() && fullPath.startsWith(excludeDir)) {
                Log.d(TAG, "Ignoring self-generated event (path match): " + fullPath);
                return;
            }
        }

        // Block SHIELD extensions
        String lowerPath = fullPath.toLowerCase();
        for (String ext : SHIELD_EXTENSIONS) {
            if (lowerPath.endsWith(ext)) {
                Log.d(TAG, "Ignoring SHIELD backup extension (" + ext + "): " + fullPath);
                return;
            }
        }

        // Handle multi-flag events
        boolean isCloseWrite = (event & CLOSE_WRITE) != 0;
        boolean isDelete = (event & DELETE) != 0;
        boolean isCreate = (event & CREATE) != 0;
        boolean isMove = (event & MOVED_TO) != 0;

        // File logging logic
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
            File file = new File(fullPath);
            long size = file.exists() ? file.length() : 0;
            String key = fullPath + "|" + logOperation;
            long now = System.currentTimeMillis();
            Long lastTime = lastEventMap.get(key);
            if (lastTime == null || (now - lastTime > DEBOUNCE_DELAY_MS)) {
                lastEventMap.put(key, now);
                FileSystemEvent logEvent = new FileSystemEvent(fullPath, logOperation, size, size);
                com.dearmoon.shield.data.EventMerger.MergedEvent merged = eventMerger.mergeEvent(logEvent, "MODE_B");
                com.dearmoon.shield.data.HybridFileSystemEvent hybrid = new com.dearmoon.shield.data.HybridFileSystemEvent(logEvent, merged.source, merged.mergeFlag);
                storage.store(hybrid);
                if (shieldStats != null) shieldStats.incrementFilesScanned(1);
                Log.i(TAG, "LOGGED: " + logOperation + " - " + fullPath + " (" + size + " bytes)");
            }
        }

        // Forward to framework via EventBus
        if (isCloseWrite) {
            File file = new File(fullPath);
            long size = file.exists() ? file.length() : 0;
            FileSystemEvent detectionEvent = new FileSystemEvent(fullPath, "MODIFY", size, size);
            eventBus.publishFileSystemEvent(detectionEvent);
        }

        // Track snapshot changes
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
