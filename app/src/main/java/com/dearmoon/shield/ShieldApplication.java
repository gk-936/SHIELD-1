package com.dearmoon.shield;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dearmoon.shield.data.EventMerger;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.services.ShieldProtectionService;
import com.dearmoon.shield.snapshot.SnapshotManager;

// Process-scoped shared instances
public class ShieldApplication extends Application {

    private static final String TAG = "ShieldApplication";

    // Watchdog polling setup
    private static final long WATCHDOG_INITIAL_DELAY_MS = 30_000L;
    private static final long WATCHDOG_INTERVAL_MS      = 10_000L;

    private static ShieldApplication instance;

    private SnapshotManager        snapshotManager;
    private UnifiedDetectionEngine detectionEngine;
    private EventMerger            eventMerger;

    private final Handler  watchdogHandler  = new Handler(Looper.getMainLooper());
    private       Runnable watchdogRunnable;
    private volatile boolean watchdogEnabled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "ShieldApplication initialised");
        startWatchdog();
    }

    public static ShieldApplication get() {
        return instance;
    }

    // Shared component accessors

    // Shared SnapshotManager
    public synchronized SnapshotManager getSnapshotManager() {
        if (snapshotManager == null) {
            snapshotManager = new SnapshotManager(this);
            Log.i(TAG, "SnapshotManager created (shared instance)");
        }
        return snapshotManager;
    }

    // Shared detection engine
    public synchronized UnifiedDetectionEngine getDetectionEngine() {
        if (detectionEngine == null) {
            detectionEngine = new UnifiedDetectionEngine(this, getSnapshotManager());
            Log.i(TAG, "UnifiedDetectionEngine created (shared instance)");
        }
        return detectionEngine;
    }

    // Shared event merger
    public synchronized EventMerger getEventMerger() {
        if (eventMerger == null) {
            eventMerger = new EventMerger();
        }
        return eventMerger;
    }

    // Application lifecycle management

    // Shutdown shared components
    public synchronized void shutdownEngine() {
        if (detectionEngine != null) {
            detectionEngine.shutdown();
            detectionEngine = null;
            Log.i(TAG, "UnifiedDetectionEngine shut down");
        }
        if (snapshotManager != null) {
            snapshotManager.shutdown();
            snapshotManager = null;
            Log.i(TAG, "SnapshotManager shut down");
        }
        eventMerger = null;
    }

    // Service watchdog layer

    // Start periodic watchdog
    public void startWatchdog() {
        if (watchdogEnabled) return;
        watchdogEnabled = true;

        watchdogRunnable = new Runnable() {
            @Override public void run() {
                if (!watchdogEnabled) return;
                if (!isServiceRunning(ShieldProtectionService.class)) {
                    Log.w(TAG, "Watchdog: ShieldProtectionService not running — restarting");
                    Intent intent = new Intent(ShieldApplication.this, ShieldProtectionService.class);
                    try {
                        startService(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Watchdog: Failed to restart ShieldProtectionService", e);
                    }
                }
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };

        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INITIAL_DELAY_MS);
        Log.i(TAG, "Watchdog started (initial delay " + WATCHDOG_INITIAL_DELAY_MS / 1000 + "s, interval " + WATCHDOG_INTERVAL_MS / 1000 + "s)");
    }

    // Stop periodic watchdog
    public void stopWatchdog() {
        watchdogEnabled = false;
        watchdogHandler.removeCallbacks(watchdogRunnable);
        Log.i(TAG, "Watchdog stopped");
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (ActivityManager.RunningServiceInfo svc : am.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(svc.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
