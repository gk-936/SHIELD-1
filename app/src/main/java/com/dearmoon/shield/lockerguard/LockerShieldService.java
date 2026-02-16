package com.dearmoon.shield.lockerguard;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LockerShieldService extends AccessibilityService {
    private static final String TAG = "LockerShieldService";
    
    private static final Set<String> WHITELIST = new HashSet<>(Arrays.asList(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.android.incallui",
        "com.android.deskclock",
        "com.google.android.youtube",
        "com.spotify.music",
        "com.dearmoon.shield"
    ));
    
    private RiskEvaluator riskEvaluator;
    private TelemetryStorage storage;
    private KeyguardManager keyguardManager;
    private String lastForegroundPackage = "";
    private long lastFocusTime = 0;
    private int focusRegainCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        storage = new TelemetryStorage(this);
        riskEvaluator = new RiskEvaluator();
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Log.i(TAG, "LockerShieldService started");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        
        String packageName = event.getPackageName().toString();
        
        if (WHITELIST.contains(packageName)) return;
        
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
        
        Intent intent = new Intent(this, EmergencyRecoveryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("SUSPICIOUS_PACKAGE", packageName);
        intent.putExtra("RISK_SCORE", score);
        startActivity(intent);
        
        riskEvaluator.reset(packageName);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "LockerShieldService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "LockerShieldService destroyed");
    }
}
