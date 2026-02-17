package com.dearmoon.shield.collectors;

import android.os.FileObserver;
import android.util.Log;
import androidx.annotation.Nullable;
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
    private static final int MAX_DEPTH = 3; // Limit depth to prevent performance issues
    private static final int MAX_OBSERVERS = 100; // Limit total observers to prevent resource exhaustion
    
    private final TelemetryStorage storage;
    private final String rootPath;
    private UnifiedDetectionEngine detectionEngine;
    private SnapshotManager snapshotManager;
    private final List<FileSystemCollector> collectors = new ArrayList<>();
    
    public RecursiveFileSystemCollector(String rootPath, TelemetryStorage storage) {
        this.rootPath = rootPath;
        this.storage = storage;
    }
    
    public void setDetectionEngine(UnifiedDetectionEngine engine) {
        this.detectionEngine = engine;
    }
    
    public void setSnapshotManager(SnapshotManager manager) {
        this.snapshotManager = manager;
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
