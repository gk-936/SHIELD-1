package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SnapshotManager {
    private static final String TAG = "SnapshotManager";
    private static final String BACKUP_DIR = "secure_backups";
    
    private final Context context;
    private final SnapshotDatabase database;
    private final ExecutorService executor;
    private final File backupRoot;
    private long currentSnapshotId;
    private long activeAttackId = 0;

    public SnapshotManager(Context context) {
        this.context = context;
        this.database = new SnapshotDatabase(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.backupRoot = new File(context.getFilesDir(), BACKUP_DIR);
        this.backupRoot.mkdirs();
        this.currentSnapshotId = System.currentTimeMillis();
    }

    public void createBaselineSnapshot(String[] directories) {
        executor.execute(() -> {
            Log.i(TAG, "Creating baseline snapshot");
            int fileCount = 0;
            for (String dir : directories) {
                fileCount += scanDirectory(new File(dir), true);
            }
            context.getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_snapshot_time", System.currentTimeMillis())
                .apply();
            Log.i(TAG, "Baseline snapshot complete: " + fileCount + " files");
        });
    }

    /**
     * Performs an incremental snapshot by scanning for new or changed files.
     * Updates metadata but only backs up if not already backed up.
     */
    public void performIncrementalSnapshot(String[] directories) {
        executor.execute(() -> {
            Log.i(TAG, "Starting incremental snapshot");
            int updatedCount = 0;
            for (String dir : directories) {
                updatedCount += scanDirectory(new File(dir), false);
            }
            Log.i(TAG, "Incremental snapshot complete: " + updatedCount + " files processed");
        });
    }

    // when file changes are detected to enable real-time snapshot tracking
    public void trackFileChange(String filePath) {
        executor.execute(() -> {
            File file = new File(filePath);
            if (!file.exists()) {
                handleFileDeletion(filePath);
                return;
            }
            
            FileMetadata existing = database.getFileMetadata(filePath);
            long currentSize = file.length();
            long currentModified = file.lastModified();
            
            if (existing == null) {
                createNewFileSnapshot(file);
            } else if (existing.fileSize != currentSize || existing.lastModified != currentModified) {
                handleFileModification(file, existing);
            }
        });
    }

    private int scanDirectory(File dir, boolean isBaseline) {
        if (!dir.exists() || !dir.isDirectory()) return 0;
        
        File[] files = dir.listFiles();
        if (files == null) return 0;
        
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += scanDirectory(file, isBaseline);
            } else if (!isTempFile(file.getName())) {
                if (isBaseline) {
                    createNewFileSnapshot(file);
                    count++;
                }
            }
        }
        return count;
    }

    private void createNewFileSnapshot(File file) {
        try {
            long size = file.length();
            long modified = file.lastModified();
            String hash = calculateHash(file);
            
            FileMetadata metadata = new FileMetadata(
                file.getAbsolutePath(), size, modified, hash, currentSnapshotId
            );
            database.insertOrUpdateFile(metadata);
            
            Log.d(TAG, "Snapshot created: " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to snapshot file: " + file.getAbsolutePath(), e);
        }
    }

    private void handleFileModification(File file, FileMetadata existing) {
        try {
            if (!existing.isBackedUp) {
                backupOriginalFile(file, existing);
            }
            
            String newHash = calculateHash(file);
            existing.fileSize = file.length();
            existing.lastModified = file.lastModified();
            existing.sha256Hash = newHash;
            
            if (activeAttackId > 0) {
                existing.modifiedDuringAttack = activeAttackId;
            }
            
            database.insertOrUpdateFile(existing);
            Log.w(TAG, "File modified: " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle modification: " + file.getAbsolutePath(), e);
        }
    }

    private void backupOriginalFile(File file, FileMetadata metadata) {
        try {
            File backupFile = new File(backupRoot, metadata.snapshotId + "_" + file.getName());
            copyFile(file, backupFile);
            
            metadata.backupPath = backupFile.getAbsolutePath();
            metadata.isBackedUp = true;
            database.insertOrUpdateFile(metadata);
            
            Log.i(TAG, "Backup created: " + backupFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "Backup failed: " + file.getAbsolutePath(), e);
        }
    }

    private void handleFileDeletion(String filePath) {
        FileMetadata metadata = database.getFileMetadata(filePath);
        if (metadata != null && activeAttackId > 0) {
            metadata.modifiedDuringAttack = activeAttackId;
            database.insertOrUpdateFile(metadata);
            Log.w(TAG, "File deleted during attack: " + filePath);
        }
    }

    public void startAttackTracking() {
        activeAttackId = database.startAttackWindow();
        Log.e(TAG, "Attack tracking started: " + activeAttackId);
    }

    public void stopAttackTracking() {
        if (activeAttackId > 0) {
            database.endAttackWindow(activeAttackId);
            Log.i(TAG, "Attack tracking stopped: " + activeAttackId);
            activeAttackId = 0;
        }
    }

    /**
     * Automates the restoration of files affected during the active attack.
     */
    public RestoreEngine.RestoreResult performAutomatedRestore() {
        long attackIdToRestore = activeAttackId;
        if (attackIdToRestore == 0) {
            // Check if there was a very recent attack that just stopped
            attackIdToRestore = database.getLatestAttackId();
        }

        Log.e(TAG, "INITIATING AUTOMATED RESTORE for attack: " + attackIdToRestore);
        RestoreEngine engine = new RestoreEngine(context);
        RestoreEngine.RestoreResult result = engine.restoreFromAttack(attackIdToRestore);

        Log.i(TAG, "Automated restore complete: " + result.restoredCount + " files recovered");
        return result;
    }

    public long getActiveAttackId() {
        return activeAttackId;
    }

    public int getTotalFileCount() {
        return database.getTotalFileCount();
    }

    public int getInfectedFileCount() {
        return database.getInfectedFileCount();
    }

    /**
     * Recursively counts all files in the monitored directories.
     */
    public int getTotalMonitoredFileCount(String[] directories) {
        int count = 0;
        for (String dir : directories) {
            count += countFilesRecursive(new File(dir));
        }
        return count;
    }

    private int countFilesRecursive(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += countFilesRecursive(file);
            } else if (!isTempFile(file.getName())) {
                count++;
            }
        }
        return count;
    }

    private String calculateHash(File file) throws Exception {
        if (file.length() > 50 * 1024 * 1024) {
            return "LARGE_FILE";
        }
        
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        
        byte[] hash = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    private void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private boolean isTempFile(String name) {
        String lower = name.toLowerCase();
        return lower.startsWith(".") || lower.contains(".tmp") || 
               lower.contains("~") || lower.contains(".swp");
    }

    public void shutdown() {
        executor.shutdown();
    }
}
