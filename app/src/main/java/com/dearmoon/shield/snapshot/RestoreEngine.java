package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

public class RestoreEngine {
    private static final String TAG = "RestoreEngine";
    
    private final Context context;
    private final SnapshotDatabase database;

    public RestoreEngine(Context context) {
        this.context = context;
        this.database = new SnapshotDatabase(context);
    }

    public RestoreResult restoreFromAttack(long attackId) {
        Log.i(TAG, "Starting selective restore for attack: " + attackId);
        
        RestoreResult result = new RestoreResult();
        
        if (attackId <= 0) {
            Log.w(TAG, "Invalid attack ID: " + attackId);
            result.noChanges = true;
            return result;
        }
        
        List<FileMetadata> affectedFiles = database.getFilesModifiedDuringAttack(attackId);
        Log.i(TAG, "Found " + affectedFiles.size() + " files modified during attack " + attackId);
        
        if (affectedFiles.isEmpty()) {
            Log.w(TAG, "No files to restore");
            result.noChanges = true;
            return result;
        }
        
        for (FileMetadata metadata : affectedFiles) {
            try {
                RestoreAction action = restoreFile(metadata);
                if (action == RestoreAction.RESTORED) {
                    result.restoredCount++;
                    Log.i(TAG, "Restored: " + metadata.filePath);
                } else if (action == RestoreAction.FAILED) {
                    result.failedCount++;
                }
            } catch (Exception e) {
                Log.e(TAG, "Restore failed: " + metadata.filePath, e);
                result.failedCount++;
            }
        }
        
        if (result.restoredCount == 0 && result.failedCount == 0) {
            result.noChanges = true;
        }
        
        Log.i(TAG, "Restore complete: " + result.restoredCount + " restored, " + result.failedCount + " failed");
        return result;
    }

    private enum RestoreAction {
        RESTORED, SKIPPED, FAILED
    }

    private RestoreAction restoreFile(FileMetadata metadata) throws Exception {
        File targetFile = new File(metadata.filePath);
        
        if (!metadata.isBackedUp || metadata.backupPath == null) {
            Log.d(TAG, "No backup needed: " + metadata.filePath);
            return RestoreAction.SKIPPED;
        }
        
        File backupFile = new File(metadata.backupPath);
        if (!backupFile.exists()) {
            Log.e(TAG, "Backup file missing: " + metadata.backupPath);
            return RestoreAction.FAILED;
        }
        
        if (!targetFile.exists()) {
            Log.i(TAG, "Restoring deleted file: " + metadata.filePath);
            copyFile(backupFile, targetFile);
            return RestoreAction.RESTORED;
        }
        
        String currentHash = calculateQuickHash(targetFile);
        if (!currentHash.equals(metadata.sha256Hash)) {
            Log.i(TAG, "Restoring modified file: " + metadata.filePath);
            targetFile.delete();
            copyFile(backupFile, targetFile);
            return RestoreAction.RESTORED;
        }
        
        Log.d(TAG, "File unchanged, skipping: " + metadata.filePath);
        return RestoreAction.SKIPPED;
    }

    private void copyFile(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        dst.setLastModified(src.lastModified());
    }

    private String calculateQuickHash(File file) {
        try {
            if (file.length() > 50 * 1024 * 1024) {
                return "LARGE_FILE";
            }
            
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
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
        } catch (Exception e) {
            return "ERROR";
        }
    }

    public static class RestoreResult {
        public int restoredCount = 0;
        public int failedCount = 0;
        public boolean noChanges = false;
    }
}
