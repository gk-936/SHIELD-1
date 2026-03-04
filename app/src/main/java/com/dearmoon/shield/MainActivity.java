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
import com.dearmoon.shield.ui.GuideSpotlightView;
import com.dearmoon.shield.ui.WaterFillView;
import androidx.biometric.BiometricPrompt;
import java.util.concurrent.Executor;
import android.graphics.RectF;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int VPN_REQUEST_CODE = 200;

    private GlitchTextView tvProtectionStatus;
    private android.widget.TextView tvSafeFiles, tvInfectedFiles, tvTotalFiles, tvInfectionTimer;
    private android.view.View timerContainer;
    private Button btnModeA;
    private Button btnModeB;
    private HighRiskAlertReceiver alertReceiver;
    private android.content.BroadcastReceiver dataUpdateReceiver;
    private com.dearmoon.shield.snapshot.SnapshotManager snapshotManager;
    private ShieldStats shieldStats;

    // Guide UI references
    private GuideSpotlightView guideSpotlightView;
    private WaterFillView guideWaterFill;
    private android.view.View guideOverlayContainer;
    private android.widget.TextView guideTextMsg;
    private android.widget.TextView guideStepTitle;
    private android.widget.TextView tvIslandStep;
    private com.dearmoon.shield.ui.GlitchTextView tvLetsSecure;
    private android.widget.FrameLayout letsSecureContainer;
    private com.dearmoon.shield.ui.BinaryConvergeView binaryConvergeView;
    private Button btnGuideNext;
    private android.widget.FrameLayout dynamicIslandContainer;
    private androidx.cardview.widget.CardView guideTooltipCard;
    private android.view.View guideFinaleOverlay;
    private int currentGuideStep = 0;
    private static final int TOTAL_STEPS = 7;
    private android.view.View statsCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        initializeViews();

        // Show user guide AFTER views are initialized
        if (UserGuideActivity.isFirstTime(this)) {
            getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE).edit().putBoolean("first_time_user", false).apply();
            // Post to next frame so layout is measured before guide starts
            getWindow().getDecorView().post(this::startInteractiveGuide);
        } else if (getIntent().getBooleanExtra("START_GUIDE", false)) {
            getWindow().getDecorView().post(this::startInteractiveGuide);
        }
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

        guideSpotlightView = findViewById(R.id.guideSpotlightView);
        guideOverlayContainer = findViewById(R.id.guideOverlayContainer);
        dynamicIslandContainer = findViewById(R.id.dynamicIslandContainer);
        guideWaterFill = findViewById(R.id.guideWaterFill);
        guideTooltipCard = findViewById(R.id.guideTooltipCard);
        guideStepTitle = findViewById(R.id.guideStepTitle);
        guideTextMsg = findViewById(R.id.guideTextMsg);
        tvIslandStep = findViewById(R.id.tvIslandStep);
        letsSecureContainer = null; // removed — replaced by BinaryConvergeView
        tvLetsSecure = findViewById(R.id.tvLetsSecure);
        binaryConvergeView = findViewById(R.id.binaryConvergeView);
        btnGuideNext = findViewById(R.id.btnGuideNext);
        guideFinaleOverlay = findViewById(R.id.guideFinaleOverlay);

        snapshotManager = new com.dearmoon.shield.snapshot.SnapshotManager(this);
        shieldStats = new ShieldStats(this);

        btnModeA.setOnClickListener(
                v -> authenticateBiometric(() -> startActivity(new Intent(this, RootModeInfoActivity.class))));
        btnModeB.setOnClickListener(v -> authenticateBiometric(() -> toggleProtection()));

        Button btnLiveDemo = findViewById(R.id.btnLiveDemo);
        btnLiveDemo.setOnClickListener(v -> startActivity(new Intent(this, DemoActivity.class)));

        findViewById(R.id.btnNavLocker)
                .setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnNavLogs).setOnClickListener(v -> startActivity(new Intent(this, LogViewerActivity.class)));
        findViewById(R.id.btnNavSettings)
                .setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnNavFile)
                .setOnClickListener(v -> startActivity(new Intent(this, FileAccessActivity.class)));
        findViewById(R.id.btnNavSnapshot).setOnClickListener(
                v -> startActivity(new Intent(this, com.dearmoon.shield.snapshot.RecoveryActivity.class)));

        updateStatusDisplay();

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
        if (intent.getBooleanExtra("START_GUIDE", false)) {
            getWindow().getDecorView().post(this::startInteractiveGuide);
        }
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

    private void startInteractiveGuide() {
        if (guideSpotlightView == null || btnGuideNext == null) return;
        if (guideOverlayContainer != null) guideOverlayContainer.setVisibility(android.view.View.VISIBLE);
        guideSpotlightView.setVisibility(android.view.View.VISIBLE);
        dynamicIslandContainer.setVisibility(android.view.View.VISIBLE);
        guideTooltipCard.setVisibility(android.view.View.VISIBLE);
        btnGuideNext.setVisibility(android.view.View.VISIBLE);
        if (binaryConvergeView != null) binaryConvergeView.stopAnimation();
        if (guideFinaleOverlay != null) guideFinaleOverlay.setVisibility(android.view.View.GONE);
        btnGuideNext.setText("Next \u2192");
        currentGuideStep = 0;
        btnGuideNext.setOnClickListener(v -> advanceGuide());
        advanceGuide();
    }

    /** Maps each step to its target View. Order: Root → NonRoot → Settings → Snapshot → FileMonitor → Logs → Locker */
    private android.view.View getTargetForStep(int step) {
        switch (step) {
            case 0: return btnModeA;
            case 1: return btnModeB;
            case 2: return findViewById(R.id.btnNavSettings);
            case 3: return findViewById(R.id.btnNavSnapshot);
            case 4: return findViewById(R.id.btnNavFile);
            case 5: return findViewById(R.id.btnNavLogs);
            case 6: return findViewById(R.id.btnNavLocker);
            default: return null;
        }
    }

    private void advanceGuide() {
        // Finale — all 7 steps done
        if (currentGuideStep >= TOTAL_STEPS) {
            guideSpotlightView.clearTarget();
            guideSpotlightView.setVisibility(android.view.View.GONE);
            dynamicIslandContainer.setVisibility(android.view.View.GONE);
            guideTooltipCard.setVisibility(android.view.View.GONE);
            btnGuideNext.setVisibility(android.view.View.GONE);
            if (guideFinaleOverlay != null) guideFinaleOverlay.setVisibility(android.view.View.VISIBLE);

            // Data-Stream Compilation: 130 binary particles converge → "SYSTEM ARMED"
            if (binaryConvergeView != null) {
                binaryConvergeView.startAnimation(() -> {
                    // Particle cleanup done inside BinaryConvergeView; close the guide overlay
                    if (guideFinaleOverlay != null) guideFinaleOverlay.setVisibility(android.view.View.GONE);
                    if (guideOverlayContainer != null) guideOverlayContainer.setVisibility(android.view.View.GONE);
                });
            } else {
                // Fallback if view not found
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (guideFinaleOverlay != null) guideFinaleOverlay.setVisibility(android.view.View.GONE);
                    if (guideOverlayContainer != null) guideOverlayContainer.setVisibility(android.view.View.GONE);
                }, 3000);
            }
            return;
        }

        // Update water fill in Dynamic Island
        guideWaterFill.setFillFraction((currentGuideStep + 1f) / TOTAL_STEPS);

        // Update pill step label
        tvIslandStep.setText((currentGuideStep + 1) + " / " + TOTAL_STEPS);

        // Highlight the target with a pulsing neon ring
        android.view.View target = getTargetForStep(currentGuideStep);
        if (target != null) highlightView(target);

        // Set tooltip content
        switch (currentGuideStep) {
            case 0:
                guideStepTitle.setText("Root Mode");
                guideTextMsg.setText("Tap this button to activate advanced kernel-level protection. Requires a rooted device.");
                break;
            case 1:
                guideStepTitle.setText("Non-Root Mode");
                guideTextMsg.setText("Activate filesystem + network monitoring — works on all devices without root access.");
                break;
            case 2:
                guideStepTitle.setText("Settings");
                guideTextMsg.setText("Configure detection sensitivity, manage whitelist, and access the test suite.");
                break;
            case 3:
                guideStepTitle.setText("Snapshot Recovery");
                guideTextMsg.setText("Capture a clean snapshot of your files and restore them after a ransomware attack.");
                break;
            case 4:
                guideStepTitle.setText("File Monitor");
                guideTextMsg.setText("Track every file system change and honeyfile access attempt in real time.");
                break;
            case 5:
                guideStepTitle.setText("Event Logs");
                guideTextMsg.setText("View a full timeline of security events with graphs and threat filters.");
                break;
            case 6:
                guideStepTitle.setText("Locker Guard");
                guideTextMsg.setText("Accessibility service that detects and blocks screen-locking ransomware.");
                btnGuideNext.setText("Finish ✓");
                break;
        }

        currentGuideStep++;
    }

    private void highlightView(android.view.View v) {
        v.post(() -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int[] overlayLoc = new int[2];
            guideSpotlightView.getLocationOnScreen(overlayLoc);

            float padPx = 14f * getResources().getDisplayMetrics().density;
            float left   = loc[0] - overlayLoc[0] - padPx;
            float top    = loc[1] - overlayLoc[1] - padPx;
            float right  = left + v.getWidth()  + padPx * 2;
            float bottom = top  + v.getHeight() + padPx * 2;
            // Use a generous corner radius so it reads as a rounded ring, not a box
            float corner = Math.min(v.getHeight() / 2f + padPx, 40f * getResources().getDisplayMetrics().density);
            guideSpotlightView.setTargetRect(new RectF(left, top, right, bottom), corner);
        });
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
            tvProtectionStatus.setTextColor(0xFF10B981);
            tvProtectionStatus.startScanBeam(() -> tvProtectionStatus.startCursorBlink());

            btnModeB.setBackgroundResource(R.drawable.bg_glass_button_active);
            btnModeB.setText("Active");
            btnModeB.setTextColor(0xFFFFFFFF);
        } else {
            tvProtectionStatus.stopCursorBlink();
            tvProtectionStatus.setText("Protection Inactive");
            tvProtectionStatus.setTextColor(0xFF94A3B8);
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
