package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.content.Intent;
import android.os.FileObserver;
import android.util.Log;

import androidx.annotation.Nullable;

// Snapshot directory observer
public class SnapshotDirectoryObserver extends FileObserver {

    private static final String TAG = "SnapshotDirObserver";

    // Backup tamper broadcast
    public static final String ACTION_BACKUP_DIR_TAMPER =
            "com.dearmoon.shield.BACKUP_DIR_TAMPER";

    // Watched modification events
    private static final int WATCHED_EVENTS =
            FileObserver.DELETE       // File deleted
            | FileObserver.MOVED_FROM // File moved/renamed
            | FileObserver.ATTRIB;    // Attributes altered

    private final Context context;
    private final String  watchedPath;

    // -------------------------------------------------------------------------

    public SnapshotDirectoryObserver(Context context, String directoryPath) {
        super(directoryPath, WATCHED_EVENTS);
        this.context     = context.getApplicationContext();
        this.watchedPath = directoryPath;
    }

    // -------------------------------------------------------------------------

    @Override
    public void onEvent(int event, @Nullable String fileName) {
        int cleanEvent = event & FileObserver.ALL_EVENTS;

        if (cleanEvent == FileObserver.DELETE
                || cleanEvent == FileObserver.MOVED_FROM
                || cleanEvent == FileObserver.ATTRIB) {

            String fullPath = watchedPath + (fileName != null ? "/" + fileName : "");

            Log.e(TAG, "BACKUP DIR TAMPER – event=" + eventName(cleanEvent)
                    + "  file=" + fullPath);

            broadcastTamper(cleanEvent, fullPath);
        }
    }

    // Internal helper methods

    private void broadcastTamper(int eventCode, String filePath) {
        Intent intent = new Intent(ACTION_BACKUP_DIR_TAMPER);
        intent.putExtra("event_code", eventCode);
        intent.putExtra("event_name", eventName(eventCode));
        intent.putExtra("file_path",  filePath);
        context.sendBroadcast(intent);
    }

    private static String eventName(int event) {
        switch (event) {
            case FileObserver.DELETE:     return "DELETE";
            case FileObserver.MOVED_FROM: return "MOVED_FROM";
            case FileObserver.ATTRIB:     return "ATTRIB";
            default:                      return "UNKNOWN(" + event + ")";
        }
    }
}
