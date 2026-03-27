package com.dearmoon.shield.collectors;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import com.dearmoon.shield.data.FileSystemEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import java.io.File;

public class MediaStoreCollector extends ContentObserver {
    private static final String TAG = "MediaStoreCollector";
    private final Context context;
    private final TelemetryStorage storage;
    private final com.dearmoon.shield.data.ShieldEventBus eventBus = com.dearmoon.shield.data.ShieldEventBus.getInstance();
    private final java.util.Map<String, Long> lastEventMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEBOUNCE_DELAY_MS = 500;

    public MediaStoreCollector(Context context, TelemetryStorage storage, UnifiedDetectionEngine engine) {
        super(new Handler(Looper.getMainLooper()));
        this.context = context;
        this.storage = storage;
    }

    public void startWatching() {
        // Clear old logs immediately
        java.io.File telemetryFile = new java.io.File(context.getFilesDir(), "modeb_telemetry.json");
        if (telemetryFile.exists()) {
            telemetryFile.delete();
            Log.i(TAG, "Cleared old telemetry on startup");
        }
        
        context.getContentResolver().registerContentObserver(
                MediaStore.Files.getContentUri("external"), true, this);
        Log.i(TAG, "Started watching MediaStore");
    }

    public void stopWatching() {
        context.getContentResolver().unregisterContentObserver(this);
        Log.i(TAG, "Stopped watching MediaStore");
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);
        if (uri == null) return;

        Log.d(TAG, "MediaStore change detected: " + uri);

        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }

            String filePath = null;
            try {
                String[] projection = {
                        // DATA is unreliable (often null) on Android 10+ due to scoped storage.
                        // Keep it for legacy devices / media providers that still expose it.
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.Files.FileColumns.SIZE
                };

                long reportedSize = -1L;
                String displayName = null;
                String relativePath = null;

                try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                        int nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                        int relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
                        int sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);

                        if (dataIdx >= 0) filePath = cursor.getString(dataIdx);
                        if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                        if (relIdx >= 0) relativePath = cursor.getString(relIdx);
                        if (sizeIdx >= 0) reportedSize = cursor.getLong(sizeIdx);
                    }
                }

                // Build a best-effort identifier for the file that remains meaningful under
                // scoped storage. Prefer a human-readable "relative/path + name" when possible,
                // otherwise fall back to the Uri string (always resolvable).
                String identifier = null;
                if (filePath != null && !filePath.isEmpty()) {
                    identifier = filePath;
                } else if (displayName != null && !displayName.isEmpty()) {
                    String rel = (relativePath != null) ? relativePath : "";
                    identifier = rel + displayName;
                } else {
                    identifier = uri.toString();
                }

                boolean fileExists = false;
                long actualSize = Math.max(0L, reportedSize);
                if (filePath != null && !filePath.isEmpty()) {
                    File file = new File(filePath);
                    fileExists = file.exists();
                    if (fileExists) actualSize = file.length();
                } else {
                    // With only a content Uri, existence checks via File() are meaningless.
                    // Treat "unknown path" entries as potentially existing.
                    fileExists = true;
                }

                String operation;
                if (!fileExists) {
                    operation = "DELETED";
                } else if (identifier.contains("/.trashed-") || identifier.contains("\\.trashed-")) {
                    operation = "DELETED";
                } else if (identifier.endsWith(".zip") || identifier.endsWith(".rar") ||
                        identifier.endsWith(".7z") || identifier.endsWith(".tar") ||
                        identifier.endsWith(".gz") || identifier.endsWith(".bz2")) {
                    operation = "COMPRESSED";
                } else {
                    operation = "MODIFY";
                }

                Log.d(TAG, "MediaStore event: id=" + identifier + " filePath=" + filePath
                        + " api=" + Build.VERSION.SDK_INT + " op=" + operation + " size=" + actualSize);

                String key = identifier + "|" + operation;
                long now = System.currentTimeMillis();
                Long lastTime = lastEventMap.get(key);

                if (lastTime == null || (now - lastTime > DEBOUNCE_DELAY_MS)) {
                    lastEventMap.put(key, now);
                    FileSystemEvent event = new FileSystemEvent(identifier, operation, actualSize, actualSize);
                    event.setFileUri(uri.toString());
                    event.setDisplayName(displayName);
                    event.setRelativePath(relativePath);
                    if (filePath != null && !filePath.isEmpty()) {
                        event.setResolvedPath(filePath);
                    } else {
                        event.setResolvedPath(null);
                    }
                    storage.store(event);
                    Log.i(TAG, "Logged: " + operation + " - " + identifier + " (" + actualSize + " bytes)");

                    if (operation.equals("MODIFY")) {
                        eventBus.publishFileSystemEvent(event);
                        Log.i(TAG, "Published to EventBus: " + identifier);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing change", e);
            }
        }).start();
    }
}
