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
    private String currentSuspectPackage = null;

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

    public void setCurrentSuspectPackage(String packageName) {
        this.currentSuspectPackage = packageName;
        Log.i(TAG, "Automation target set to: " + packageName);
    }

    /**
     * Public accessor for window-based locker identification.
     * Used by InterventionOrchestrator when PID/UID attribution fails.
     */
    public String resolveActiveLockerPackage() {
        return findActiveLockerPackage();
    }

    public String getLastForegroundPackage() {
        return lastForegroundPackage;
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
                
                // Automation: If we are in the Settings page for our suspect package, try to auto-kill
                if (currentSuspectPackage != null && packageName.equals("com.android.settings")) {
                    handleAutoForceStop(event);
                }
            } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                if (currentSuspectPackage != null && "com.android.settings".equals(packageName)) {
                    handleAutoForceStop(event);
                }
            }
        }
    }

    private void handleAutoForceStop(AccessibilityEvent event) {
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return;

            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;

                // 1. Look for "Force Stop" button in App Info
                java.util.List<AccessibilityNodeInfo> forceStopNodes = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/force_stop_button");
                if (forceStopNodes == null || forceStopNodes.isEmpty()) {
                    // Fallback for some OS versions
                    forceStopNodes = root.findAccessibilityNodeInfosByText("Force stop");
                }
                if (forceStopNodes == null || forceStopNodes.isEmpty()) {
                    forceStopNodes = root.findAccessibilityNodeInfosByText("Force Stop");
                }

                if (forceStopNodes != null && !forceStopNodes.isEmpty()) {
                    AccessibilityNodeInfo btn = forceStopNodes.get(0);
                    if (btn.isEnabled()) {
                        Log.w(TAG, "AUTOMATION: Clicking Force Stop button");
                        btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return; // Found and clicked, wait for the next event for OK dialog
                    }
                }

                // 2. Look for "OK" button in confirmation dialog
                java.util.List<AccessibilityNodeInfo> okNodes = root.findAccessibilityNodeInfosByViewId("android:id/button1");
                if (okNodes == null || okNodes.isEmpty()) {
                    okNodes = root.findAccessibilityNodeInfosByText("OK");
                }

                if (okNodes != null && !okNodes.isEmpty()) {
                    Log.w(TAG, "AUTOMATION: Clicking OK in confirmation dialog");
                    okNodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    currentSuspectPackage = null; // Mission accomplished
                    dismissEmergencyOverlay();
                    performNavigationEscape(); // Go home
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleAutoForceStop", e);
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
        // Reset button state immediately to prevent re-trigger on next single press
        isVolumeUpPressed = false;
        isVolumeDownPressed = false;
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
        
        Log.i(TAG, "Bypass launching EmergencyRecoveryActivity for: " + suspectPkg);
        currentSuspectPackage = suspectPkg;
        Intent era = new Intent(this, EmergencyRecoveryActivity.class);
        era.putExtra("SUSPICIOUS_PACKAGE", suspectPkg);
        era.putExtra("RISK_SCORE", 0);
        era.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(era);
    }

    private String findActiveLockerPackage() {
        try {
            java.util.List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return null;

            // Single pass: Prioritize the top-most non-whitelisted candidate.
            // Iterating windows in Z-order (top-to-bottom).
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;

                int type = w.getType();
                // Skip known safe window types
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;

                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) {
                    // Window has no accessible hierarchy (common for some non-interactive overlays).
                    // We skip it for now and let the fallback mechanism (lastForegroundPackage) 
                    // handle it if no other better candidate is found.
                    continue;
                }

                String pkg = root.getPackageName().toString();
                if (whitelistManager.isWhitelisted(pkg)
                        || pkg.equals(getPackageName())
                        || pkg.contains("systemui")
                        || pkg.startsWith("com.android.")
                        || pkg.equals("android")) {
                    continue;
                }

                Log.i(TAG, "Identified suspect package (" + 
                    (type == AccessibilityWindowInfo.TYPE_SYSTEM ? "SYSTEM_OVERLAY" : "FULLSCREEN_APP") + 
                    "): " + pkg);
                return pkg;
            }
            
            // Final fallback: if window iteration found nothing, use the last foreground app
            if (lastForegroundPackage != null && !lastForegroundPackage.isEmpty() && !whitelistManager.isWhitelisted(lastForegroundPackage)) {
                Log.i(TAG, "Window inspection found no candidate, falling back to lastForegroundPackage: " + lastForegroundPackage);
                return lastForegroundPackage;
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

            // Auto-navigate removed: previously this unconditionally launched App Info
            // after 2s, causing SHIELD to redirect to Settings even while the locker overlay
            // was still showing. The user-facing buttons now handle navigation explicitly.

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
