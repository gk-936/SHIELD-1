package com.dearmoon.shield.lockerguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.Rect;
import android.widget.Button;

import com.dearmoon.shield.R;
import com.dearmoon.shield.data.WhitelistManager;

public class LockerShieldService extends AccessibilityService {
    private static final String TAG = "LockerShieldService";
    
    private static final String ACTION_SUPPRESS_OVERLAY = "com.dearmoon.shield.ACTION_SUPPRESS_OVERLAY";

    private WhitelistManager whitelistManager;
    private WindowManager windowManager;
    private View emergencyOverlay;
    private String lastForegroundPackage = "";

    private boolean isVolumeUpPressed = false;
    private boolean isVolumeDownPressed = false;

    private static LockerShieldService instance;

    private final android.content.BroadcastReceiver suppressReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SUPPRESS_OVERLAY.equals(intent.getAction())) {
                Log.i(TAG, "MANUAL TRIGGER: Persistent notification clicked");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    handleBypass();
                }, 500); 
            }
        }
    };

    public static LockerShieldService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_SUPPRESS_OVERLAY);
        registerReceiver(suppressReceiver, filter);
        
        showPersistentNotification();
    }

    private void showPersistentNotification() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                "shield_persistent", "SHIELD Active Status", android.app.NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent suppressIntent = new Intent(ACTION_SUPPRESS_OVERLAY);
        android.app.PendingIntent suppressPI = android.app.PendingIntent.getBroadcast(
            this, 0, suppressIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, "shield_persistent")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("🛡 SHIELD LockerGuard Active")
            .setContentText("Tap here if your screen is locked by ransomware.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(suppressPI); // Tapping notification body triggers it

        nm.notify(2007, builder.build());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        whitelistManager = new WhitelistManager(this);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Log.i(TAG, "LockerShieldService started");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : null;
        if (packageName != null && !packageName.equals(getPackageName())) {
            // Keep track of the last foreground application package for fallback
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastForegroundPackage = packageName;
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) isVolumeUpPressed = true;
            else if (action == KeyEvent.ACTION_UP) isVolumeUpPressed = false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) isVolumeDownPressed = true;
            else if (action == KeyEvent.ACTION_UP) isVolumeDownPressed = false;
        }

        if (isVolumeUpPressed && isVolumeDownPressed) {
            Log.w(TAG, "BYPASS TRIGGERED: Physical button combination detected (VolUp + VolDown)");
            handleBypass();
            // Consume key event
            return true;
        }

        return super.onKeyEvent(event);
    }

    private void handleBypass() {
        Log.i(TAG, "Executing manual bypass (Physical Buttons)");
        dismissEmergencyOverlay();
        
        String suspectPkg = findActiveLockerPackage();
        if (suspectPkg == null || suspectPkg.isEmpty()) {
            if (lastForegroundPackage != null && !lastForegroundPackage.isEmpty()) {
                suspectPkg = lastForegroundPackage;
                Log.i(TAG, "Fallback: using last foreground package: " + suspectPkg);
            } else {
                Log.w(TAG, "No suspect package found.");
                performNavigationEscape();
                return;
            }
        }
        
        Log.i(TAG, "Bypass triggering overlay for: " + suspectPkg);
        showRemovalGuideOverlay(suspectPkg);
    }

    private String findActiveLockerPackage() {
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return null;

            Rect screen = new Rect();
            try {
                android.view.Display d = (windowManager != null) ? windowManager.getDefaultDisplay() : null;
                if (d != null) {
                    android.graphics.Point p = new android.graphics.Point();
                    d.getRealSize(p);
                    screen.set(0, 0, p.x, p.y);
                }
            } catch (Exception e) {
                // Ignore
            }

            for (int i = 0; i < windows.size(); i++) {
                AccessibilityWindowInfo w = windows.get(i);
                if (w == null) continue;

                int type = w.getType();
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;

                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                
                String pkg = root.getPackageName().toString();
                if (whitelistManager.isWhitelisted(pkg) || pkg.equals(getPackageName()) || pkg.contains("systemui")) continue;

                return pkg;
            }
        } catch (Exception e) {
            Log.w(TAG, "Window stack inspection failed", e);
        }
        return null;
    }

    private void showRemovalGuideOverlay(String suspectPackage) {
        if (windowManager == null) return;
        
        dismissEmergencyOverlay(); 

        try {
            emergencyOverlay = LayoutInflater.from(this).inflate(R.layout.overlay_removal_guide, null);
            Button btnDismiss = emergencyOverlay.findViewById(R.id.guide_btn_dismiss);
            Button btnSettings = emergencyOverlay.findViewById(R.id.guide_btn_settings);

            btnDismiss.setOnClickListener(v -> dismissEmergencyOverlay());
            btnSettings.setOnClickListener(v -> {
                Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appInfo.setData(Uri.parse("package:" + suspectPackage));
                appInfo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appInfo);
                dismissEmergencyOverlay();
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O ?
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, 
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

            windowManager.addView(emergencyOverlay, params);
            Log.i(TAG, "Guide overlay shown for: " + suspectPackage);

            // Auto navigate
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appInfo.setData(Uri.parse("package:" + suspectPackage));
                appInfo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appInfo);
            }, 2000);

        } catch (Exception e) {
            Log.e(TAG, "Failed to show removal guide overlay", e);
            emergencyOverlay = null;
        }
    }

    private void dismissEmergencyOverlay() {
        if (emergencyOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(emergencyOverlay);
            } catch (Exception ignored) { }
            emergencyOverlay = null;
        }
    }

    public void performNavigationEscape() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                performGlobalAction(GLOBAL_ACTION_RECENTS), 300);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "LockerShieldService interrupted");
    }

    @Override
    public void onDestroy() {
        dismissEmergencyOverlay();
        try {
            unregisterReceiver(suppressReceiver);
        } catch (Exception ignored) {}
        
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(2007);
        }
        
        instance = null;
        super.onDestroy();
        Log.i(TAG, "LockerShieldService destroyed");
    }
}
