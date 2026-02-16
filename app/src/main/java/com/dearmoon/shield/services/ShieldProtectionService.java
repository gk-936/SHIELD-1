package com.dearmoon.shield.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.dearmoon.shield.MainActivity;
import com.dearmoon.shield.R;
import com.dearmoon.shield.collectors.MediaStoreCollector;
import com.dearmoon.shield.collectors.FileSystemCollector;
import com.dearmoon.shield.collectors.HoneyfileCollector;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.security.SecurityUtils;
import com.dearmoon.shield.snapshot.SnapshotManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShieldProtectionService extends Service {
    private static final String TAG = "ShieldProtectionService";
    private static final String CHANNEL_ID = "shield_protection_channel";
    private static final int NOTIFICATION_ID = 1001;

    private TelemetryStorage storage;
    private UnifiedDetectionEngine detectionEngine;
    private HoneyfileCollector honeyfileCollector;
    private MediaStoreCollector mediaStoreCollector;
    private SnapshotManager snapshotManager;
    private List<FileSystemCollector> fileSystemCollectors = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShieldProtectionService created");

        // Initialize storage and detection engine
        storage = new TelemetryStorage(this);
        detectionEngine = new UnifiedDetectionEngine(this);
        snapshotManager = new SnapshotManager(this);

        // Initialize collectors (MediaStoreCollector disabled to prevent duplicates)
        initializeCollectors();

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());
    }

    private void initializeCollectors() {
        // Initialize honeyfile collector
        honeyfileCollector = new HoneyfileCollector(storage, this);
        String[] honeyfileDirs = getMonitoredDirectories();
        honeyfileCollector.createHoneyfiles(this, honeyfileDirs);

        // Create baseline snapshot
        snapshotManager.createBaselineSnapshot(honeyfileDirs);

        // Initialize file system collectors
        for (String dir : honeyfileDirs) {
            File directory = new File(dir);
            if (directory.exists() && directory.isDirectory()) {
                FileSystemCollector collector = new FileSystemCollector(dir, storage);
                collector.setDetectionEngine(detectionEngine);
                collector.setSnapshotManager(snapshotManager);
                collector.startWatching();
                fileSystemCollectors.add(collector);
                Log.i(TAG, "Started monitoring: " + dir);
            }
        }
    }

    private String[] getMonitoredDirectories() {
        List<String> dirs = new ArrayList<>();

        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage != null && externalStorage.exists()) {
            addIfExists(dirs, new File(externalStorage, "Documents"));
            addIfExists(dirs, new File(externalStorage, "Download"));
            addIfExists(dirs, new File(externalStorage, "Pictures"));
            addIfExists(dirs, new File(externalStorage, "DCIM"));
        }

        return dirs.toArray(new String[0]);
    }

    private void addIfExists(List<String> list, File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            list.add(dir.getAbsolutePath());
        }
    }

    private Notification createNotification() {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SHIELD Protection Active")
                .setContentText("Monitoring file system for ransomware activity")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SHIELD Protection",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ransomware detection and protection service");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SecurityUtils.checkSecurity(this);
        Log.i(TAG, "ShieldProtectionService started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ShieldProtectionService destroyed");

        // Stop MediaStore collector (disabled)
        // if (mediaStoreCollector != null) {
        //     mediaStoreCollector.stopWatching();
        // }

        // Stop all file system collectors
        for (FileSystemCollector collector : fileSystemCollectors) {
            collector.stopWatching();
        }
        fileSystemCollectors.clear();

        // Stop honeyfile collector and clear honeyfiles
        if (honeyfileCollector != null) {
            honeyfileCollector.stopWatching();
            honeyfileCollector.clearAllHoneyfiles();
        }

        // Shutdown detection engine
        if (detectionEngine != null) {
            detectionEngine.shutdown();
        }

        // Shutdown snapshot manager
        if (snapshotManager != null) {
            snapshotManager.shutdown();
        }

        // Trigger restart
        Intent restartIntent = new Intent("com.dearmoon.shield.RESTART_SERVICE");
        sendBroadcast(restartIntent);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
