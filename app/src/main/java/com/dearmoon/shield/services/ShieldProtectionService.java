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
import com.dearmoon.shield.security.OverlayPermissionAuditor;
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
    public static final String ACTION_INTERVENE_CRYPTO = "com.dearmoon.shield.ACTION_INTERVENE_CRYPTO";
    private static final long HONEYFILE_WATCHDOG_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    private TelemetryStorage storage;
    private UnifiedDetectionEngine detectionEngine;
    private HoneyfileCollector honeyfileCollector;
    private SnapshotManager snapshotManager;
    private List<FileSystemCollector> fileSystemCollectors = new ArrayList<>();
    private List<RecursiveFileSystemCollector> recursiveCollectors = new ArrayList<>();  // Recursive monitoring
    private com.dearmoon.shield.collectors.MediaStoreCollector mediaStoreCollector;  // MediaStore monitoring
    private NetworkGuardService networkGuard;
    private boolean networkMonitoringStarted = false;
    private Handler cleanupHandler;
    private Handler snapshotHandler;
    private Handler heartbeatHandler;  // Watchdog heartbeat
    private Handler honeyfileWatchdogHandler;
    private static final long CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    private static final long SNAPSHOT_INTERVAL_MS = 60 * 60 * 1000; // 1 hour
    private static final int RETENTION_DAYS = 7;

    private final android.content.BroadcastReceiver interventionReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INTERVENE_CRYPTO.equals(intent.getAction())) {
                String pkg = intent.getStringExtra("package");
                Log.w(TAG, "MANUAL INTERVENTION: User requested kill for " + pkg);
                if (detectionEngine != null && pkg != null) {
                    detectionEngine.killMaliciousProcess(pkg);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShieldProtectionService created");

        // Start service watchdog
        startService(new Intent(this, ShieldWatchdogService.class));

        // Initialize detection engines
        storage = new TelemetryStorage(this);
        snapshotManager = com.dearmoon.shield.ShieldApplication.get().getSnapshotManager();
        detectionEngine = com.dearmoon.shield.ShieldApplication.get().getDetectionEngine();

        // Initialize telemetry collectors
        initializeCollectors();

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification());

        // Start watchdog heartbeat
        heartbeatHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleHeartbeat();
        
        // Start network monitoring
        startNetworkMonitoring();
        
        // Schedule database cleanup
        cleanupHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleCleanup();

        // Schedule incremental snapshots
        snapshotHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleIncrementalSnapshot();

        // Start honeyfile watchdog
        honeyfileWatchdogHandler = new Handler(android.os.Looper.getMainLooper());
        scheduleHoneyfileWatchdog();

        // Register intervention receiver
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_INTERVENE_CRYPTO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(interventionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(interventionReceiver, filter);
        }

        // Choice-based Mode-A start
    }
    
    private void scheduleCleanup() {
        cleanupHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performDatabaseCleanup();
                scheduleCleanup(); // Reschedule cleanup
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
                scheduleIncrementalSnapshot(); // Reschedule snapshots
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
        honeyfileCollector = new HoneyfileCollector(storage, this, detectionEngine);
        String[] honeyfileDirs = getMonitoredDirectories();
        honeyfileCollector.createHoneyfiles(this, honeyfileDirs);

        // Create baseline snapshot
        snapshotManager.createBaselineSnapshot(honeyfileDirs);

        // Recursive monitoring setup
        for (String dir : honeyfileDirs) {
            File directory = new File(dir);
            if (directory.exists() && directory.isDirectory()) {
                // Create recursive collector
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

        // Enable MediaStore monitoring
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

            // Monitor RanSim sandbox
            File ransimSandbox = new File(externalStorage, 
                "Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox"
            );
            if (ransimSandbox.exists()) {
                dirs.add(ransimSandbox.getAbsolutePath());
                Log.i(TAG, "Explicitly adding RanSim sandbox to monitored paths");
            }
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

                NotificationChannel alertsChannel = new NotificationChannel(
                        "shield_alerts",
                        "SHIELD Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                alertsChannel.setDescription("High-priority security alerts and recovery status");
                manager.createNotificationChannel(alertsChannel);
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

        // --- Installed-app overlay audit (locker prevention) ---
        // This is intentionally lightweight and runs in the background.
        final android.content.Context svcCtx = this;
        new Thread(() -> {
            try {
                OverlayPermissionAuditor.auditInstalledApps(svcCtx);
            } catch (Exception e) {
                Log.e(TAG, "OverlayPermissionAuditor failed", e);
            }
        }, "shield-overlay-audit").start();

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

        // Force synchronous stop
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("intentionally_stopped", true).commit();

        // Stop service heartbeat
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

        // Stop MediaStore monitoring
        if (mediaStoreCollector != null) {
            mediaStoreCollector.stopWatching();
            mediaStoreCollector = null;
        }

        // Mode A independent lifecycle

        // Stop recursive monitoring
        for (RecursiveFileSystemCollector collector : recursiveCollectors) {
            collector.stopWatching();
        }
        recursiveCollectors.clear();

        // Stop legacy collectors
        for (FileSystemCollector collector : fileSystemCollectors) {
            collector.stopWatching();
        }
        fileSystemCollectors.clear();

        // Stop honeyfile collector
        if (honeyfileCollector != null) {
            honeyfileCollector.stopWatching();
            honeyfileCollector.clearAllHoneyfiles();
        }

        // Shutdown detection engines
        com.dearmoon.shield.ShieldApplication.get().shutdownEngine();
        detectionEngine = null;

        snapshotManager = null;

        // Heartbeat-based restart detection

        // Clear foreground notification
        try {
            unregisterReceiver(interventionReceiver);
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        // Stop honeyfile watchdog
        if (honeyfileWatchdogHandler != null) {
            honeyfileWatchdogHandler.removeCallbacksAndMessages(null);
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

        // Route VPN permission
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

    // Periodic heartbeat write
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

    private void scheduleHoneyfileWatchdog() {
        honeyfileWatchdogHandler.postDelayed(() -> {
            Log.d(TAG, "Honeyfile watchdog tick");
            if (honeyfileCollector != null) {
                honeyfileCollector.verifyAndRedeploy(this, getMonitoredDirectories());
            }
            scheduleHoneyfileWatchdog();
        }, HONEYFILE_WATCHDOG_INTERVAL_MS);
    }
}
