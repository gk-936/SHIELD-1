/**
 * SHIELD RANSOMWARE SIMULATOR — SECURITY RESEARCH ONLY
 * =====================================================
 * Package: com.dearmoon.shield.ransim
 *
 * SAFETY CONSTRAINTS:
 * - All file operations confined to sandbox directory only
 * - XOR cipher only (key 0x5A) — NOT real encryption  
 * - Locker overlay always shows password (TEST PASSWORD: 1234)
 * - STOP TEST button always accessible, no password needed
 * - Network simulation targets localhost only (127.0.0.1)
 * - Cleanup/restore runs automatically on stop or app exit
 *
 * SANDBOX PATH:
 * /sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/
 *
 * TO FORCE CLEANUP IF APP CRASHES:
 * adb shell rm -rf /sdcard/Android/data/com.dearmoon.shield.ransim/
 *
 * FILTER LOGS:
 * adb logcat -s SHIELD_RANSIM
 */
package com.dearmoon.shield.ransim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

public class OverlayService extends Service {
    private static final String TAG = "SHIELD_RANSIM";
    private static final String TEST_PASSWORD = "1234";
    private WindowManager windowManager;
    private View overlayView;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private Runnable logRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        // Foreground notification
        createNotificationChannel();
        Notification notif = new Notification.Builder(this, "ransim_overlay")
                .setContentTitle("SHIELD RanSim — Locker test active. Tap to stop.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "STOP TEST",
                        PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
                ).build())
                .build();
        startForeground(1, notif);
        // Overlay
        addOverlay();
        // Wake lock
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG+":Locker");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        // Log every 5s
        logRunnable = new Runnable() {
            @Override public void run() {
                Log.d(TAG, "SIM_LOCKER_ACTIVE");
                handler.postDelayed(this, 5000);
            }
        };
        handler.post(logRunnable);
    }

    private void addOverlay() {
        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = inflater.inflate(R.layout.overlay_locker, null);
        // Password always visible
        TextView pw = overlayView.findViewById(R.id.password);
        pw.setText(TEST_PASSWORD);
        // Unlock button
        Button unlockBtn = overlayView.findViewById(R.id.unlockButton);
        EditText input = overlayView.findViewById(R.id.passwordInput);
        unlockBtn.setOnClickListener(v -> {
            if (TEST_PASSWORD.equals(input.getText().toString())) {
                stopSelf();
            } else {
                input.setError("Wrong password");
                // input.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, android.R.anim.shake));
            }
        });
        // STOP TEST button
        Button stopBtn = overlayView.findViewById(R.id.stopTestButton);
        stopBtn.setOnClickListener(v -> stopSelf());
        // Add overlay
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        windowManager.addView(overlayView, params);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null) windowManager.removeView(overlayView);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        handler.removeCallbacks(logRunnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("ransim_overlay", "RanSim Overlay", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }
}
