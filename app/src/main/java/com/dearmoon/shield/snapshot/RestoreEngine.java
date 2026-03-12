package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * RestoreEngine – v2
 *
 * Handles secure restoration of backed-up files:
 *   • Detects whether a backup file is AES-GCM encrypted (metadata.encryptedKey != null)
 *     and decrypts it transparently before writing to the target path.
 *   • Falls back to plain copy for legacy (pre-v2) non-encrypted backups.
 *   • Decrypts the stored backup_path column via BackupEncryptionManager.
 *   • GCM authentication automatically raises an exception if the ciphertext was
 *     tampered with, so an attacker cannot silently inject a malicious payload.
 */
public class RestoreEngine {

    private static final String TAG = "RestoreEngine";

    private final Context                 context;
    private final SnapshotDatabase        database;
    private final BackupEncryptionManager encManager;   // may be null for legacy calls

    // ── Constructors ──────────────────────────────────────────────────────────

    /** Legacy constructor – used by RecoveryActivity.  Creates its own enc manager. */
    public RestoreEngine(Context context) {
        this(context, new BackupEncryptionManager(context));
    }

    /** Preferred constructor – shares the enc manager from SnapshotManager. */
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
            Log.w(TAG, "Invalid attack ID: " + attackId);
            result.noChanges = true;
            return result;
        }

        // 1. Restore all files that were backed up before the attack window started
        long attackStartTime = database.getAttackWindowStartTime(attackId);
        List<FileMetadata> allBackedUp = database.getAllBackedUpFiles();
        int restoreCandidates = 0;
        for (FileMetadata metadata : allBackedUp) {
            // Only restore files that existed before the attack
            if (metadata.lastModified <= attackStartTime) {
                restoreCandidates++;
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
        }

        // 2. Validation: check for missing files that should have been restored
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

        // ── Decrypt the stored backup_path column (Feature 3) ──────────────
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

        // ── Check if target file still needs restoring ─────────────────────
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

        // ── Decrypt (v2) or plain copy (v1 legacy) ─────────────────────────
        boolean encrypted = metadata.encryptedKey != null && encManager != null;
        if (encrypted) {
            // AES-256-GCM decryption – GCM tag check ensures ciphertext integrity
            encManager.decryptFile(backupFile, targetFile, metadata.encryptedKey);
            Log.i(TAG, "Decrypted & restored: " + metadata.filePath);
        } else {
            // Legacy plaintext backup
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

    // =========================================================================
    //  Result class
    // =========================================================================

    public static class RestoreResult {
        public int     restoredCount = 0;
        public int     failedCount   = 0;
        public boolean noChanges     = false;
    }
}
