package com.dearmoon.shield.snapshot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * SnapshotDatabase  – v2 schema
 *
 * Schema additions (v1 → v2):
 *   chain_hash    TEXT   – tamper-evident hash chain link (Feature 1)
 *   encrypted_key BLOB   – AES-256 per-snapshot key wrapped with Keystore master (Feature 6)
 *
 * The backup_path column stores an AES-GCM encrypted + Base64-encoded string
 * (Feature 3 – DB column protection).  Encryption / decryption is performed by
 * BackupEncryptionManager; this class treats the value as an opaque string.
 */
public class SnapshotDatabase extends SQLiteOpenHelper {

    private static final String TAG = "SnapshotDatabase";

    private static final String DB_NAME    = "shield_snapshots.db";
    private static final int    DB_VERSION = 2;          // bumped for v2 schema

    private static final String TABLE_FILES   = "file_metadata";
    private static final String TABLE_ATTACKS = "attack_windows";

    // -------------------------------------------------------------------------

    public SnapshotDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    // =========================================================================
    //  Schema
    // =========================================================================

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FILES + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_path TEXT UNIQUE NOT NULL," +
                "file_size INTEGER," +
                "last_modified INTEGER," +
                "sha256_hash TEXT," +
                "snapshot_id INTEGER," +
                "backup_path TEXT," +           // encrypted via BackupEncryptionManager
                "is_backed_up INTEGER DEFAULT 0," +
                "modified_during_attack INTEGER DEFAULT 0," +
                "chain_hash TEXT," +            // v2: tamper-evident chain link
                "encrypted_key BLOB)");         // v2: wrapped per-snapshot AES key

        db.execSQL("CREATE TABLE " + TABLE_ATTACKS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_time INTEGER," +
                "end_time INTEGER," +
                "is_active INTEGER DEFAULT 1)");

        db.execSQL("CREATE INDEX idx_file_path   ON " + TABLE_FILES + "(file_path)");
        db.execSQL("CREATE INDEX idx_snapshot_id ON " + TABLE_FILES + "(snapshot_id)");
        db.execSQL("CREATE INDEX idx_chain_backed ON " + TABLE_FILES
                + "(is_backed_up, id)");        // fast scan for integrity checker
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Non-destructive migration: add the two new columns to existing data.
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_FILES + " ADD COLUMN chain_hash TEXT");
            } catch (Exception e) {
                Log.w(TAG, "chain_hash column may already exist: " + e.getMessage());
            }
            try {
                db.execSQL("ALTER TABLE " + TABLE_FILES + " ADD COLUMN encrypted_key BLOB");
            } catch (Exception e) {
                Log.w(TAG, "encrypted_key column may already exist: " + e.getMessage());
            }
            // Existing rows have NULL for both columns – handled gracefully by code.
            Log.i(TAG, "Migrated SnapshotDatabase from v" + oldVersion + " to v2");
        }
    }

    // =========================================================================
    //  Core CRUD
    // =========================================================================

    public synchronized void insertOrUpdateFile(FileMetadata metadata) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("file_path",             metadata.filePath);
        v.put("file_size",             metadata.fileSize);
        v.put("last_modified",         metadata.lastModified);
        v.put("sha256_hash",           metadata.sha256Hash);
        v.put("snapshot_id",           metadata.snapshotId);
        v.put("backup_path",           metadata.backupPath);
        v.put("is_backed_up",          metadata.isBackedUp ? 1 : 0);
        v.put("modified_during_attack", metadata.modifiedDuringAttack);
        v.put("chain_hash",            metadata.chainHash);     // v2
        v.put("encrypted_key",         metadata.encryptedKey);  // v2
        db.insertWithOnConflict(TABLE_FILES, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized FileMetadata getFileMetadata(String filePath) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_FILES, null, "file_path = ?",
                new String[]{filePath}, null, null, null);
        FileMetadata m = null;
        if (c.moveToFirst()) m = cursorToMetadata(c);
        c.close();
        return m;
    }

    public synchronized List<FileMetadata> getFilesModifiedDuringAttack(long attackId) {
        SQLiteDatabase db = getReadableDatabase();
        List<FileMetadata> files = new ArrayList<>();
        Cursor c = db.query(TABLE_FILES, null, "modified_during_attack = ?",
                new String[]{String.valueOf(attackId)}, null, null, null);
        while (c.moveToNext()) files.add(cursorToMetadata(c));
        c.close();
        return files;
    }

    // =========================================================================
    //  Integrity / Chain queries  (Features 1 & 4)
    // =========================================================================

    /**
     * Returns ALL entries that have a backup recorded, ordered by row id ASC.
     * Used by SnapshotIntegrityChecker to validate the full hash chain in order.
     */
    public synchronized List<FileMetadata> getAllBackedUpFiles() {
        SQLiteDatabase db = getReadableDatabase();
        List<FileMetadata> files = new ArrayList<>();
        Cursor c = db.query(TABLE_FILES, null, "is_backed_up = 1",
                null, null, null, "id ASC");
        while (c.moveToNext()) files.add(cursorToMetadata(c));
        c.close();
        return files;
    }

    /**
     * Returns the chain_hash of the most recently inserted backed-up entry,
     * or {@code "GENESIS"} when no entries exist yet.
     * Used by SnapshotManager when appending a new chain link.
     */
    public synchronized String getLastChainHash() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_FILES,
                new String[]{"chain_hash"}, "is_backed_up = 1 AND chain_hash IS NOT NULL",
                null, null, null, "id DESC", "1");
        String hash = "GENESIS";
        if (c.moveToFirst()) {
            String stored = c.getString(0);
            if (stored != null && !stored.isEmpty()) hash = stored;
        }
        c.close();
        return hash;
    }

    // =========================================================================
    //  Retention policy helpers  (Feature 7)
    // =========================================================================

    /**
     * Returns the {@code limit} oldest backed-up entries (by row id ASC).
     * Used by SnapshotManager to prune when over file-count or storage limits.
     */
    public synchronized List<FileMetadata> getOldestBackedUpFiles(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<FileMetadata> files = new ArrayList<>();
        Cursor c = db.query(TABLE_FILES, null, "is_backed_up = 1",
                null, null, null, "id ASC", String.valueOf(limit));
        while (c.moveToNext()) files.add(cursorToMetadata(c));
        c.close();
        return files;
    }

    /** Total number of backed-up entries. */
    public synchronized int getBackedUpFileCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_FILES + " WHERE is_backed_up = 1", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    // =========================================================================
    //  Attack window management
    // =========================================================================

    public synchronized long startAttackWindow() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("start_time", System.currentTimeMillis());
        v.put("is_active", 1);
        return db.insert(TABLE_ATTACKS, null, v);
    }

    public synchronized void endAttackWindow(long attackId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("end_time", System.currentTimeMillis());
        v.put("is_active", 0);
        db.update(TABLE_ATTACKS, v, "id = ?", new String[]{String.valueOf(attackId)});
    }

    public synchronized long getLatestAttackId() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_ATTACKS, new String[]{"id"}, null,
                null, null, null, "id DESC", "1");
        long id = 0;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    // =========================================================================
    //  Stats
    // =========================================================================

    public synchronized int getTotalFileCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_FILES, null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public synchronized int getInfectedFileCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_FILES + " WHERE modified_during_attack > 0", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public synchronized void deleteFile(String filePath) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FILES, "file_path = ?", new String[]{filePath});
    }

    // =========================================================================
    //  Cursor helper
    // =========================================================================

    private FileMetadata cursorToMetadata(Cursor c) {
        FileMetadata m = new FileMetadata(
                c.getString(c.getColumnIndexOrThrow("file_path")),
                c.getLong  (c.getColumnIndexOrThrow("file_size")),
                c.getLong  (c.getColumnIndexOrThrow("last_modified")),
                c.getString(c.getColumnIndexOrThrow("sha256_hash")),
                c.getLong  (c.getColumnIndexOrThrow("snapshot_id")));
        m.id                  = c.getLong  (c.getColumnIndexOrThrow("id"));
        m.backupPath          = c.getString(c.getColumnIndexOrThrow("backup_path"));
        m.isBackedUp          = c.getInt   (c.getColumnIndexOrThrow("is_backed_up")) == 1;
        m.modifiedDuringAttack = c.getLong (c.getColumnIndexOrThrow("modified_during_attack"));

        // v2 columns (may be NULL for rows migrated from v1)
        int chainCol = c.getColumnIndex("chain_hash");
        if (chainCol != -1) m.chainHash    = c.getString(chainCol);
        int keyCol   = c.getColumnIndex("encrypted_key");
        if (keyCol != -1)   m.encryptedKey = c.getBlob(keyCol);

        return m;
    }
}
