package com.dearmoon.shield.services;

import android.os.Handler;
import android.os.Looper;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.dearmoon.shield.MainActivity;
import com.dearmoon.shield.R;
import com.dearmoon.shield.collectors.FileSystemCollector;
import com.dearmoon.shield.collectors.HoneyfileCollector;
import com.dearmoon.shield.collectors.RecursiveFileSystemCollector;
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
    private SnapshotManager snapshotManager;
    private List<FileSystemCollector> fileSystemCollectors = new ArrayList<>();
    private List<RecursiveFileSystemCollector> recursiveCollectors = new ArrayList<>();  // SECURITY FIX: Recursive monitoring
    private NetworkGuardService networkGuard;
    private boolean networkMonitoringStarted = false;
    private Handler cleanupHandler;
    private Handler snapshotHandler;
    private static final long CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long SNAPSHOT_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final int RETENTION_DAYS = 7;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShieldProtectionService created");

        // Initialize storage and detection engine
        storage = new TelemetryStorage(this);
        snapshotManager = new SnapshotManager(this);
        detectionEngine = new UnifiedDetectionEngine(this, snapshotManager);

        // Initialize collectors
        initializeCollectors();

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Start integrated network monitoring
        startNetworkMonitoring();
        
        // Schedule periodic database cleanup
        cleanupHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleCleanup();

        // Schedule periodic incremental snapshots
        snapshotHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleIncrementalSnapshot();
    }
    
    private void scheduleCleanup() {
        cleanupHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performDatabaseCleanup();
                scheduleCleanup(); // Reschedule
            }
        }, CLEANUP_INTERVAL_MS);
    }
    
    private void performDatabaseCleanup() {
        new Thread(() -> {
            try {
                com.dearmoon.shield.data.EventDatabase db = storage.getDatabase();
                int deleted = db.cleanupOldEvents(RETENTION_DAYS);
                Log.i(TAG, "Database cleanup completed: " + deleted + " events removed");
            } catch (Exception e) {
                Log.e(TAG, "Database cleanup failed", e);
            }
        }).start();
    }

    private void scheduleIncrementalSnapshot() {
        snapshotHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performIncrementalSnapshot();
                scheduleIncrementalSnapshot(); // Reschedule
            }
        }, SNAPSHOT_INTERVAL_MS);
    }

    private void performIncrementalSnapshot() {
        if (snapshotManager != null) {
            String[] dirs = getMonitoredDirectories();
            snapshotManager.performIncrementalSnapshot(dirs);
        }
    }

    private void initializeCollectors() {
        // Initialize honeyfile collector
        honeyfileCollector = new HoneyfileCollector(storage, this);
        String[] honeyfileDirs = getMonitoredDirectories();
        honeyfileCollector.createHoneyfiles(this, honeyfileDirs);

        // Create baseline snapshot
        snapshotManager.createBaselineSnapshot(honeyfileDirs);

        // SECURITY FIX: Initialize recursive file system collectors for deep monitoring
        // This prevents ransomware from encrypting files in subdirectories undetected
        for (String dir : honeyfileDirs) {
            File directory = new File(dir);
            if (directory.exists() && directory.isDirectory()) {
                // Create recursive collector for this directory tree
                RecursiveFileSystemCollector recursiveCollector = 
                    new RecursiveFileSystemCollector(dir, storage);
                recursiveCollector.setDetectionEngine(detectionEngine);
                recursiveCollector.setSnapshotManager(snapshotManager);
                recursiveCollector.startWatching();
                recursiveCollectors.add(recursiveCollector);
                
                int monitoredCount = recursiveCollector.getMonitoredDirectoryCount();
                Log.i(TAG, "Started recursive monitoring: " + dir + " (" + monitoredCount + " directories)");
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

        // Stop cleanup handler
        if (cleanupHandler != null) {
            cleanupHandler.removeCallbacksAndMessages(null);
        }

        // Stop snapshot handler
        if (snapshotHandler != null) {
            snapshotHandler.removeCallbacksAndMessages(null);
        }

        // Stop network monitoring
        stopNetworkMonitoring();

        // SECURITY FIX: Stop recursive file system collectors
        for (RecursiveFileSystemCollector collector : recursiveCollectors) {
            collector.stopWatching();
        }
        recursiveCollectors.clear();

        // Stop all file system collectors (legacy, if any)
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
    
    private void startNetworkMonitoring() {
        if (networkMonitoringStarted) {
            Log.d(TAG, "Network monitoring already started");
            return;
        }
        
        try {
            Intent vpnIntent = new Intent(this, NetworkGuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(vpnIntent);
            } else {
                startService(vpnIntent);
            }
            networkMonitoringStarted = true;
            Log.i(TAG, "Network monitoring started automatically");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start network monitoring", e);
        }
    }
    
    private void stopNetworkMonitoring() {
        if (!networkMonitoringStarted) {
            return;
        }
        
        try {
            Intent vpnIntent = new Intent(this, NetworkGuardService.class);
            vpnIntent.setAction(NetworkGuardService.ACTION_STOP);
            startService(vpnIntent);
            networkMonitoringStarted = false;
            Log.i(TAG, "Network monitoring stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop network monitoring", e);
        }
    }
}
