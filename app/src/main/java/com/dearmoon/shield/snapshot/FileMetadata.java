package com.dearmoon.shield.snapshot;

public class FileMetadata {
    public long   id;
    public String filePath;
    public long   fileSize;
    public long   lastModified;
    public String sha256Hash;
    public long   snapshotId;
    public String backupPath;           // stored encrypted in DB (AES-GCM + Keystore)
    public boolean isBackedUp;
    public long   modifiedDuringAttack;

    // ── Security enhancements ────────────────────────────────────────────────

    /**
     * Tamper-evident hash chain link (Feature 1).
     * chainHash = SHA-256(previousChainHash | metadataHash)
     * Stored in the DB; recomputed on integrity check to detect structural manipulation.
     */
    public String chainHash;

    /**
     * Per-snapshot AES-256 key, wrapped with the master Keystore key (Feature 6).
     * Stored as a BLOB in the DB.  The backup file ciphertext can only be decrypted
     * when this value is unwrapped via BackupEncryptionManager.
     */
    public byte[] encryptedKey;

    // ─────────────────────────────────────────────────────────────────────────

    public FileMetadata(String filePath, long fileSize, long lastModified,
                        String sha256Hash, long snapshotId) {
        this.filePath            = filePath;
        this.fileSize            = fileSize;
        this.lastModified        = lastModified;
        this.sha256Hash          = sha256Hash;
        this.snapshotId          = snapshotId;
        this.isBackedUp          = false;
        this.modifiedDuringAttack = 0;
        this.chainHash           = null;
        this.encryptedKey        = null;
    }
}
