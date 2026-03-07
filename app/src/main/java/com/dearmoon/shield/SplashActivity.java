package com.dearmoon.shield;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.dearmoon.shield.data.PrivacyConsentManager;
import com.dearmoon.shield.ui.AnimatedLetterTextView;

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
        // ── Edge-to-Edge Immersive Status Bar ───────────────────────────────
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        // Dark navy background → force white (dark=false) status bar icons
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                new androidx.core.view.WindowInsetsControllerCompat(
                        getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
        // ─────────────────────────────────────────────────────────────────────

        setContentView(R.layout.activity_splash);

        // ── Letter-drop animations ──────────────────────────────────────────
        // Both logo labels animate simultaneously.
        // underlineStartOverride = 780ms (= 6 letters × 80ms + 300ms) so that
        // both underlines sweep at exactly the same moment regardless of word length.
        AnimatedLetterTextView shieldLabel =
                findViewById(R.id.animatedShieldLabel);
        AnimatedLetterTextView dsciLabel =
                findViewById(R.id.animatedDsciLabel);

        shieldLabel.post(() -> {
            shieldLabel.startAnimation(780L);
            dsciLabel.startAnimation(780L);
        });
        // ───────────────────────────────────────────────────────────────────

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
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_privacy_consent);
        dialog.setCancelable(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            // Apply horizontal margins to the transparent dialog window itself
            android.view.WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels - (int)(48 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setAttributes(params);
        }

        android.widget.Button btnAgree = dialog.findViewById(R.id.btnAgree);
        android.widget.Button btnDecline = dialog.findViewById(R.id.btnDecline);

        btnAgree.setOnClickListener(v -> {
            PrivacyConsentManager.recordConsent(SplashActivity.this, true);
            dialog.dismiss();
            proceedToMain();
        });

        btnDecline.setOnClickListener(v -> {
            PrivacyConsentManager.recordConsent(SplashActivity.this, false);
            dialog.dismiss();
            android.widget.Toast.makeText(this,
                    "SHIELD requires consent to operate. The app will now close.",
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
        });

        dialog.show();
    }

    private void proceedToMain() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, 2000);
    }
}
