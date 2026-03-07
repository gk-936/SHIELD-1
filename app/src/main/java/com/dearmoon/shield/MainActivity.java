package com.dearmoon.shield;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.dearmoon.shield.services.NetworkGuardService;
import com.dearmoon.shield.services.ShieldProtectionService;
import com.dearmoon.shield.ui.GlitchTextView;

import androidx.biometric.BiometricPrompt;
import java.util.concurrent.Executor;
import android.graphics.RectF;
import android.view.MotionEvent;
import com.dearmoon.shield.ui.FluidMenuView;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int VPN_REQUEST_CODE = 200;

    private GlitchTextView tvProtectionStatus;
    private android.widget.TextView tvSafeFiles, tvInfectedFiles, tvTotalFiles, tvInfectionTimer;
    private android.view.View timerContainer;
    private Button btnModeA;
    private Button btnModeB;
    private com.dearmoon.shield.ui.SecurityRippleView securityRipple;
    private HighRiskAlertReceiver alertReceiver;
    private android.content.BroadcastReceiver dataUpdateReceiver;
    private com.dearmoon.shield.snapshot.SnapshotManager snapshotManager;
    private ShieldStats shieldStats;

    private android.view.View statsCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Immersive Edge-to-Edge display
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat windowInsetsController =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            // Because the theme is Abyssal Navy, force light icons for visibility
            windowInsetsController.setAppearanceLightStatusBars(false); 
            windowInsetsController.setAppearanceLightNavigationBars(false);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }

        setContentView(R.layout.activity_main);
        
        // Prevent content overlap via insets while letting background stretch behind bars
        android.view.View root = findViewById(R.id.mainRoot);
        if (root != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
                androidx.core.graphics.Insets insets = windowInsets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars());
                v.setPadding(0, insets.top, 0, insets.bottom);
                return windowInsets;
            });
        }

        initializeViews();


    }

    private void initializeViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus);
        tvSafeFiles = findViewById(R.id.tvSafeFiles);
        tvInfectedFiles = findViewById(R.id.tvInfectedFiles);
        tvTotalFiles = findViewById(R.id.tvTotalFiles);
        tvInfectionTimer = findViewById(R.id.tvInfectionTimer);
        timerContainer = findViewById(R.id.timerContainer);

        btnModeA = findViewById(R.id.btnModeA);
        btnModeB = findViewById(R.id.btnModeB);
        statsCard = findViewById(R.id.statsCard);
        securityRipple = findViewById(R.id.securityRipple);

        snapshotManager = new com.dearmoon.shield.snapshot.SnapshotManager(this);
        shieldStats = new ShieldStats(this);

        btnModeA.setOnClickListener(v -> {
            triggerButtonRipple(btnModeA, true);
            // Root mode: show confirmation orb screen then proceed to info
            android.content.Intent rootIntent = new android.content.Intent(this, ModeConfirmActivity.class);
            rootIntent.putExtra("mode", "ROOT");
            startActivity(rootIntent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
        btnModeB.setOnClickListener(v -> {
            triggerButtonRipple(btnModeB, false);

            boolean isRunning = isServiceRunning(ShieldProtectionService.class);
            if (isRunning) {
                // Turning OFF protection → require fingerprint, NO animation
                authenticateBiometric(() -> toggleProtection());
            } else {
                // Turning ON protection → show immersive orb confirmation screen
                android.content.Intent stdIntent = new android.content.Intent(this, ModeConfirmActivity.class);
                stdIntent.putExtra("mode", "STANDARD");
                startActivity(stdIntent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            }
        });


        // Start idle heartbeat once buttons are fully laid out
        btnModeB.post(() -> {
            if (securityRipple != null) {
                android.util.Pair<Float, Float> centre = securityRipple.centreOf(btnModeB);
                securityRipple.startIdleHeartbeat(centre.first, centre.second, false);
            }
        });

        updateStatusDisplay();

        // ── Fluid bottom menu ──────────────────────────────────────────────────
        FluidMenuView fluidMenu = findViewById(R.id.fluidMenu);
        if (fluidMenu != null) {
            fluidMenu.setup(this, Arrays.asList(
                new FluidMenuView.MenuItem(
                    "files", "Files Accessed", R.drawable.ic_file, false,
                    () -> {
                        startActivity(new Intent(MainActivity.this, FileAccessActivity.class));
                        fluidMenu.collapseMenu();
                        return null;
                    }),
                new FluidMenuView.MenuItem(
                    "timeline", "Incident Report", R.drawable.ic_timeline, true,
                    () -> {
                        openIncidentReport();
                        fluidMenu.collapseMenu();
                        return null;
                    }),
                new FluidMenuView.MenuItem(
                    "snapshot", "Snapshot", R.drawable.ic_snapshot, false,
                    () -> {
                        startActivity(new Intent(MainActivity.this,
                            com.dearmoon.shield.snapshot.RecoveryActivity.class));
                        fluidMenu.collapseMenu();
                        return null;
                    }),
                new FluidMenuView.MenuItem(
                    "logs", "Event Logs", R.drawable.ic_scroll, false,
                    () -> {
                        startActivity(new Intent(MainActivity.this, LogViewerActivity.class));
                        fluidMenu.collapseMenu();
                        return null;
                    }),
                new FluidMenuView.MenuItem(
                    "settings", "Settings", R.drawable.ic_settings, false,
                    () -> {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        fluidMenu.collapseMenu();
                        return null;
                    })
            ));

            // Close menu on outside touch
            findViewById(android.R.id.content).setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN && fluidMenu.isExpanded()) {
                    fluidMenu.collapseMenu();
                }
                return false;
            });
        }

        alertReceiver = new HighRiskAlertReceiver();
        android.content.IntentFilter filter = new android.content.IntentFilter("com.dearmoon.shield.HIGH_RISK_ALERT");
        filter.addAction("com.dearmoon.shield.RESTORE_COMPLETE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alertReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(alertReceiver, filter);
        }

        dataUpdateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateStatusDisplay();
            }
        };
        android.content.IntentFilter dataFilter = new android.content.IntentFilter("com.dearmoon.shield.DATA_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataUpdateReceiver, dataFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dataUpdateReceiver, dataFilter);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
    }

    /**
     * Triggers the full Security Ripple animation on button tap:
     *  Phase 1 (0–60ms)   — press-in: scale 1.0 → 0.92  [Linear]
     *  Phase 2 (60–210ms) — ping release: scale 0.92 → 1.08 [AnticipateOvershoot t=3]
     *  Phase 3 (210–330ms)— settle: scale 1.08 → 1.0  [Decelerate]
     *  Then fires SecurityRippleView burst.
     */
    private void triggerButtonRipple(android.view.View btn, boolean isRoot) {
        if (securityRipple == null) return;

        // Phase 1: press-in compression
        android.animation.ObjectAnimator pressIn = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            btn,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.0f, 0.92f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.0f, 0.92f)
        );
        pressIn.setDuration(60);
        pressIn.setInterpolator(new android.view.animation.LinearInterpolator());

        // Phase 2: overshoot ping
        android.animation.ObjectAnimator pingRelease = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            btn,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.92f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.92f, 1.08f)
        );
        pingRelease.setDuration(150);
        pingRelease.setInterpolator(new android.view.animation.AnticipateOvershootInterpolator(3.0f));

        // Phase 3: settle
        android.animation.ObjectAnimator settle = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            btn,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.08f, 1.0f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.08f, 1.0f)
        );
        settle.setDuration(120);
        settle.setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f));

        android.animation.AnimatorSet bounce = new android.animation.AnimatorSet();
        bounce.playSequentially(pressIn, pingRelease, settle);
        bounce.start();

        // Fire the ripple burst (centred on button)
        android.util.Pair<Float, Float> centre = securityRipple.centreOf(btn);
        securityRipple.triggerRipple(centre.first, centre.second, isRoot);
    }

    /** Launches IncidentActivity with the most recent attack window parameters from SharedPreferences. */
    private void openIncidentReport() {
        android.content.SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        startActivity(new Intent(this, IncidentActivity.class)
                .putExtra("attackWindowStart",  prefs.getLong("last_attack_start", 0L))
                .putExtra("attackWindowEnd",    prefs.getLong("last_attack_end",   0L))
                .putExtra("compositeScore",     prefs.getInt ("last_composite_score", 0))
                .putExtra("entropyScore",       prefs.getInt ("last_entropy_score",  0))
                .putExtra("kldScore",           prefs.getInt ("last_kld_score",      0))
                .putExtra("sprtAcceptedH1",     prefs.getBoolean("last_sprt_h1",    false))
                .putExtra("restoredFileCount",  prefs.getInt ("last_restored_count", 0)));
        Log.d(TAG, "openIncidentReport() launched");
    }

    private void authenticateBiometric(Runnable onSuccess) {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(getApplicationContext(), "Authentication error: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                onSuccess.run();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(getApplicationContext(), "Authentication failed", Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Authentication Required")
                .setSubtitle("Please authenticate to toggle root setting")
                .setNegativeButtonText("Cancel")
                .build();
        biometricPrompt.authenticate(promptInfo);
    }



    private void toggleProtection() {
        boolean isServiceRunning = isServiceRunning(ShieldProtectionService.class);
        if (isServiceRunning) {
            stopShieldService();
        } else {
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
            } else {
                startShieldService();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startShieldService();
            } else {
                Toast.makeText(this, "VPN permission required for network monitoring", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startShieldService() {
        // Clear intentionally stopped flag
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("intentionally_stopped", false).apply();

        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show();
            requestNecessaryPermissions();
            return;
        }

        Intent serviceIntent = new Intent(this, ShieldProtectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Protection Active (File + Network)", Toast.LENGTH_SHORT).show();
        updateStatusDisplay();
    }

    private void stopShieldService() {
        stopService(new Intent(this, ShieldProtectionService.class));
        Toast.makeText(this, "Protection Disabled", Toast.LENGTH_SHORT).show();
        updateStatusDisplay();
    }

    private void updateStatusDisplay() {
        boolean isServiceRunning = isServiceRunning(ShieldProtectionService.class);
        boolean isVpnRunning = isServiceRunning(NetworkGuardService.class);

        // Update Stats — use persistent ShieldStats counters so dashboard never shows zeros
        if (shieldStats != null) {
            tvTotalFiles.setText(String.valueOf(shieldStats.getFilesScanned()));
            tvInfectedFiles.setText(String.valueOf(shieldStats.getThreatsFound()));
            tvSafeFiles.setText(String.valueOf(shieldStats.getAttacksBlocked()));
        }

        if (isServiceRunning) {
            tvProtectionStatus.stopGlitchEffect();
            tvProtectionStatus.setText("System Protected" + (isVpnRunning ? " + Network Guard" : ""));
            tvProtectionStatus.setTextColor(0xFFD2DBEB);
            tvProtectionStatus.startScanBeam(() -> tvProtectionStatus.startCursorBlink());

            btnModeB.setBackgroundResource(R.drawable.bg_glass_button_active);
            btnModeB.setText("Active");
            btnModeB.setTextColor(0xFFFFFFFF);
        } else {
            tvProtectionStatus.stopCursorBlink();
            tvProtectionStatus.setText("Protection Inactive");
            tvProtectionStatus.setTextColor(0xFF6A90B4);
            tvProtectionStatus.startGlitchEffect();

            btnModeB.setBackgroundResource(R.drawable.bg_glass_button_inactive);
            btnModeB.setText("Non-Root");
            btnModeB.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null)
            return false;
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager())
                return false;
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                return false;
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNecessaryPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusDisplay();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (alertReceiver != null) {
            try {
                unregisterReceiver(alertReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering alert receiver", e);
            }
        }
        if (dataUpdateReceiver != null) {
            try {
                unregisterReceiver(dataUpdateReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering data receiver", e);
            }
        }
    }

    private class HighRiskAlertReceiver extends android.content.BroadcastReceiver {
        private android.os.CountDownTimer countDownTimer;

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.dearmoon.shield.HIGH_RISK_ALERT".equals(action)) {
                String filePath = intent.getStringExtra("file_path");
                int score = intent.getIntExtra("confidence_score", 0);
                int infectionTime = intent.getIntExtra("infection_time", -1);

                // Persist the detection event to stats counters
                if (shieldStats != null) shieldStats.recordAttackDetected();

                // Store attack window in SharedPreferences so IncidentActivity can read them.
                // Window start is approximated as 30 seconds before the alert arrives.
                android.content.SharedPreferences prefs =
                    MainActivity.this.getSharedPreferences("shield_prefs", MODE_PRIVATE);
                long attackStart = System.currentTimeMillis() - 30000L;
                long attackEnd   = System.currentTimeMillis();
                prefs.edit()
                    .putLong   ("last_attack_start",     attackStart)
                    .putLong   ("last_attack_end",       attackEnd)
                    .putInt    ("last_composite_score",  score)
                    .putInt    ("last_entropy_score",    intent.getIntExtra("entropy_score", 0))
                    .putInt    ("last_kld_score",        intent.getIntExtra("kld_score",     0))
                    .putBoolean("last_sprt_h1",          intent.getBooleanExtra("sprt_h1",   false))
                    .apply();
                Log.d(TAG, "Attack window stored in prefs: start=" + attackStart + " end=" + attackEnd);

                runOnUiThread(() -> {
                    // Show persistent timer in UI
                    if (infectionTime > 0) {
                        timerContainer.setVisibility(android.view.View.VISIBLE);
                        startCountdown(infectionTime);
                    }

                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("⚠️ RANSOMWARE DETECTED")
                            .setMessage("High-risk activity detected!\n\n" +
                                    "File: " + (filePath != null ? new java.io.File(filePath).getName() : "Unknown")
                                    + "\n" +
                                    "Confidence: " + score + "/100\n\n" +
                                    "Malicious process has been terminated.\n" +
                                    "Automated data recovery initiated.")
                            .setPositiveButton("View Logs",
                                    (dialog, which) -> startActivity(
                                            new Intent(MainActivity.this, LogViewerActivity.class)))
                            .setNegativeButton("Dismiss", null)
                            .setCancelable(false)
                            .show();
                });
            } else if ("com.dearmoon.shield.RESTORE_COMPLETE".equals(action)) {
                int restoredCount = intent.getIntExtra("restored_count", 0);
                // Store restored count so IncidentActivity can report accurate recovery stats
                MainActivity.this.getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putInt("last_restored_count", restoredCount).apply();

                runOnUiThread(() -> {
                    // Hide timer
                    timerContainer.setVisibility(android.view.View.GONE);
                    if (countDownTimer != null) countDownTimer.cancel();

                    Toast.makeText(MainActivity.this, "Data recovery complete: " + restoredCount + " files restored", Toast.LENGTH_LONG).show();
                    updateStatusDisplay();
                });
            }
        }

        private void startCountdown(int seconds) {
            if (countDownTimer != null) countDownTimer.cancel();
            countDownTimer = new android.os.CountDownTimer(seconds * 1000L, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    long secs = millisUntilFinished / 1000;
                    tvInfectionTimer.setText("Time to total infection: " + secs + "s");
                }

                @Override
                public void onFinish() {
                    tvInfectionTimer.setText("Infection time elapsed");
                }
            }.start();
        }
    }
}
