package com.dearmoon.shield.snapshot;

import android.content.Context;
import android.content.Intent;
import android.os.FileObserver;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * SnapshotDirectoryObserver  (Feature 5 – Snapshot Directory Monitoring)
 *
 * Attaches a {@link FileObserver} to the {@code secure_backups/} directory.
 * Any DELETE, MOVED_FROM or ATTRIB change on a file inside that directory
 * is treated as a potential ransomware or attacker tampering event and:
 *
 *   1. Logged at ERROR level.
 *   2. Broadcast as {@link #ACTION_BACKUP_DIR_TAMPER} so
 *      ShieldProtectionService / MainActivity can raise the detection score
 *      and trigger mitigation.
 *
 * Usage:
 *   SnapshotDirectoryObserver obs = new SnapshotDirectoryObserver(context, path);
 *   obs.startWatching();   // call once when protection starts
 *   obs.stopWatching();    // call when service stops
 */
public class SnapshotDirectoryObserver extends FileObserver {

    private static final String TAG = "SnapshotDirObserver";

    /** Broadcast action emitted when the backup directory is tampered with. */
    public static final String ACTION_BACKUP_DIR_TAMPER =
            "com.dearmoon.shield.BACKUP_DIR_TAMPER";

    // Events that indicate malicious or unexpected modification of backups.
    private static final int WATCHED_EVENTS =
            FileObserver.DELETE       // file deleted
            | FileObserver.MOVED_FROM // file renamed/moved away
            | FileObserver.ATTRIB;    // permissions / timestamps altered

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

    // =========================================================================
    //  Internal helpers
    // =========================================================================

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
