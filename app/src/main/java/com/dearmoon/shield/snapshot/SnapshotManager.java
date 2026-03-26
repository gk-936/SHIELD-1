package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.util.Log;

import com.dearmoon.shield.utils.PathNormalizer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

// SnapshotManager v2
// Implements 7 enhancements
public class SnapshotManager {
    // UI broadcast constants
    public static final String ACTION_ATTACK_STARTED = "com.dearmoon.shield.ATTACK_STARTED";
    public static final String ACTION_ATTACK_STOPPED = "com.dearmoon.shield.ATTACK_STOPPED";
    public static final String ACTION_SNAPSHOT_COMPLETE = "com.dearmoon.shield.SNAPSHOT_COMPLETE";

    private static final String TAG = "SnapshotManager";
    private static final String BACKUP_DIR = "secure_backups";

    // Retention limits
    private static final int  RETENTION_MAX_FILES = 100;
    private static final long RETENTION_MAX_BYTES = 200L * 1024 * 1024; // 200 MB

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private final Context                   context;
    private final SnapshotDatabase          database;
    private final ExecutorService           executor;
    private final File                      backupRoot;
    private final BackupEncryptionManager   encManager;      // Encryption features
    private final SnapshotDirectoryObserver dirObserver;     // Directory monitoring
    private long                            currentSnapshotId;
    private long                            activeAttackId = 0;

    public SnapshotManager(Context context) {
        this.context          = context;
        this.database         = SnapshotDatabase.getInstance(context);
        this.executor         = Executors.newSingleThreadExecutor();
        this.backupRoot       = new File(context.getFilesDir(), BACKUP_DIR);
        this.backupRoot.mkdirs();
        this.currentSnapshotId = System.currentTimeMillis();

        // Initialize encryption manager
        this.encManager = new BackupEncryptionManager(context);

        // Start directory observer
        this.dirObserver = new SnapshotDirectoryObserver(context, backupRoot.getAbsolutePath());
        this.dirObserver.startWatching();

        // Async integrity check
        executor.execute(() -> performIntegrityCheck());

        // Close stale windows
        checkAndCloseStaleAttackWindow();
    }

    private void checkAndCloseStaleAttackWindow() {
        long latestAttackId = database.getLatestAttackId();
        if (latestAttackId > 0) {
            if (database.isAttackWindowActive(latestAttackId)) {
                database.endAttackWindow(latestAttackId);
                Log.w(TAG, "Stale attack window found and closed on startup: " + latestAttackId);
                android.content.Intent intent = new android.content.Intent(ACTION_ATTACK_STOPPED);
                intent.putExtra("attack_id", latestAttackId);
                intent.putExtra("stale", true);
                context.sendBroadcast(intent);
            }
        }
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
            // Notify UI: Baseline snapshot complete
            android.content.Intent intent = new android.content.Intent(ACTION_SNAPSHOT_COMPLETE);
            intent.putExtra("type", "baseline");
            intent.putExtra("file_count", fileCount);
            context.sendBroadcast(intent);
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
            // Notify UI: Incremental snapshot complete
            android.content.Intent intent = new android.content.Intent(ACTION_SNAPSHOT_COMPLETE);
            intent.putExtra("type", "incremental");
            intent.putExtra("file_count", updatedCount);
            context.sendBroadcast(intent);
        });
    }

    /** Called by FileSystemCollector whenever a file change is detected. */
    public void trackFileChange(String rawPath) {
        executor.execute(() -> {
            String filePath = PathNormalizer.normalize(rawPath);
            File file = new File(filePath);
            if (!file.exists()) {
                handleFileDeletion(filePath);
                return;
            }

            FileMetadata existing = database.getFileMetadata(filePath);

            // Protect clean backups
            if (activeAttackId > 0 && existing != null && existing.isBackedUp) {
                Log.w(TAG, "Skipping re-backup for already-protected file during attack: " + filePath);
                if (existing.modifiedDuringAttack == 0) {
                    existing.modifiedDuringAttack = activeAttackId;
                    database.insertOrUpdateFile(existing);
                }
                return;
            }

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
        // Prevent overlapping attacks
        if (activeAttackId > 0 && database.isAttackWindowActive(activeAttackId)) {
            Log.w(TAG, "Attempted to start attack tracking while another attack is active: " + activeAttackId);
            return;
        }
        activeAttackId = database.startAttackWindow();
        Log.e(TAG, "Attack tracking started: " + activeAttackId);
        // Notify UI: Attack started
        android.content.Intent intent = new android.content.Intent(ACTION_ATTACK_STARTED);
        intent.putExtra("attack_id", activeAttackId);
        context.sendBroadcast(intent);
        // Quick pre-attack snapshot
        executor.execute(() -> {
            long now = System.currentTimeMillis();
            long windowMs = 10_000; // 10 seconds
            List<FileMetadata> allFiles = database.getAllBackedUpFiles();
            for (FileMetadata meta : allFiles) {
                if (now - meta.lastModified <= windowMs) {
                    File file = new File(meta.filePath);
                    if (file.exists()) {
                        Log.i(TAG, "Pre-attack snapshot: " + meta.filePath);
                        createNewFileSnapshot(file);
                    }
                }
            }
        });
    }

    public void stopAttackTracking() {
        // Safe attack stop
        if (activeAttackId > 0 && database.isAttackWindowActive(activeAttackId)) {
            database.endAttackWindow(activeAttackId);
            Log.i(TAG, "Attack tracking stopped: " + activeAttackId);
            // Notify UI: Attack stopped
            android.content.Intent intent = new android.content.Intent(ACTION_ATTACK_STOPPED);
            intent.putExtra("attack_id", activeAttackId);
            context.sendBroadcast(intent);
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

    // Integrity self-check

    // Validate chain
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
                    PathNormalizer.normalize(file.getAbsolutePath()), size, modified, hash, currentSnapshotId);

            // Persist the metadata entry first
            database.insertOrUpdateFile(metadata);

            // Actually back up the file and update metadata (sets isBackedUp, backupPath, etc.)
            backupOriginalFile(file, metadata);

            Log.d(TAG, "Snapshot created and backed up: " + file.getName());
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
            Log.e(TAG, "Failed to handle modification: " + PathNormalizer.normalize(file.getAbsolutePath()), e);
        }
    }

    // Backup original file
    private void backupOriginalFile(File file, FileMetadata metadata) {
        // Safe encryption closure
        File encBackupFile = null;
        try {
            // Key rotation generation
            byte[] wrappedKey = encManager.generateAndWrapSnapshotKey();
            SecretKey fileKey  = encManager.unwrapSnapshotKey(wrappedKey);

            // Encrypt backup file
            String encFileName = metadata.snapshotId + "_" + file.getName() + ".enc";
            encBackupFile = new File(backupRoot, encFileName);
            encManager.encryptFile(file, encBackupFile, fileKey);

            // Encrypt metadata path
            String encPath = encManager.encryptColumn(encBackupFile.getAbsolutePath());

            metadata.backupPath  = encPath;
            metadata.isBackedUp  = true;
            metadata.encryptedKey = wrappedKey;

            // Compute hash chain
            String previousChainHash = database.getLastChainHash();
            String metaHash          = SnapshotIntegrityChecker.computeMetadataHash(metadata);
            String chainHash         = SnapshotIntegrityChecker.computeChainHash(previousChainHash, metaHash);
            metadata.chainHash = chainHash;

            database.insertOrUpdateFile(metadata);

            // Skip purge during attack
            if (activeAttackId == 0) {
                enforceRetentionPolicy();
            }

            Log.i(TAG, "Encrypted backup created: " + encFileName
                    + "  chain=" + chainHash.substring(0, 12) + "â€¦");

        } catch (Exception e) {
            Log.e(TAG, "Backup failed: " + file.getAbsolutePath(), e);
            // Remove any partially-written .enc file to avoid orphaning storage
            if (encBackupFile != null && encBackupFile.exists()) {
                encBackupFile.delete();
            }
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

    // Retention policy

    // Prune old backups
    private void enforceRetentionPolicy() {
        try {
            // File-count limit
            int backedUp = database.getBackedUpFileCount();
            if (backedUp > RETENTION_MAX_FILES) {
                int excess = backedUp - RETENTION_MAX_FILES;
                List<FileMetadata> oldest = database.getOldestBackedUpFiles(excess);
                for (FileMetadata m : oldest) pruneEntry(m);
                Log.i(TAG, "Retention: pruned " + oldest.size() + " entries (file-count limit)");
            }

            // Storage size limit
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

    // Remove backup entry
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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = file.length();
        try (FileInputStream fis = new FileInputStream(file)) {
            if (size <= 500L * 1024 * 1024) {
                // Full streaming hash
                byte[] buffer = new byte[65536];
                int n;
                while ((n = fis.read(buffer)) != -1) digest.update(buffer, 0, n);
            } else {
                // Partial large-file hash
                byte[] buf = new byte[1024 * 1024];
                int n = fis.read(buf);
                if (n > 0) digest.update(buf, 0, n);
                long skipTo = size - buf.length;
                fis.skip(Math.max(0, skipTo - n));
                n = fis.read(buf);
                if (n > 0) digest.update(buf, 0, n);
                // Include file size to distinguish files of different lengths
                java.nio.ByteBuffer sizeBytes = java.nio.ByteBuffer.allocate(8).putLong(size);
                digest.update(sizeBytes.array());
            }
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
