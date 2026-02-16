package com.dearmoon.shield.collectors;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
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
    private final UnifiedDetectionEngine detectionEngine;
    private final java.util.Map<String, Long> lastEventMap = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEBOUNCE_DELAY_MS = 2000;

    public MediaStoreCollector(Context context, TelemetryStorage storage, UnifiedDetectionEngine detectionEngine) {
        super(new Handler(Looper.getMainLooper()));
        this.context = context;
        this.storage = storage;
        this.detectionEngine = detectionEngine;
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
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.SIZE
                };

                Cursor cursor = context.getContentResolver().query(
                        uri, projection, null, null, null);

                if (cursor != null && cursor.moveToFirst()) {
                    int pathIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                    int sizeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);

                    if (pathIndex >= 0 && sizeIndex >= 0) {
                        filePath = cursor.getString(pathIndex);
                        long size = cursor.getLong(sizeIndex);
                        cursor.close();

                        File file = new File(filePath);
                        boolean fileExists = file.exists();
                        String operation;
                        long actualSize = fileExists ? file.length() : size;
                        
                        if (!fileExists || filePath.contains("/.trashed-") || filePath.contains("\\.trashed-")) {
                            operation = "DELETED";
                        } else if (filePath.endsWith(".zip") || filePath.endsWith(".rar") || 
                                   filePath.endsWith(".7z") || filePath.endsWith(".tar") ||
                                   filePath.endsWith(".gz") || filePath.endsWith(".bz2")) {
                            operation = "COMPRESSED";
                        } else {
                            operation = "MODIFY";
                        }

                        Log.d(TAG, "File check: " + filePath + " exists=" + fileExists + " op=" + operation);

                        String key = filePath + "|" + operation;
                        long now = System.currentTimeMillis();
                        Long lastTime = lastEventMap.get(key);

                        if (lastTime == null || (now - lastTime > DEBOUNCE_DELAY_MS)) {
                            lastEventMap.put(key, now);
                            FileSystemEvent event = new FileSystemEvent(filePath, operation, actualSize, actualSize);
                            storage.store(event);
                            Log.i(TAG, "Logged: " + operation + " - " + filePath + " (" + actualSize + " bytes)");
                            
                            if (operation.equals("MODIFY") && detectionEngine != null) {
                                detectionEngine.processFileEvent(event);
                                Log.i(TAG, "Sent to detection engine: " + filePath);
                            }
                        }
                    } else {
                        cursor.close();
                    }
                } else {
                    if (cursor != null) cursor.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing change", e);
            }
        }).start();
    }
}
