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

/**
 * SECURITY FIX: Recursive file system monitoring to prevent ransomware bypass.
 * Monitors root directory AND all subdirectories up to MAX_DEPTH.
 * Prevents ransomware from encrypting files in nested directories undetected.
 */
public class RecursiveFileSystemCollector {
    private static final String TAG = "RecursiveFileSystemCollector";
    private static final int MAX_DEPTH = 8; // Deep monitoring for better coverage
    private static final int MAX_OBSERVERS = 1000; // Increased to support deeper recursion
    
    private final TelemetryStorage storage;
    private final String rootPath;
    private final ShieldStats shieldStats;    // shared across all child collectors
    private UnifiedDetectionEngine detectionEngine;
    private SnapshotManager snapshotManager;
    private com.dearmoon.shield.data.EventMerger sharedEventMerger;
    private final List<FileSystemCollector> collectors = new ArrayList<>();

    public RecursiveFileSystemCollector(String rootPath, TelemetryStorage storage) {
        this.rootPath    = rootPath;
        this.storage     = storage;
        this.shieldStats = null;   // legacy constructor — stats not tracked
    }

    public RecursiveFileSystemCollector(String rootPath, TelemetryStorage storage, Context context) {
        this.rootPath    = rootPath;
        this.storage     = storage;
        this.shieldStats = new ShieldStats(context);
    }
    
    public void setDetectionEngine(UnifiedDetectionEngine engine) {
        this.detectionEngine = engine;
    }
    
    public void setSnapshotManager(SnapshotManager manager) {
        this.snapshotManager = manager;
    }

    /** Provide the application-scoped shared EventMerger so events from all collectors
     *  in this tree feed into the same dedup window as Mode A events. */
    public void setEventMerger(com.dearmoon.shield.data.EventMerger merger) {
        this.sharedEventMerger = merger;
    }
    
    /**
     * Starts recursive monitoring of root directory and subdirectories.
     * Limits depth and total observers to prevent resource exhaustion.
     */
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
    
    /**
     * Recursively monitors directories up to MAX_DEPTH.
     */
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
            FileSystemCollector collector = new FileSystemCollector(
                directory.getAbsolutePath(), storage);
            
            if (detectionEngine != null) {
                collector.setDetectionEngine(detectionEngine);
            }
            
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
                // Skip hidden directories and system directories
                String name = subdir.getName();
                if (name.startsWith(".") || name.equals("Android") || name.equals("cache")) {
                    continue;
                }
                
                monitorDirectoryRecursive(subdir, depth + 1);
            }
        }
    }
    
    /**
     * Stops all file system collectors.
     */
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
    
    /**
     * Returns number of directories being monitored.
     */
    public int getMonitoredDirectoryCount() {
        return collectors.size();
    }
}
