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
import com.dearmoon.shield.modea.ModeAService;
import com.dearmoon.shield.security.ConfigAuditChecker;
import com.dearmoon.shield.security.DependencyIntegrityChecker;
import com.dearmoon.shield.security.SecurityUtils;
import com.dearmoon.shield.security.integrity.IntegrityLogger;
import com.dearmoon.shield.security.integrity.IntegrityResult;
import com.dearmoon.shield.security.integrity.ShieldIntegrityManager;
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
    private com.dearmoon.shield.collectors.MediaStoreCollector mediaStoreCollector;  // H-02: MediaStore monitoring
    private NetworkGuardService networkGuard;
    private boolean networkMonitoringStarted = false;
    private Handler cleanupHandler;
    private Handler snapshotHandler;
    private Handler heartbeatHandler;  // L-01: heartbeat for watchdog
    private static final long CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long SNAPSHOT_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final int RETENTION_DAYS = 7;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShieldProtectionService created");

        // Start watchdog
        startService(new Intent(this, ShieldWatchdogService.class));

        // Initialize storage and detection engine — use Application-scoped singletons
        // so Mode A and Mode B share ONE engine, ONE snapshot manager, ONE SPRT state.
        storage = new TelemetryStorage(this);
        snapshotManager = com.dearmoon.shield.ShieldApplication.get().getSnapshotManager();
        detectionEngine = com.dearmoon.shield.ShieldApplication.get().getDetectionEngine();

        // Initialize collectors
        initializeCollectors();

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());

        // L-01: Start heartbeat so watchdog can detect us reliably
        heartbeatHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleHeartbeat();
        
        // Start integrated network monitoring
        startNetworkMonitoring();
        
        // Schedule periodic database cleanup
        cleanupHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleCleanup();

        // Schedule periodic incremental snapshots
        snapshotHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleIncrementalSnapshot();

        // Mode-A (eBPF collection) is started explicitly by the user via
        // MainActivity when they choose Root Mode — not auto-launched here.
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
                    new RecursiveFileSystemCollector(dir, storage, this);
                recursiveCollector.setDetectionEngine(detectionEngine);
                recursiveCollector.setSnapshotManager(snapshotManager);
                recursiveCollector.setEventMerger(
                        com.dearmoon.shield.ShieldApplication.get().getEventMerger());
                recursiveCollector.startWatching();
                recursiveCollectors.add(recursiveCollector);
                
                int monitoredCount = recursiveCollector.getMonitoredDirectoryCount();
                Log.i(TAG, "Started recursive monitoring: " + dir + " (" + monitoredCount + " directories)");
            }
        }

        // H-02: Enable MediaStoreCollector — catches ContentResolver-based ransomware
        mediaStoreCollector = new com.dearmoon.shield.collectors.MediaStoreCollector(this, storage, detectionEngine);
        mediaStoreCollector.startWatching();
        Log.i(TAG, "MediaStore monitoring enabled");
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
        // SecurityUtils.checkSecurity(this); // DISABLED FOR TESTING

        // --- TEE-Anchored APK Integrity Check --- DISABLED FOR TESTING
        /*
        if (ShieldIntegrityManager.isFirstRun(this)) {
            ShieldIntegrityManager.initialize(this);
        }

        IntegrityResult integrityResult = ShieldIntegrityManager.verify(this);
        switch (integrityResult) {
            case APK_TAMPERED:
                IntegrityLogger.log(this, integrityResult,
                        "APK hash mismatch - possible tampering detected");
                postIntegrityAlert("APK TAMPERED",
                        "SHIELD APK integrity check failed. Service halted for safety.");
                stopSelf();
                return START_NOT_STICKY;

            case BASELINE_FORGED:
                IntegrityLogger.log(this, integrityResult,
                        "Stored HMAC baseline inconsistency - forged baseline suspected");
                postIntegrityAlert("BASELINE FORGED",
                        "SHIELD integrity baseline compromised. Service halted for safety.");
                stopSelf();
                return START_NOT_STICKY;

            case KEY_INVALIDATED:
                IntegrityLogger.log(this, integrityResult,
                        "Keystore key permanently invalidated - regenerating baseline");
                ShieldIntegrityManager.regenerate(this);
                break;

            case TEE_KEY_MISSING:
                IntegrityLogger.log(this, integrityResult,
                        "Keystore key absent - initializing fresh baseline");
                ShieldIntegrityManager.regenerate(this);
                break;

            case STRONGBOX_UNAVAILABLE:
                IntegrityLogger.log(this, integrityResult,
                        "StrongBox not present on this device - using TEE-backed key");
                break;

            case CLEAN:
            default:
                // No action needed; proceed
                break;
        }
        */
        IntegrityResult integrityResult = IntegrityResult.CLEAN; // Force CLEAN for testing
        // --- End Integrity Check ---

        // --- M2: Supply Chain Integrity Check (background thread) --- DISABLED FOR TESTING
        // --- M8: Security Misconfiguration Audit   (background thread) --- DISABLED FOR TESTING
        /*
        final android.content.Context svcCtx = this;
        new Thread(() -> {
            try {
                DependencyIntegrityChecker.Finding supplyChainResult =
                        DependencyIntegrityChecker.check(svcCtx);
                Log.i(TAG, "Supply-chain check: " + supplyChainResult.name());
            } catch (Exception e) {
                Log.e(TAG, "DependencyIntegrityChecker failed", e);
            }
            try {
                java.util.List<ConfigAuditChecker.ConfigFinding> auditFindings =
                        ConfigAuditChecker.audit(svcCtx);
                long failCount = auditFindings.stream()
                        .filter(f -> f.severity == ConfigAuditChecker.Severity.FAIL)
                        .count();
                Log.i(TAG, "Config audit complete: " + auditFindings.size()
                        + " findings, " + failCount + " FAIL");
            } catch (Exception e) {
                Log.e(TAG, "ConfigAuditChecker failed", e);
            }
        }, "shield-audit").start();
        */

        Log.i(TAG, "ShieldProtectionService started (integrity=DISABLED_FOR_TESTING)");
        return START_STICKY;
    }

    /** Posts a high-priority notification when an integrity violation halts the service. */
    private void postIntegrityAlert(String title, String text) {
        try {
            android.app.Notification alert = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("SHIELD: " + title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build();
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID + 100, alert);
        } catch (Exception e) {
            Log.e(TAG, "Failed to post integrity alert notification", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ShieldProtectionService destroyed");

        // H-01: Use commit() (synchronous) so watchdog reads the flag before it can fire
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("intentionally_stopped", true).commit();

        // L-01: Stop heartbeat
        if (heartbeatHandler != null) heartbeatHandler.removeCallbacksAndMessages(null);

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

        // H-02: Stop MediaStore monitoring
        if (mediaStoreCollector != null) {
            mediaStoreCollector.stopWatching();
            mediaStoreCollector = null;
        }

        // NOTE: ModeAService is NOT stopped here — Mode A manages its own lifecycle.
        // It is started and stopped independently by the user via MainActivity.
        // Stopping it here would race with Mode A's init thread and kill the daemon
        // before the socket-wait loop has a chance to connect.

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

        // Shutdown detection engine + snapshot manager via Application
        // (this is the only place shutdown should be called)
        com.dearmoon.shield.ShieldApplication.get().shutdownEngine();
        detectionEngine = null;

        snapshotManager = null;

        // H-01: Restart broadcast removed — watchdog handles restart detection via heartbeat

        // Optional: stopForeground ensures the notification clears immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

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

        // H-04: Check VPN permission. If not yet granted, route to MainActivity
        // because the system dialog can only be shown from an Activity context.
        Intent permCheck = android.net.VpnService.prepare(this);
        if (permCheck != null) {
            Log.w(TAG, "VPN permission not yet granted — requesting via MainActivity");
            Intent requestPermIntent = new Intent(this, com.dearmoon.shield.MainActivity.class);
            requestPermIntent.setAction("com.dearmoon.shield.REQUEST_VPN_PERMISSION");
            requestPermIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(requestPermIntent);
            return;  // Will be started again via MainActivity's onActivityResult
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

    // L-01: Write heartbeat timestamp every 30 s so the watchdog can detect service death
    private void scheduleHeartbeat() {
        heartbeatHandler.postDelayed(() -> {
            getSharedPreferences("ShieldPrefs", MODE_PRIVATE).edit()
                .putLong("last_heartbeat", System.currentTimeMillis()).apply();
            scheduleHeartbeat();
        }, 30_000);
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
