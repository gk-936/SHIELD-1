package com.dearmoon.shield;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.dearmoon.shield.data.PrivacyConsentManager;

/**
 * Entry point of SHIELD.
 *
 * <p>M6 — Privacy Controls: on the first launch (or after a policy version bump) the user is
 * shown a data-collection disclosure dialog and must explicitly accept before any telemetry
 * collectors are started.  If the user declines, the app exits gracefully without collecting
 * any data.  Consent decisions are persisted by {@link PrivacyConsentManager} and logged to
 * the privacy audit table in {@link com.dearmoon.shield.data.EventDatabase}.
 */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        // M6 — Privacy Controls: gate on explicit user consent
        if (!PrivacyConsentManager.hasConsent(this)) {
            showConsentDialog();
        } else {
            proceedToMain();
        }
    }

    // -------------------------------------------------------------------------
    // M6 consent dialog
    // -------------------------------------------------------------------------

    private void showConsentDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Data Collection Disclosure")
                .setMessage(
                        "SHIELD collects the following data to detect ransomware on your device:\n\n"
                        + "• File system events (paths, sizes, timestamps)\n"
                        + "• Network connection metadata (IP, port, protocol)\n"
                        + "• Honeyfile access events\n"
                        + "• Accessibility events from suspicious apps\n\n"
                        + "All data is stored locally on your device and is never transmitted "
                        + "to any server.  You can review or delete collected data at any time "
                        + "via Settings → Privacy → Purge Telemetry.\n\n"
                        + "Do you agree to allow SHIELD to collect this data?")
                .setCancelable(false)
                .setPositiveButton("I Agree", (dialog, which) -> {
                    PrivacyConsentManager.recordConsent(SplashActivity.this, true);
                    proceedToMain();
                })
                .setNegativeButton("Decline", (dialog, which) -> {
                    PrivacyConsentManager.recordConsent(SplashActivity.this, false);
                    android.widget.Toast.makeText(this,
                            "SHIELD requires consent to operate.  The app will now close.",
                            android.widget.Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
    }

    private void proceedToMain() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, 2000);
    }
}
