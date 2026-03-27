package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

// RestoreEngine v2
// Secure file restoration
public class RestoreEngine {

    private static final String TAG = "RestoreEngine";

    private final Context                 context;
    private final SnapshotDatabase        database;
    private final BackupEncryptionManager encManager;   // may be null for legacy calls

    // Constructors

    // Legacy constructor
    public RestoreEngine(Context context) {
        this(context, new BackupEncryptionManager(context));
    }

    // Preferred constructor
    public RestoreEngine(Context context, BackupEncryptionManager encManager) {
        this.context    = context;
        this.database   = SnapshotDatabase.getInstance(context);
        this.encManager = encManager;
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    public RestoreResult restoreFromAttack(long attackId) {
        Log.i(TAG, "Starting expanded restore for attack: " + attackId);

        RestoreResult result = new RestoreResult();
        if (attackId <= 0) {
            Log.i(TAG, "No active attack ID provided. Initiating Deep System Integrity Audit...");
        }

        // Restore pre-attack files
        long attackStartTime = database.getAttackWindowStartTime(attackId);
        List<FileMetadata> allBackedUp = database.getAllBackedUpFiles();
        int restoreCandidates = 0;
        // Active Healing: Proactively hunt for ransom victims
        for (FileMetadata metadata : allBackedUp) {
            try {
                File onDisk = new File(metadata.filePath);
                
                // 🛡️ SHIELD HEALING LOGIC 🛡️
                // 1. If we marked it during attack -> HEAL
                // 2. If it has a ransom extension on disk (.enc, .locked) -> HEAL
                // 3. (PROACTIVE) If file was modified recently AND hash changed from baseline -> HEAL
                
                boolean wasModified = (metadata.modifiedDuringAttack == attackId);
                boolean isVictimOfRansom = isSuspectedVictimOnDisk(metadata.filePath);
                boolean integrityFailed = false;

                if (onDisk.exists()) {
                    String currentHash = calculateQuickHash(onDisk);
                    Log.d(TAG, "VERIFYING: " + metadata.filePath + " | OnDiskHash=" + currentHash + " | BaselineHash=" + metadata.sha256Hash);
                    if (!currentHash.equals(metadata.sha256Hash) && !currentHash.equals("LARGE_FILE")) {
                         Log.e(TAG, "INTEGRITY FAILURE: Hash mismatch detected for " + metadata.filePath);
                         integrityFailed = true;
                    }
                } else {
                    Log.w(TAG, "FILE MISSING: " + metadata.filePath + " - Recovery marked as mandatory");
                    integrityFailed = true; // Heals deleted files too
                }

                if (wasModified || isVictimOfRansom || integrityFailed) {
                    RestoreAction action = restoreFile(metadata);
                    if (action == RestoreAction.RESTORED) {
                        result.restoredCount++;
                        if (isVictimOfRansom) {
                            Log.w(TAG, "ACTIVE HEALING: Restored ransom victim: " + metadata.filePath);
                            cleanupRansomVictim(metadata.filePath);
                        } else {
                            Log.i(TAG, "Restored modified file: " + metadata.filePath);
                        }
                    } else if (action == RestoreAction.FAILED) {
                        result.failedCount++;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Restore failed: " + metadata.filePath, e);
                result.failedCount++;
            }
        }

        // Post-restore validation
        int missingCount = 0;
        for (FileMetadata metadata : allBackedUp) {
            if (metadata.lastModified <= attackStartTime) {
                File f = new File(metadata.filePath);
                if (!f.exists()) {
                    missingCount++;
                    Log.w(TAG, "File missing after restore: " + metadata.filePath);
                }
            }
        }
        result.noChanges = (result.restoredCount == 0 && result.failedCount == 0);
        Log.i(TAG, "Restore complete: "
                + result.restoredCount + " restored, " + result.failedCount + " failed, "
                + missingCount + " missing after restore");
        return result;
    }

    // =========================================================================
    //  Internal
    // =========================================================================

    private enum RestoreAction { RESTORED, SKIPPED, FAILED }

    private RestoreAction restoreFile(FileMetadata metadata) throws Exception {
        if (!metadata.isBackedUp || metadata.backupPath == null) {
            Log.d(TAG, "No backup needed: " + metadata.filePath);
            return RestoreAction.SKIPPED;
        }

        // Decrypt backup path
        String realBackupPath = metadata.backupPath;
        if (encManager != null) {
            try {
                realBackupPath = encManager.decryptColumn(metadata.backupPath);
            } catch (Exception ignored) {
                // Legacy entry with plaintext path – use as-is
            }
        }

        File backupFile = new File(realBackupPath);
        if (!backupFile.exists()) {
            Log.e(TAG, "Backup file missing: " + realBackupPath);
            return RestoreAction.FAILED;
        }

        File targetFile = new File(metadata.filePath);

        // Check restoration need
        if (targetFile.exists()) {
            String currentHash = calculateQuickHash(targetFile);
            if (currentHash.equals(metadata.sha256Hash)) {
                Log.d(TAG, "File unchanged, skipping: " + metadata.filePath);
                return RestoreAction.SKIPPED;
            }
            targetFile.delete();
        } else {
            Log.i(TAG, "Restoring deleted file: " + metadata.filePath);
        }

        targetFile.getParentFile().mkdirs();

        // Decrypt or copy
        boolean encrypted = metadata.encryptedKey != null && encManager != null;
        if (encrypted) {
            // AES-GCM decryption
            encManager.decryptFile(backupFile, targetFile, metadata.encryptedKey);
            Log.i(TAG, "Decrypted & restored: " + metadata.filePath);
        } else {
            // Plaintext legacy backup
            copyFile(backupFile, targetFile);
            Log.i(TAG, "Copied (plaintext backup): " + metadata.filePath);
        }

        return RestoreAction.RESTORED;
    }

    // =========================================================================
    //  Utility helpers
    // =========================================================================

    private void copyFile(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (FileInputStream  in  = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        dst.setLastModified(src.lastModified());
    }

    private String calculateQuickHash(File file) {
        try {
            if (file.length() > 50 * 1024 * 1024) return "LARGE_FILE";
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private boolean isSuspectedVictimOnDisk(String originalPath) {
        String[] ransomExtensions = {".enc", ".locked", ".wncry", ".crypt", ".zepto", ".locky"};
        for (String ext : ransomExtensions) {
            if (new File(originalPath + ext).exists()) return true;
        }
        return false;
    }

    private void cleanupRansomVictim(String originalPath) {
        String[] ransomExtensions = {".enc", ".locked", ".wncry", ".crypt", ".zepto", ".locky"};
        for (String ext : ransomExtensions) {
            File victim = new File(originalPath + ext);
            if (victim.exists()) {
                if (victim.delete()) {
                    Log.w(TAG, "Deleted ransom victim: " + victim.getAbsolutePath());
                } else {
                    Log.e(TAG, "Failed to delete ransom victim: " + victim.getAbsolutePath());
                }
            }
        }
    }

    // =========================================================================
    //  Result class
    // =========================================================================

    public static class RestoreResult {
        public int     restoredCount = 0;
        public int     failedCount   = 0;
        public boolean noChanges     = false;
    }
}
