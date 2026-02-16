package com.dearmoon.shield.snapshot;

public class FileMetadata {
    public long id;
    public String filePath;
    public long fileSize;
    public long lastModified;
    public String sha256Hash;
    public long snapshotId;
    public String backupPath;
    public boolean isBackedUp;
    public long modifiedDuringAttack;

    public FileMetadata(String filePath, long fileSize, long lastModified, String sha256Hash, long snapshotId) {
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.lastModified = lastModified;
        this.sha256Hash = sha256Hash;
        this.snapshotId = snapshotId;
        this.isBackedUp = false;
        this.modifiedDuringAttack = 0;
    }
}
