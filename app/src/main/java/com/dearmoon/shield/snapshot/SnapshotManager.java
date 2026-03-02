package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/**
 * SnapshotManager â€“ v2
 *
 * Integrates all 7 snapshot security enhancements:
 *
 *  1. Hash Chain Linking        â€“ every backup entry includes a chain_hash that
 *                                  links to the previous entry, forming a tamper-evident ledger.
 *  2. AES-256-GCM file encryption â€“ each backup file is encrypted before being written to disk.
 *  3. DB column protection       â€“ backup_path is stored encrypted in the database.
 *  4. Startup integrity check    â€“ chain + backup existence verified on construction.
 *  5. Backup directory monitoring â€“ SnapshotDirectoryObserver watches secure_backups/.
 *  6. Per-file key rotation      â€“ every backup uses its own AES-256 key wrapped by Keystore.
 *  7. Retention policy           â€“ keeps â‰¤RETENTION_MAX_FILES files and â‰¤RETENTION_MAX_BYTES.
 */
public class SnapshotManager {

    private static final String TAG = "SnapshotManager";
    private static final String BACKUP_DIR = "secure_backups";

    // â”€â”€ Retention limits (Feature 7) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    private static final int  RETENTION_MAX_FILES = 100;
    private static final long RETENTION_MAX_BYTES = 200L * 1024 * 1024; // 200 MB

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private final Context                   context;
    private final SnapshotDatabase          database;
    private final ExecutorService           executor;
    private final File                      backupRoot;
    private final BackupEncryptionManager   encManager;      // Features 2, 3, 6
    private final SnapshotDirectoryObserver dirObserver;     // Feature 5
    private long                            currentSnapshotId;
    private long                            activeAttackId = 0;

    public SnapshotManager(Context context) {
        this.context          = context;
        this.database         = new SnapshotDatabase(context);
        this.executor         = Executors.newSingleThreadExecutor();
        this.backupRoot       = new File(context.getFilesDir(), BACKUP_DIR);
        this.backupRoot.mkdirs();
        this.currentSnapshotId = System.currentTimeMillis();

        // Feature 2 & 6: Encryption manager (initialises Keystore keys)
        this.encManager = new BackupEncryptionManager(context);

        // Feature 5: Start watching the backup directory for tampering
        this.dirObserver = new SnapshotDirectoryObserver(context, backupRoot.getAbsolutePath());
        this.dirObserver.startWatching();

        // Feature 4: Run integrity self-check asynchronously so startup is not blocked
        executor.execute(() -> performIntegrityCheck());
    }

    // =========================================================================
    //  Public API  (signatures unchanged from v1)
    // =========================================================================

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

    /** Called by FileSystemCollector whenever a file change is detected. */
    public void trackFileChange(String filePath) {
        executor.execute(() -> {
            File file = new File(filePath);
            if (!file.exists()) {
                handleFileDeletion(filePath);
                return;
            }

            FileMetadata existing = database.getFileMetadata(filePath);
            long currentSize     = file.length();
            long currentModified = file.lastModified();

            if (existing == null) {
                createNewFileSnapshot(file);
            } else if (existing.fileSize != currentSize || existing.lastModified != currentModified) {
                handleFileModification(file, existing);
            }
        });
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

    public RestoreEngine.RestoreResult performAutomatedRestore() {
        long attackIdToRestore = activeAttackId;
        if (attackIdToRestore == 0) {
            attackIdToRestore = database.getLatestAttackId();
        }
        Log.e(TAG, "INITIATING AUTOMATED RESTORE for attack: " + attackIdToRestore);
        RestoreEngine engine = new RestoreEngine(context, encManager);
        RestoreEngine.RestoreResult result = engine.restoreFromAttack(attackIdToRestore);
        Log.i(TAG, "Automated restore complete: " + result.restoredCount + " files recovered");
        return result;
    }

    public long getActiveAttackId() { return activeAttackId; }

    public int getTotalFileCount()       { return database.getTotalFileCount(); }
    public int getInfectedFileCount()    { return database.getInfectedFileCount(); }

    public int getTotalMonitoredFileCount(String[] directories) {
        int count = 0;
        for (String dir : directories) count += countFilesRecursive(new File(dir));
        return count;
    }

    public void shutdown() {
        dirObserver.stopWatching();   // Feature 5
        executor.shutdown();
    }

    // =========================================================================
    //  Feature 4 â€“ Integrity self-check (called on startup & on demand)
    // =========================================================================

    /**
     * Re-validate the entire snapshot chain and all backup file existences.
     * Broadcasts SNAPSHOT_TAMPER_ALERT if anything is wrong.
     */
    public SnapshotIntegrityChecker.IntegrityResult performIntegrityCheck() {
        SnapshotIntegrityChecker checker = new SnapshotIntegrityChecker();
        SnapshotIntegrityChecker.IntegrityResult result =
                checker.check(context, database, encManager);
        if (result.tamperDetected) {
            Log.e(TAG, "Integrity check FAILED â€“ tampering detected! " + result.details);
        }
        return result;
    }

    // =========================================================================
    //  Internal â€“ scan & snapshot creation
    // =========================================================================

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
            long size     = file.length();
            long modified = file.lastModified();
            String hash   = calculateHash(file);

            FileMetadata metadata = new FileMetadata(
                    file.getAbsolutePath(), size, modified, hash, currentSnapshotId);

            // Feature 1 & 6 are applied in backupOriginalFile, but we still need
            // to persist the metadata entry first (without backup/chain fields yet).
            database.insertOrUpdateFile(metadata);

            Log.d(TAG, "Snapshot created: " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to snapshot file: " + file.getAbsolutePath(), e);
        }
    }

    private void handleFileModification(File file, FileMetadata existing) {
        try {
            if (!existing.isBackedUp) {
                backupOriginalFile(file, existing);   // encrypt + chain link happens here
            }

            String newHash    = calculateHash(file);
            existing.fileSize     = file.length();
            existing.lastModified = file.lastModified();
            existing.sha256Hash   = newHash;

            if (activeAttackId > 0) {
                existing.modifiedDuringAttack = activeAttackId;
            }

            database.insertOrUpdateFile(existing);
            Log.w(TAG, "File modified: " + file.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle modification: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * Back up {@code file} before it is overwritten/deleted.
     *
     * Feature 2 â€“ AES-256-GCM encryption of the backup file.
     * Feature 3 â€“ backup_path stored encrypted in the DB.
     * Feature 6 â€“ unique per-file key wrapped with Keystore master key.
     * Feature 1 â€“ chain_hash computed after encryption and stored.
     */
    private void backupOriginalFile(File file, FileMetadata metadata) {
        try {
            // â”€â”€ Feature 6: Generate and wrap a fresh per-file AES-256 key â”€â”€â”€â”€
            byte[] wrappedKey = encManager.generateAndWrapSnapshotKey();
            SecretKey fileKey  = encManager.unwrapSnapshotKey(wrappedKey);

            // â”€â”€ Feature 2: Encrypt backup file with per-file key â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            String encFileName = metadata.snapshotId + "_" + file.getName() + ".enc";
            File encBackupFile = new File(backupRoot, encFileName);
            encManager.encryptFile(file, encBackupFile, fileKey);

            // â”€â”€ Feature 3: Encrypt backup_path column before storing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            String encPath = encManager.encryptColumn(encBackupFile.getAbsolutePath());

            metadata.backupPath  = encPath;
            metadata.isBackedUp  = true;
            metadata.encryptedKey = wrappedKey;

            // â”€â”€ Feature 1: Compute and append hash chain link â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            String previousChainHash = database.getLastChainHash();
            String metaHash          = SnapshotIntegrityChecker.computeMetadataHash(metadata);
            String chainHash         = SnapshotIntegrityChecker.computeChainHash(previousChainHash, metaHash);
            metadata.chainHash = chainHash;

            database.insertOrUpdateFile(metadata);

            // â”€â”€ Feature 7: Enforce retention policy after every backup â”€â”€â”€â”€â”€â”€â”€â”€
            enforceRetentionPolicy();

            Log.i(TAG, "Encrypted backup created: " + encFileName
                    + "  chain=" + chainHash.substring(0, 12) + "â€¦");

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

    // =========================================================================
    //  Feature 7 â€“ Retention policy
    // =========================================================================

    /**
     * Prune old backups so that:
     *   â€“ at most RETENTION_MAX_FILES backup entries exist, and
     *   â€“ total backup storage stays under RETENTION_MAX_BYTES.
     *
     * The oldest entries (lowest DB row id) are removed first.
     */
    private void enforceRetentionPolicy() {
        try {
            // â”€â”€ File-count limit â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            int backedUp = database.getBackedUpFileCount();
            if (backedUp > RETENTION_MAX_FILES) {
                int excess = backedUp - RETENTION_MAX_FILES;
                List<FileMetadata> oldest = database.getOldestBackedUpFiles(excess);
                for (FileMetadata m : oldest) pruneEntry(m);
                Log.i(TAG, "Retention: pruned " + oldest.size() + " entries (file-count limit)");
            }

            // â”€â”€ Storage size limit â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            long totalSize = calculateBackupDirSize();
            while (totalSize > RETENTION_MAX_BYTES) {
                List<FileMetadata> oldest = database.getOldestBackedUpFiles(1);
                if (oldest.isEmpty()) break;
                FileMetadata m = oldest.get(0);
                pruneEntry(m);
                totalSize = calculateBackupDirSize();
            }
        } catch (Exception e) {
            Log.e(TAG, "Retention policy error", e);
        }
    }

    /** Remove one backup entry: delete the encrypted file and the DB row. */
    private void pruneEntry(FileMetadata m) {
        try {
            // Decrypt path to find the actual file
            String realPath = m.backupPath;
            if (m.backupPath != null) {
                try { realPath = encManager.decryptColumn(m.backupPath); }
                catch (Exception ignored) { /* legacy plaintext path */ }
            }
            if (realPath != null) {
                File f = new File(realPath);
                if (f.exists()) f.delete();
            }
            database.deleteFile(m.filePath);
        } catch (Exception e) {
            Log.w(TAG, "pruneEntry error: " + e.getMessage());
        }
    }

    private long calculateBackupDirSize() {
        long total = 0;
        File[] files = backupRoot.listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    // =========================================================================
    //  Utility helpers
    // =========================================================================

    private String calculateHash(File file) throws Exception {
        if (file.length() > 50 * 1024 * 1024) return "LARGE_FILE";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in  = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private int countFilesRecursive(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += countFilesRecursive(f);
            } else if (!isTempFile(f.getName())) {
                count++;
            }
        }
        return count;
    }

    private boolean isTempFile(String name) {
        String lower = name.toLowerCase();
        return lower.startsWith(".") || lower.contains(".tmp")
                || lower.contains("~") || lower.contains(".swp");
    }
}
