package com.dearmoon.shield.data;

import android.content.Context;
import android.util.Log;
import java.io.File;

public class TelemetryStorage {
    private static final String TAG = "TelemetryStorage";
    private final EventDatabase database;
    private final Context context;

    public TelemetryStorage(Context context) {
        this.context = context;
        this.database = EventDatabase.getInstance(context);
        Log.i(TAG, "TelemetryStorage initialized with SQLite backend");
    }

    public synchronized void store(TelemetryEvent event) {
        try {
            Log.d(TAG, "EVENT GENERATED: " + event.getEventType());
            
            if (event instanceof FileSystemEvent) {
                long id = database.insertFileSystemEvent((FileSystemEvent) event);
                Log.d(TAG, "EVENT STORED: FILE_SYSTEM (ID: " + id + ")");
            } else if (event instanceof HoneyfileEvent) {
                long id = database.insertHoneyfileEvent((HoneyfileEvent) event);
                Log.d(TAG, "EVENT STORED: HONEYFILE_ACCESS (ID: " + id + ")");
            } else if (event instanceof NetworkEvent) {
                long id = database.insertNetworkEvent((NetworkEvent) event);
                Log.d(TAG, "EVENT STORED: NETWORK (ID: " + id + ")");
            } else if (event instanceof com.dearmoon.shield.lockerguard.LockerShieldEvent) {
                long id = database.insertLockerShieldEvent((com.dearmoon.shield.lockerguard.LockerShieldEvent) event);
                Log.d(TAG, "EVENT STORED: LOCKER_SHIELD (ID: " + id + ")");
            } else {
                Log.w(TAG, "Unknown event type: " + event.getClass().getSimpleName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to store event: " + event.getEventType(), e);
        }
    }

    public EventDatabase getDatabase() {
        return database;
    }

    @Deprecated
    public File getLogFile() {
        // Legacy method for compatibility
        return new File(context.getFilesDir(), "modeb_telemetry.json");
    }
}
