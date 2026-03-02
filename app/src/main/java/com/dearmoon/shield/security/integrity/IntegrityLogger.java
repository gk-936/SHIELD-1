package com.dearmoon.shield.security.integrity;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.dearmoon.shield.data.EventDatabase;

/**
 * Persists TEE integrity verification events into the existing shield_events.db database via
 * the {@link EventDatabase} singleton.  The table {@code integrity_events} is added through a
 * non-destructive schema migration (EventDatabase v2 → v3).
 *
 * Schema of {@code integrity_events}:
 *   id                    INTEGER PRIMARY KEY AUTOINCREMENT
 *   timestamp             INTEGER NOT NULL          – System.currentTimeMillis()
 *   result_type           TEXT NOT NULL             – IntegrityResult.name()
 *   apk_hash_snapshot     TEXT                      – Base64 APK hash at time of check
 *   device_has_strongbox  INTEGER NOT NULL DEFAULT 0 – 1 if StrongBox present, 0 otherwise
 *   additional_info       TEXT                      – Optional free-form context string
 */
public final class IntegrityLogger {

    private static final String TAG = "SHIELD_INTEGRITY";
    public static final String TABLE_INTEGRITY = "integrity_events";

    private IntegrityLogger() {}

    /**
     * Writes one integrity event row to the database.
     *
     * @param context        Application or Service context.
     * @param result         The {@link IntegrityResult} from {@link ShieldIntegrityManager#verify}.
     * @param additionalInfo Optional diagnostic string (e.g. exception message). May be null.
     */
    public static void log(Context context, IntegrityResult result, String additionalInfo) {
        try {
            SQLiteDatabase db = EventDatabase.getInstance(context).getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("timestamp", System.currentTimeMillis());
            values.put("result_type", result.name());
            values.put("apk_hash_snapshot",
                    context.getSharedPreferences("shield_integrity", Context.MODE_PRIVATE)
                            .getString("apk_hash_b64", null));
            values.put("device_has_strongbox",
                    ShieldIntegrityManager.hasStrongBox(context) ? 1 : 0);
            values.put("additional_info", additionalInfo);
            long rowId = db.insert(TABLE_INTEGRITY, null, values);
            Log.d(TAG, "Integrity event logged: result=" + result.name() + " rowId=" + rowId);
        } catch (Exception e) {
            Log.e(TAG, "IntegrityLogger.log() failed", e);
        }
    }
}
