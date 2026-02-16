package com.dearmoon.shield.testing;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized test file tracking system
 * Ensures ALL test files are tracked and can be cleaned up completely
 */
public class TestFileManager {
    private static final String TAG = "TestFileManager";
    private static final String TEST_ROOT = "SHIELD_TEST";
    
    private final Context context;
    private final File testRootDir;
    private final List<String> createdFiles = new CopyOnWriteArrayList<>();
    
    public TestFileManager(Context context) {
        this.context = context;
        this.testRootDir = new File(android.os.Environment.getExternalStorageDirectory(), TEST_ROOT);
        Log.i(TAG, "TestFileManager initialized: " + testRootDir.getAbsolutePath());
    }
    
    /**
     * Get dedicated test root directory
     * ALL test files MUST be created under this directory
     */
    public File getTestRootDir() {
        if (!testRootDir.exists()) {
            testRootDir.mkdirs();
            Log.i(TAG, "Created test root directory: " + testRootDir.getAbsolutePath());
        }
        return testRootDir;
    }
    
    /**
     * Track a created file
     */
    public void trackFile(File file) {
        String path = file.getAbsolutePath();
        if (!createdFiles.contains(path)) {
            createdFiles.add(path);
            Log.d(TAG, "Tracked file: " + path);
        }
    }
    
    /**
     * Track a created file by path
     */
    public void trackFile(String path) {
        if (!createdFiles.contains(path)) {
            createdFiles.add(path);
            Log.d(TAG, "Tracked file: " + path);
        }
    }
    
    /**
     * Get all tracked files
     */
    public List<String> getAllTrackedFiles() {
        return new ArrayList<>(createdFiles);
    }
    
    /**
     * Get count of tracked files
     */
    public int getTrackedFileCount() {
        return createdFiles.size();
    }
    
    /**
     * Clear all test files
     * Returns CleanupResult with statistics
     */
    public CleanupResult clearAllTestFiles() {
        Log.i(TAG, "Starting cleanup of " + createdFiles.size() + " tracked files...");
        
        int deletedCount = 0;
        int failedCount = 0;
        List<String> failedFiles = new ArrayList<>();
        
        // Delete tracked files
        for (String path : createdFiles) {
            File file = new File(path);
            if (file.exists()) {
                if (file.delete()) {
                    deletedCount++;
                    Log.d(TAG, "Deleted: " + path);
                } else {
                    failedCount++;
                    failedFiles.add(path);
                    Log.w(TAG, "Failed to delete: " + path);
                }
            }
        }
        
        // Clear tracking list
        createdFiles.clear();
        
        // Delete empty directories
        deleteEmptyDirectories(testRootDir);
        
        // Delete root if empty
        if (testRootDir.exists() && testRootDir.listFiles() != null && testRootDir.listFiles().length == 0) {
            if (testRootDir.delete()) {
                Log.i(TAG, "Deleted empty test root directory");
            }
        }
        
        CleanupResult result = new CleanupResult(deletedCount, failedCount, failedFiles);
        Log.i(TAG, "Cleanup complete: " + deletedCount + " deleted, " + failedCount + " failed");
        
        return result;
    }
    
    /**
     * Recursively delete empty directories
     */
    private void deleteEmptyDirectories(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteEmptyDirectories(file);
                }
            }
        }
        
        // Delete if empty
        files = dir.listFiles();
        if (files != null && files.length == 0 && !dir.equals(testRootDir)) {
            if (dir.delete()) {
                Log.d(TAG, "Deleted empty directory: " + dir.getAbsolutePath());
            }
        }
    }
    
    /**
     * Cleanup result statistics
     */
    public static class CleanupResult {
        public final int deletedCount;
        public final int failedCount;
        public final List<String> failedFiles;
        
        public CleanupResult(int deletedCount, int failedCount, List<String> failedFiles) {
            this.deletedCount = deletedCount;
            this.failedCount = failedCount;
            this.failedFiles = failedFiles;
        }
        
        public boolean isComplete() {
            return failedCount == 0;
        }
        
        @Override
        public String toString() {
            return "Deleted: " + deletedCount + ", Failed: " + failedCount;
        }
    }
}
