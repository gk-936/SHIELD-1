package com.dearmoon.shield.lockerguard;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.TextView;
import com.dearmoon.shield.R;
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.data.WhitelistManager;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LockerShieldService extends AccessibilityService {
    private static final String TAG = "LockerShieldService";
    
    private WhitelistManager whitelistManager;
    private RiskEvaluator riskEvaluator;
    private TelemetryStorage storage;
    private KeyguardManager keyguardManager;
    private WindowManager windowManager;
    private View emergencyOverlay;
    private String lastForegroundPackage = "";
    private long lastFocusTime = 0;
    private int focusRegainCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        storage = new TelemetryStorage(this);
        whitelistManager = new WhitelistManager(this);
        riskEvaluator = new RiskEvaluator();
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Log.i(TAG, "LockerShieldService started");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        
        String packageName = event.getPackageName().toString();
        
        if (whitelistManager.isWhitelisted(packageName)) return;
        
        int eventType = event.getEventType();
        long now = System.currentTimeMillis();
        
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowStateChange(packageName, event, now);
                break;
                
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                handleWindowContentChange(packageName, now);
                break;
        }
    }

    private void handleWindowStateChange(String packageName, AccessibilityEvent event, long now) {
        boolean isFullscreen = event.isFullScreen();
        boolean isLocked = keyguardManager.isKeyguardLocked();
        
        if (isLocked && !packageName.contains("systemui")) {
            int score = riskEvaluator.evaluateRisk(packageName, "LOCKSCREEN_HIJACK");
            logThreat(packageName, "LOCKSCREEN_HIJACK", score, "App active while device locked");
            
            if (riskEvaluator.isThresholdExceeded(packageName)) {
                triggerEmergencyResponse(packageName, score);
            }
        }
        
        if (isFullscreen) {
            int score = riskEvaluator.evaluateRisk(packageName, "FULLSCREEN_OVERLAY");
            
            if (score > 50) {
                logThreat(packageName, "FULLSCREEN_OVERLAY", score, "Suspicious fullscreen activity");
            }
        }
        
        if (packageName.equals(lastForegroundPackage) && now - lastFocusTime < 1000) {
            focusRegainCount++;
            
            if (focusRegainCount > 3) {
                int score = riskEvaluator.evaluateRisk(packageName, "RAPID_FOCUS_REGAIN");
                logThreat(packageName, "RAPID_FOCUS_REGAIN", score, "Rapid focus regain: " + focusRegainCount);
                
                if (riskEvaluator.isThresholdExceeded(packageName)) {
                    triggerEmergencyResponse(packageName, score);
                }
            }
        } else {
            focusRegainCount = 0;
        }
        
        lastForegroundPackage = packageName;
        lastFocusTime = now;
    }

    private void handleWindowContentChange(String packageName, long now) {
        int frequency = riskEvaluator.getEventFrequency(packageName);
        
        if (frequency > 10) {
            int score = riskEvaluator.evaluateRisk(packageName, "HIGH_FREQUENCY");
            
            if (riskEvaluator.isThresholdExceeded(packageName)) {
                logThreat(packageName, "HIGH_FREQUENCY", score, "Event frequency: " + frequency);
                triggerEmergencyResponse(packageName, score);
            }
        }
    }

    private void logThreat(String packageName, String threatType, int score, String details) {
        LockerShieldEvent event = new LockerShieldEvent(packageName, threatType, score, details);
        storage.store(event);
        Log.w(TAG, "Threat detected: " + packageName + " - " + threatType + " (score: " + score + ")");
    }

    private void triggerEmergencyResponse(String packageName, int score) {
        Log.e(TAG, "EMERGENCY: Triggering response for " + packageName + " (score: " + score + ")");

        // ---------------------------------------------------------------
        // Step 1: Navigate away from the ransomware overlay immediately.
        //
        // As an Accessibility Service, we can call performGlobalAction()
        // without any extra permissions. HOME sends the locker to the back
        // stack; BACK dismisses any dialog or overlay that handles it.
        // Neither kills the process, but both make the locker visually
        // disappear, giving the user a window to interact with SHIELD.
        // ---------------------------------------------------------------
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);

        // ---------------------------------------------------------------
        // Step 2: Draw SHIELD's own emergency overlay using
        // TYPE_ACCESSIBILITY_OVERLAY.  This window type is reserved for
        // accessibility services and has a higher z-order than the
        // ransomware's TYPE_APPLICATION_OVERLAY window, so it appears
        // on top even if the HOME action didn't dismiss the locker.  It
        // does NOT require the SYSTEM_ALERT_WINDOW permission.
        // ---------------------------------------------------------------
        showEmergencyOverlay(packageName, score);

        // ---------------------------------------------------------------
        // Step 3: killBackgroundProcesses is kept as a best-effort call.
        // It is ineffective against foreground services (which most locker
        // ransomware uses) but may catch families that run as background
        // processes.
        // ---------------------------------------------------------------
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill locker process", e);
        }

        // ---------------------------------------------------------------
        // Step 4: Also launch EmergencyRecoveryActivity. It now carries
        // FLAG_SHOW_WHEN_LOCKED so it appears above the keyguard if the
        // locker managed to activate the lock screen.
        // ---------------------------------------------------------------
        Intent intent = new Intent(this, EmergencyRecoveryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("SUSPICIOUS_PACKAGE", packageName);
        intent.putExtra("RISK_SCORE", score);
        startActivity(intent);

        riskEvaluator.reset(packageName);
    }

    /**
     * Inflate and attach an emergency alert banner using
     * {@code TYPE_ACCESSIBILITY_OVERLAY}.  This window type is only
     * available to accessibility services and sits above
     * {@code TYPE_APPLICATION_OVERLAY}, making it visible even when
     * ransomware has drawn a full-screen overlay.
     *
     * <p>The overlay provides two actions:
     * <ul>
     *   <li><b>FORCE STOP</b> — opens Android's App Info page directly on
     *       the suspect package so the user can tap Force Stop in one tap.
     *   <li><b>DISMISS</b> — removes the overlay banner.
     * </ul>
     */
    private void showEmergencyOverlay(String packageName, int score) {
        if (windowManager == null) return;
        dismissEmergencyOverlay(); // remove any previous overlay first

        try {
            emergencyOverlay = LayoutInflater.from(this)
                    .inflate(R.layout.overlay_locker_alert, null);

            TextView tvPackage = emergencyOverlay.findViewById(R.id.overlay_package);
            Button btnForceStop = emergencyOverlay.findViewById(R.id.overlay_btn_force_stop);
            Button btnDismiss = emergencyOverlay.findViewById(R.id.overlay_btn_dismiss);

            tvPackage.setText("Blocking app: " + packageName + "  (score " + score + ")");

            btnForceStop.setOnClickListener(v -> {
                Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appInfo.setData(Uri.parse("package:" + packageName));
                appInfo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appInfo);
                dismissEmergencyOverlay();
            });

            btnDismiss.setOnClickListener(v -> dismissEmergencyOverlay());

            // TYPE_ACCESSIBILITY_OVERLAY: API 22+, no SYSTEM_ALERT_WINDOW needed.
            // On API 26+ TYPE_PHONE was restricted; TYPE_ACCESSIBILITY_OVERLAY
            // remains the correct overlay type for accessibility services.
            int overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

            windowManager.addView(emergencyOverlay, params);
            Log.i(TAG, "Emergency overlay shown for: " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show emergency overlay", e);
            emergencyOverlay = null;
        }
    }

    /** Remove the emergency overlay banner if it is currently attached. */
    private void dismissEmergencyOverlay() {
        if (emergencyOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(emergencyOverlay);
            } catch (Exception ignored) { }
            emergencyOverlay = null;
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "LockerShieldService interrupted");
    }

    @Override
    public void onDestroy() {
        dismissEmergencyOverlay();
        super.onDestroy();
        Log.i(TAG, "LockerShieldService destroyed");
    }
}
