package com.dearmoon.shield.lockerguard;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.dearmoon.shield.R;

public class EmergencyRecoveryActivity extends AppCompatActivity {
    private static final String TAG = "EmergencyRecovery";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---------------------------------------------------------------
        // Make this activity visible above the lock screen and ransomware
        // overlays. Two APIs are needed: the window flags path (pre-27)
        // and the Activity methods path (API 27+). Both are set so the
        // activity works across the full supported API range (24+).
        //
        // FLAG_SHOW_WHEN_LOCKED  — renders the window above the keyguard
        // FLAG_TURN_SCREEN_ON    — wakes the screen if it was off
        // FLAG_DISMISS_KEYGUARD  — dismisses a non-secure keyguard
        // ---------------------------------------------------------------
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        setContentView(R.layout.activity_emergency_recovery);

        // Force status bar to black
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }

        // Dismiss the keyguard programmatically on API 26+ so the user
        // can interact with this activity without having to swipe first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissError() {
                        Log.w(TAG, "Keyguard dismiss error — secure keyguard active");
                    }
                    @Override public void onDismissSucceeded() {
                        Log.i(TAG, "Keyguard dismissed");
                    }
                    @Override public void onDismissCancelled() {
                        Log.w(TAG, "Keyguard dismiss cancelled by user");
                    }
                });
            }
        }

        String suspiciousPackage = getIntent().getStringExtra("SUSPICIOUS_PACKAGE");
        int riskScore = getIntent().getIntExtra("RISK_SCORE", 0);

        TextView tvWarning = findViewById(R.id.tvWarning);
        TextView tvPackageName = findViewById(R.id.tvPackageName);
        TextView tvRiskScore = findViewById(R.id.tvRiskScore);
        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnDismiss = findViewById(R.id.btnDismiss);

        tvWarning.setText("⚠ Locker Ransomware Blocked");
        tvPackageName.setText("Suspicious App: " + suspiciousPackage);
        tvRiskScore.setText("Risk Score: " + riskScore + "/100");

        // Primary action: open App Info directly on the suspect package so
        // the user can tap Force Stop and then Uninstall without navigating
        // through the full Settings tree.
        btnOpenSettings.setText("FORCE STOP / UNINSTALL");
        btnDismiss.setOnClickListener(v -> finish());

        // CRITICAL FIX: Since some modern ransomware overlays (TYPE_APPLICATION_OVERLAY)
        // are drawn with Z-orders higher than any Activity, this activity might be buried.
        // We deploy our own counter-overlay immediately to ensure the user gets an escape hatch
        // that floats above the attacker's overlay.
        com.dearmoon.shield.ui.KillGuidanceOverlay.getInstance(this)
            .show(suspiciousPackage, "Suspicious App");

        // AUTOMATION: Automatically launch the hidden Settings window right now so that 
        // the LockerShieldService Accessibility auto-clicker can find it behind the overlay.
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + suspiciousPackage));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to auto-launch Settings app", e);
        }

        Log.i(TAG, "EmergencyRecoveryActivity shown above lockscreen for: " + suspiciousPackage);
    }
}
