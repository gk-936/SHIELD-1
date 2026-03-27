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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Recursive monitoring fix
// Monitor all subdirectories
// Prevent undetected encryption
public class RecursiveFileSystemCollector {
    private static final String TAG = "RecursiveFileSystemCollector";
    private static final int MAX_DEPTH = 8; // Deep directory monitoring
    private static final int MAX_OBSERVERS = 1000; // Higher observer count
    
    private final TelemetryStorage storage;
    private final String rootPath;
    private final Context context;           // Path exclusion context
    private final ShieldStats shieldStats;   // Shared stats object
    private SnapshotManager snapshotManager;
    private com.dearmoon.shield.data.EventMerger sharedEventMerger;
    private final List<FileSystemCollector> collectors = new ArrayList<>();

    public RecursiveFileSystemCollector(String rootPath, TelemetryStorage storage) {
        this.rootPath    = rootPath;
        this.storage     = storage;
        this.context     = null;
        this.shieldStats = null;   // Legacy constructor
    }

    public RecursiveFileSystemCollector(String rootPath, TelemetryStorage storage, Context context) {
        this.rootPath    = rootPath;
        this.storage     = storage;
        this.context     = context;
        this.shieldStats = new ShieldStats(context);
    }
    

    
    public void setSnapshotManager(SnapshotManager manager) {
        this.snapshotManager = manager;
    }

    // Shared event merger
    public void setEventMerger(com.dearmoon.shield.data.EventMerger merger) {
        this.sharedEventMerger = merger;
    }
    
    // Start recursive monitoring
    public void startWatching() {
        File root = new File(rootPath);
        if (!root.exists() || !root.isDirectory()) {
            Log.w(TAG, "Root path does not exist or is not a directory: " + rootPath);
            return;
        }
        
        Log.i(TAG, "Starting recursive monitoring of: " + rootPath);
        monitorDirectoryRecursive(root, 0);
        Log.i(TAG, "Recursive monitoring started: " + collectors.size() + " directories monitored");
    }
    
    // Monitor directories recursively
    private void monitorDirectoryRecursive(File directory, int depth) {
        // Safety checks
        if (depth > MAX_DEPTH) {
            Log.d(TAG, "Max depth reached, skipping: " + directory.getAbsolutePath());
            return;
        }
        
        if (collectors.size() >= MAX_OBSERVERS) {
            Log.w(TAG, "Max observers reached (" + MAX_OBSERVERS + "), stopping recursion");
            return;
        }
        
        if (!directory.exists() || !directory.isDirectory() || !directory.canRead()) {
            return;
        }
        
        // Create collector for this directory
        try {
            // Pass exclusion context
            FileSystemCollector collector = new FileSystemCollector(
                directory.getAbsolutePath(), storage, context);
            
            if (snapshotManager != null) {
                collector.setSnapshotManager(snapshotManager);
            }

            if (sharedEventMerger != null) {
                collector.setEventMerger(sharedEventMerger);
            }

            if (shieldStats != null) {
                collector.setShieldStats(shieldStats);
            }

            collector.startWatching();
            collectors.add(collector);
            Log.d(TAG, "Monitoring [depth=" + depth + "]: " + directory.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create collector for: " + directory.getAbsolutePath(), e);
            return;
        }
        
        // Recursively monitor subdirectories
        File[] subdirs = directory.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                // Skip private directories unless they are the specific monitored target
                String name = subdir.getName();
                String absPath = subdir.getAbsolutePath();
                
                // FIXED: Don't skip if the path is explicitly allowed or a sub-path of an allowed root
                boolean isInternalSystem = name.equals("Android") || name.equals("cache");
                if (name.startsWith(".") || (isInternalSystem && !absPath.startsWith(rootPath))) {
                    continue;
                }
                
                // Exclude SHIELD's own private storage (Internal + External)
                String internalPrivate = context.getFilesDir().getParentFile().getAbsolutePath();
                File externalContextDir = (context != null) ? context.getExternalFilesDir(null) : null;
                String externalPrivate = (externalContextDir != null) ? 
                    externalContextDir.getParentFile().getAbsolutePath() : "";

                if (absPath.equals(internalPrivate) || absPath.startsWith(internalPrivate + "/") ||
                    (!externalPrivate.isEmpty() && (absPath.equals(externalPrivate) || absPath.startsWith(externalPrivate + "/")))) {
                    Log.d(TAG, "Skipping SHIELD private storage: " + absPath);
                    continue;
                }

                monitorDirectoryRecursive(subdir, depth + 1);
            }
        }
    }
    
    // Stop all collectors
    public void stopWatching() {
        Log.i(TAG, "Stopping recursive monitoring: " + collectors.size() + " collectors");
        for (FileSystemCollector collector : collectors) {
            try {
                collector.stopWatching();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping collector", e);
            }
        }
        collectors.clear();
    }
    
    // Directory count
    public int getMonitoredDirectoryCount() {
        return collectors.size();
    }
}
