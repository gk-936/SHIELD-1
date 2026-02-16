package com.dearmoon.shield.data;

import org.json.JSONException;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class EventDatabase extends SQLiteOpenHelper {
    private static final String TAG = "EventDatabase";
    private static final String DATABASE_NAME = "shield_events.db";
    private static final int DATABASE_VERSION = 1;

    private static EventDatabase instance;
    private final Context context;

    // Table names
    private static final String TABLE_FILE_SYSTEM = "file_system_events";
    private static final String TABLE_HONEYFILE = "honeyfile_events";
    private static final String TABLE_NETWORK = "network_events";
    private static final String TABLE_DETECTION = "detection_results";
    private static final String TABLE_LOCKER_SHIELD = "locker_shield_events";
    private static final String TABLE_CORRELATION = "correlation_results";

    // Common columns
    private static final String COL_ID = "id";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_EVENT_TYPE = "event_type";

    public static synchronized EventDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new EventDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private EventDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context;
        Log.i(TAG, "EventDatabase initialized");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i(TAG, "Creating database tables");

        // File System Events Table
        db.execSQL("CREATE TABLE " + TABLE_FILE_SYSTEM + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_EVENT_TYPE + " TEXT NOT NULL, " +
                "operation TEXT, " +
                "file_path TEXT, " +
                "file_extension TEXT, " +
                "file_size_before INTEGER, " +
                "file_size_after INTEGER)");

        // Honeyfile Events Table
        db.execSQL("CREATE TABLE " + TABLE_HONEYFILE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_EVENT_TYPE + " TEXT NOT NULL, " +
                "file_path TEXT, " +
                "access_type TEXT, " +
                "calling_uid INTEGER, " +
                "package_name TEXT)");

        // Network Events Table
        db.execSQL("CREATE TABLE " + TABLE_NETWORK + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_EVENT_TYPE + " TEXT NOT NULL, " +
                "destination_ip TEXT, " +
                "destination_port INTEGER, " +
                "protocol TEXT, " +
                "bytes_sent INTEGER, " +
                "bytes_received INTEGER, " +
                "app_uid INTEGER)");

        // Detection Results Table
        db.execSQL("CREATE TABLE " + TABLE_DETECTION + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                "file_path TEXT, " +
                "entropy REAL, " +
                "kl_divergence REAL, " +
                "sprt_state TEXT, " +
                "confidence_score INTEGER)");

        // LockerShield Events Table
        db.execSQL("CREATE TABLE " + TABLE_LOCKER_SHIELD + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_EVENT_TYPE + " TEXT NOT NULL, " +
                "package_name TEXT, " +
                "threat_type TEXT, " +
                "risk_score INTEGER, " +
                "details TEXT)");

        // Correlation Results Table (PSEUDO-KERNEL)
        db.execSQL("CREATE TABLE " + TABLE_CORRELATION + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TIMESTAMP + " INTEGER NOT NULL, " +
                COL_EVENT_TYPE + " TEXT NOT NULL, " +
                "file_path TEXT, " +
                "package_name TEXT, " +
                "uid INTEGER, " +
                "behavior_score INTEGER, " +
                "file_event_count INTEGER, " +
                "network_event_count INTEGER, " +
                "honeyfile_event_count INTEGER, " +
                "locker_event_count INTEGER, " +
                "syscall_pattern TEXT)");

        // Create indexes for performance
        db.execSQL("CREATE INDEX idx_fs_timestamp ON " + TABLE_FILE_SYSTEM + "(" + COL_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_hf_timestamp ON " + TABLE_HONEYFILE + "(" + COL_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_net_timestamp ON " + TABLE_NETWORK + "(" + COL_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_det_timestamp ON " + TABLE_DETECTION + "(" + COL_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_ls_timestamp ON " + TABLE_LOCKER_SHIELD + "(" + COL_TIMESTAMP + ")");
        db.execSQL("CREATE INDEX idx_corr_timestamp ON " + TABLE_CORRELATION + "(" + COL_TIMESTAMP + ")");

        Log.i(TAG, "Database tables created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FILE_SYSTEM);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HONEYFILE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NETWORK);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DETECTION);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_LOCKER_SHIELD);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CORRELATION);
        onCreate(db);
    }

    // Insert methods
    public synchronized long insertFileSystemEvent(FileSystemEvent event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, event.getTimestamp());
        values.put(COL_EVENT_TYPE, "FILE_SYSTEM");
        
        try {
            JSONObject json = event.toJSON();
            values.put("operation", json.optString("operation"));
            values.put("file_path", json.optString("filePath"));
            values.put("file_extension", json.optString("fileExtension"));
            values.put("file_size_before", json.optLong("fileSizeBefore"));
            values.put("file_size_after", json.optLong("fileSizeAfter"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert FileSystemEvent to JSON", e);
            return -1;
        }

        long id = db.insert(TABLE_FILE_SYSTEM, null, values);
        Log.d(TAG, "Inserted FileSystemEvent: " + id);
        return id;
    }

    public synchronized long insertHoneyfileEvent(HoneyfileEvent event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, event.getTimestamp());
        values.put(COL_EVENT_TYPE, "HONEYFILE_ACCESS");
        
        try {
            JSONObject json = event.toJSON();
            values.put("file_path", json.optString("filePath"));
            values.put("access_type", json.optString("accessType"));
            values.put("calling_uid", json.optInt("callingUid"));
            values.put("package_name", json.optString("packageName"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert HoneyfileEvent to JSON", e);
            return -1;
        }

        long id = db.insert(TABLE_HONEYFILE, null, values);
        Log.d(TAG, "Inserted HoneyfileEvent: " + id);
        return id;
    }

    public synchronized long insertNetworkEvent(NetworkEvent event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, event.getTimestamp());
        values.put(COL_EVENT_TYPE, "NETWORK");
        
        try {
            JSONObject json = event.toJSON();
            values.put("destination_ip", json.optString("destinationIp"));
            values.put("destination_port", json.optInt("destinationPort"));
            values.put("protocol", json.optString("protocol"));
            values.put("bytes_sent", json.optLong("bytesSent"));
            values.put("bytes_received", json.optLong("bytesReceived"));
            values.put("app_uid", json.optInt("appUid"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert NetworkEvent to JSON", e);
            return -1;
        }

        long id = db.insert(TABLE_NETWORK, null, values);
        Log.d(TAG, "Inserted NetworkEvent: " + id);
        return id;
    }

    public synchronized long insertDetectionResult(JSONObject result) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, result.optLong("timestamp"));
        values.put("file_path", result.optString("file_path"));
        values.put("entropy", result.optDouble("entropy"));
        values.put("kl_divergence", result.optDouble("kl_divergence"));
        values.put("sprt_state", result.optString("sprt_state"));
        values.put("confidence_score", result.optInt("confidence_score"));

        long id = db.insert(TABLE_DETECTION, null, values);
        Log.d(TAG, "Inserted DetectionResult: " + id);
        return id;
    }

    // Query methods
    public List<JSONObject> getAllEvents(String eventType, int limit) {
        List<JSONObject> events = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Log.d(TAG, "getAllEvents called with eventType=" + eventType + ", limit=" + limit);
        
        String query;
        if ("ALL".equals(eventType)) {
            query = "SELECT * FROM (" +
                    "SELECT *, 'FILE_SYSTEM' as source FROM " + TABLE_FILE_SYSTEM +
                    " UNION ALL SELECT *, 'HONEYFILE_ACCESS' as source FROM " + TABLE_HONEYFILE +
                    " UNION ALL SELECT *, 'NETWORK' as source FROM " + TABLE_NETWORK +
                    ") ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + limit;
        } else if ("FILE_SYSTEM".equals(eventType)) {
            query = "SELECT * FROM " + TABLE_FILE_SYSTEM + " ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + limit;
        } else if ("HONEYFILE_ACCESS".equals(eventType)) {
            query = "SELECT * FROM " + TABLE_HONEYFILE + " ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + limit;
        } else if ("NETWORK".equals(eventType)) {
            query = "SELECT * FROM " + TABLE_NETWORK + " ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + limit;
        } else {
            Log.w(TAG, "Unknown event type: " + eventType);
            return events;
        }

        Cursor cursor = db.rawQuery(query, null);
        Log.d(TAG, "Query returned " + cursor.getCount() + " rows");
        events = cursorToJsonList(cursor, eventType);
        cursor.close();
        
        Log.i(TAG, "getAllEvents returning " + events.size() + " events for type: " + eventType);
        return events;
    }

    public List<JSONObject> getAllDetectionResults(int limit) {
        List<JSONObject> results = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Cursor cursor = db.query(TABLE_DETECTION, null, null, null, null, null, 
                COL_TIMESTAMP + " DESC", String.valueOf(limit));
        
        while (cursor.moveToNext()) {
            try {
                JSONObject json = new JSONObject();
                json.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
                json.put("file_path", cursor.getString(cursor.getColumnIndexOrThrow("file_path")));
                json.put("entropy", cursor.getDouble(cursor.getColumnIndexOrThrow("entropy")));
                json.put("kl_divergence", cursor.getDouble(cursor.getColumnIndexOrThrow("kl_divergence")));
                json.put("sprt_state", cursor.getString(cursor.getColumnIndexOrThrow("sprt_state")));
                json.put("confidence_score", cursor.getInt(cursor.getColumnIndexOrThrow("confidence_score")));
                results.add(json);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing detection result", e);
            }
        }
        cursor.close();
        
        return results;
    }

    private List<JSONObject> cursorToJsonList(Cursor cursor, String eventType) {
        List<JSONObject> list = new ArrayList<>();
        
        while (cursor.moveToNext()) {
            try {
                JSONObject json = new JSONObject();
                json.put("timestamp", cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
                
                // For ALL queries, determine actual event type from the row
                String actualEventType = cursor.getString(cursor.getColumnIndexOrThrow(COL_EVENT_TYPE));
                json.put("eventType", actualEventType);
                
                // Parse columns based on ACTUAL event type, not query filter
                if ("FILE_SYSTEM".equals(actualEventType)) {
                    json.put("operation", cursor.getString(cursor.getColumnIndexOrThrow("operation")));
                    json.put("filePath", cursor.getString(cursor.getColumnIndexOrThrow("file_path")));
                    json.put("fileExtension", cursor.getString(cursor.getColumnIndexOrThrow("file_extension")));
                    json.put("fileSizeAfter", cursor.getLong(cursor.getColumnIndexOrThrow("file_size_after")));
                } else if ("HONEYFILE_ACCESS".equals(actualEventType)) {
                    json.put("filePath", cursor.getString(cursor.getColumnIndexOrThrow("file_path")));
                    json.put("accessType", cursor.getString(cursor.getColumnIndexOrThrow("access_type")));
                    json.put("callingUid", cursor.getInt(cursor.getColumnIndexOrThrow("calling_uid")));
                    json.put("packageName", cursor.getString(cursor.getColumnIndexOrThrow("package_name")));
                } else if ("NETWORK".equals(actualEventType)) {
                    json.put("destinationIp", cursor.getString(cursor.getColumnIndexOrThrow("destination_ip")));
                    json.put("destinationPort", cursor.getInt(cursor.getColumnIndexOrThrow("destination_port")));
                    json.put("protocol", cursor.getString(cursor.getColumnIndexOrThrow("protocol")));
                    json.put("bytesSent", cursor.getLong(cursor.getColumnIndexOrThrow("bytes_sent")));
                    json.put("bytesReceived", cursor.getLong(cursor.getColumnIndexOrThrow("bytes_received")));
                    json.put("appUid", cursor.getInt(cursor.getColumnIndexOrThrow("app_uid")));
                }
                
                list.add(json);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing cursor row: " + e.getMessage(), e);
            }
        }
        
        return list;
    }

    public synchronized long insertLockerShieldEvent(com.dearmoon.shield.lockerguard.LockerShieldEvent event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, event.getTimestamp());
        values.put(COL_EVENT_TYPE, "LOCKER_SHIELD");
        
        try {
            JSONObject json = event.toJSON();
            values.put("package_name", json.optString("packageName"));
            values.put("threat_type", json.optString("threatType"));
            values.put("risk_score", json.optInt("riskScore"));
            values.put("details", json.optString("details"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert LockerShieldEvent to JSON", e);
            return -1;
        }

        long id = db.insert(TABLE_LOCKER_SHIELD, null, values);
        Log.d(TAG, "Inserted LockerShieldEvent: " + id);
        return id;
    }

    public synchronized long insertCorrelationResult(JSONObject result) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TIMESTAMP, result.optLong("timestamp"));
        values.put(COL_EVENT_TYPE, "BEHAVIOR_CORRELATION");
        values.put("file_path", result.optString("filePath"));
        values.put("package_name", result.optString("packageName"));
        values.put("uid", result.optInt("uid"));
        values.put("behavior_score", result.optInt("behaviorScore"));
        values.put("file_event_count", result.optInt("fileEventCount"));
        values.put("network_event_count", result.optInt("networkEventCount"));
        values.put("honeyfile_event_count", result.optInt("honeyfileEventCount"));
        values.put("locker_event_count", result.optInt("lockerEventCount"));
        values.put("syscall_pattern", result.optString("syscallPattern"));

        long id = db.insert(TABLE_CORRELATION, null, values);
        Log.d(TAG, "Inserted CorrelationResult: " + id);
        return id;
    }

    public synchronized void clearAllEvents() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_FILE_SYSTEM, null, null);
        db.delete(TABLE_HONEYFILE, null, null);
        db.delete(TABLE_NETWORK, null, null);
        db.delete(TABLE_DETECTION, null, null);
        db.delete(TABLE_LOCKER_SHIELD, null, null);
        db.delete(TABLE_CORRELATION, null, null);
        Log.i(TAG, "All events cleared from database");
    }

    public int getEventCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT " +
                "(SELECT COUNT(*) FROM " + TABLE_FILE_SYSTEM + ") + " +
                "(SELECT COUNT(*) FROM " + TABLE_HONEYFILE + ") + " +
                "(SELECT COUNT(*) FROM " + TABLE_NETWORK + ") + " +
                "(SELECT COUNT(*) FROM " + TABLE_DETECTION + ") + " +
                "(SELECT COUNT(*) FROM " + TABLE_LOCKER_SHIELD + ") + " +
                "(SELECT COUNT(*) FROM " + TABLE_CORRELATION + ") as total", null);
        
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }
}
