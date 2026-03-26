package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

// Snapshot integrity checker
public class SnapshotIntegrityChecker {

    private static final String TAG = "SnapshotIntegrity";

    // Tamper alert broadcast
    public static final String ACTION_TAMPER_ALERT = "com.dearmoon.shield.SNAPSHOT_TAMPER_ALERT";

    // -------------------------------------------------------------------------

    public static class IntegrityResult {
        public boolean chainValid      = true;
        public boolean allBackupsExist = true;
        public boolean tamperDetected  = false;
        public int     checkedEntries  = 0;
        public int     missingBackups  = 0;
        public int     brokenChainLinks = 0;
        public String  details         = "";
    }

    // -------------------------------------------------------------------------

    // Integrity audit check
    public IntegrityResult check(Context context,
                                  SnapshotDatabase database,
                                  BackupEncryptionManager encMgr) {
        IntegrityResult result = new IntegrityResult();
        StringBuilder details = new StringBuilder();

        try {
            // Traverse chain by ID
            // Ordered entry traversal
            List<FileMetadata> allFiles = database.getAllBackedUpFiles();
            result.checkedEntries = allFiles.size();

            String previousChainHash = "GENESIS";

            for (FileMetadata meta : allFiles) {

                // Verify backup existence
                if (meta.isBackedUp && meta.backupPath != null) {
                    String realPath = meta.backupPath;
                    // Decrypt path gracefully
                    if (encMgr != null) {
                        try {
                            realPath = encMgr.decryptColumn(meta.backupPath);
                        } catch (Exception ignored) {
                            // Legacy plaintext path
                        }
                    }
                    if (realPath != null && !new File(realPath).exists()) {
                        result.missingBackups++;
                        result.allBackupsExist = false;
                        details.append("Missing backup: ").append(meta.filePath).append("\n");
                    }
                }

                // Validate hash chain
                if (meta.chainHash != null && !meta.chainHash.isEmpty()) {
                    String metaHash     = computeMetadataHash(meta);
                    String expectedChain = computeChainHash(previousChainHash, metaHash);

                    if (!expectedChain.equals(meta.chainHash)) {
                        result.brokenChainLinks++;
                        result.chainValid = false;
                        details.append("Chain break at: ").append(meta.filePath).append("\n");
                    }

                    previousChainHash = meta.chainHash; // Update chain pointer
                }
            }

            if (!result.chainValid || !result.allBackupsExist) {
                result.tamperDetected = true;
                result.details = details.toString();
                Log.e(TAG, "TAMPER DETECTED  >>>  " + result.details);
                broadcastTamperAlert(context, result);
            } else {
                Log.i(TAG, "Integrity OK – verified " + result.checkedEntries + " entries");
            }

        } catch (Exception e) {
            Log.e(TAG, "Integrity check exception", e);
            result.tamperDetected = true;
            result.details = "Exception during integrity check: " + e.getMessage();
            broadcastTamperAlert(context, result);
        }

        return result;
    }

    // =========================================================================
    //  Hash computation (package-private so SnapshotManager can reuse them)
    // =========================================================================

    // Compute metadata hash
    static String computeMetadataHash(FileMetadata meta) {
        try {
            String input = meta.filePath + "|" + meta.fileSize + "|"
                    + meta.lastModified + "|" + meta.sha256Hash + "|" + meta.snapshotId;
            return sha256Hex(input.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "computeMetadataHash failed", e);
            return "ERROR";
        }
    }

    // Compute chain hash
    static String computeChainHash(String previousChainHash, String metadataHash) {
        try {
            String input = previousChainHash + "|" + metadataHash;
            return sha256Hex(input.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "computeChainHash failed", e);
            return "ERROR";
        }
    }

    // -------------------------------------------------------------------------

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void broadcastTamperAlert(Context context, IntegrityResult result) {
        Intent intent = new Intent(ACTION_TAMPER_ALERT);
        intent.putExtra("missing_backups",    result.missingBackups);
        intent.putExtra("broken_chain_links", result.brokenChainLinks);
        intent.putExtra("details",            result.details);
        context.sendBroadcast(intent);
        Log.e(TAG, "SNAPSHOT_TAMPER_ALERT broadcast sent");
    }
}
