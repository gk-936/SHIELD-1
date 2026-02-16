package com.dearmoon.shield.snapshot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class SnapshotDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "shield_snapshots.db";
    private static final int DB_VERSION = 1;
    
    private static final String TABLE_FILES = "file_metadata";
    private static final String TABLE_ATTACKS = "attack_windows";

    public SnapshotDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FILES + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "file_path TEXT UNIQUE NOT NULL," +
            "file_size INTEGER," +
            "last_modified INTEGER," +
            "sha256_hash TEXT," +
            "snapshot_id INTEGER," +
            "backup_path TEXT," +
            "is_backed_up INTEGER DEFAULT 0," +
            "modified_during_attack INTEGER DEFAULT 0)");
        
        db.execSQL("CREATE TABLE " + TABLE_ATTACKS + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "start_time INTEGER," +
            "end_time INTEGER," +
            "is_active INTEGER DEFAULT 1)");
        
        db.execSQL("CREATE INDEX idx_file_path ON " + TABLE_FILES + "(file_path)");
        db.execSQL("CREATE INDEX idx_snapshot_id ON " + TABLE_FILES + "(snapshot_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FILES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ATTACKS);
        onCreate(db);
    }

    public synchronized void insertOrUpdateFile(FileMetadata metadata) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("file_path", metadata.filePath);
        values.put("file_size", metadata.fileSize);
        values.put("last_modified", metadata.lastModified);
        values.put("sha256_hash", metadata.sha256Hash);
        values.put("snapshot_id", metadata.snapshotId);
        values.put("backup_path", metadata.backupPath);
        values.put("is_backed_up", metadata.isBackedUp ? 1 : 0);
        values.put("modified_during_attack", metadata.modifiedDuringAttack);
        
        db.insertWithOnConflict(TABLE_FILES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized FileMetadata getFileMetadata(String filePath) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_FILES, null, "file_path = ?", 
            new String[]{filePath}, null, null, null);
        
        FileMetadata metadata = null;
        if (cursor.moveToFirst()) {
            metadata = new FileMetadata(
                cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_modified")),
                cursor.getString(cursor.getColumnIndexOrThrow("sha256_hash")),
                cursor.getLong(cursor.getColumnIndexOrThrow("snapshot_id"))
            );
            metadata.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            metadata.backupPath = cursor.getString(cursor.getColumnIndexOrThrow("backup_path"));
            metadata.isBackedUp = cursor.getInt(cursor.getColumnIndexOrThrow("is_backed_up")) == 1;
            metadata.modifiedDuringAttack = cursor.getLong(cursor.getColumnIndexOrThrow("modified_during_attack"));
        }
        cursor.close();
        return metadata;
    }

    public synchronized List<FileMetadata> getFilesModifiedDuringAttack(long attackId) {
        SQLiteDatabase db = getReadableDatabase();
        List<FileMetadata> files = new ArrayList<>();
        Cursor cursor = db.query(TABLE_FILES, null, "modified_during_attack = ?", 
            new String[]{String.valueOf(attackId)}, null, null, null);
        
        while (cursor.moveToNext()) {
            FileMetadata metadata = new FileMetadata(
                cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
                cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_modified")),
                cursor.getString(cursor.getColumnIndexOrThrow("sha256_hash")),
                cursor.getLong(cursor.getColumnIndexOrThrow("snapshot_id"))
            );
            metadata.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
            metadata.backupPath = cursor.getString(cursor.getColumnIndexOrThrow("backup_path"));
            metadata.isBackedUp = cursor.getInt(cursor.getColumnIndexOrThrow("is_backed_up")) == 1;
            metadata.modifiedDuringAttack = cursor.getLong(cursor.getColumnIndexOrThrow("modified_during_attack"));
            files.add(metadata);
        }
        cursor.close();
        return files;
    }

    public synchronized long startAttackWindow() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("start_time", System.currentTimeMillis());
        values.put("is_active", 1);
        return db.insert(TABLE_ATTACKS, null, values);
    }

    public synchronized void endAttackWindow(long attackId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("end_time", System.currentTimeMillis());
        values.put("is_active", 0);
        db.update(TABLE_ATTACKS, values, "id = ?", new String[]{String.valueOf(attackId)});
    }

    public synchronized void deleteFile(String filePath) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FILES, "file_path = ?", new String[]{filePath});
    }
}
