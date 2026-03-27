package com.dearmoon.shield.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.util.Timer;
import java.util.TimerTask;

public class ShieldWatchdogService extends Service {
    private static final String TAG = "ShieldWatchdog";
    private Timer timer;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "ShieldWatchdogService started");
        startMonitoring();
        return START_STICKY;
    }

    private void startMonitoring() {
        if (timer != null) return;
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkMainService();
            }
        }, 5000, 5000);
    }

    private void checkMainService() {
        boolean intentionallyStopped = getSharedPreferences("ShieldPrefs", Context.MODE_MULTI_PROCESS)
                .getBoolean("intentionally_stopped", false);

        if (intentionallyStopped) {
            Log.d(TAG, "Main service intentionally stopped, shutting down watchdog");
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            stopSelf();
            return;
        }

        // Check service heartbeat
        if (!isServiceAlive()) {
            Log.e(TAG, "ShieldProtectionService KILLED! Restarting...");
            Intent serviceIntent = new Intent(this, ShieldProtectionService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }
    }

    // Heartbeat-based liveness check
    private boolean isServiceAlive() {
        long last = getSharedPreferences("ShieldPrefs", Context.MODE_MULTI_PROCESS)
                .getLong("last_heartbeat", 0);
        return System.currentTimeMillis() - last < 60_000;
    }

    @Override
    public void onDestroy() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        Log.i(TAG, "ShieldWatchdogService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
