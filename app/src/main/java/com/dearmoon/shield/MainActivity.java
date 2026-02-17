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

        // Show user guide on first launch
        if (UserGuideActivity.isFirstTime(this)) {
            startActivity(new android.content.Intent(this, UserGuideActivity.class));
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

        snapshotManager = new com.dearmoon.shield.snapshot.SnapshotManager(this);

        btnModeA.setOnClickListener(
                v -> Toast.makeText(this, "Root Mode: Requires rooted device", Toast.LENGTH_SHORT).show());
        btnModeB.setOnClickListener(v -> toggleProtection());

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

        // Update Stats
        if (snapshotManager != null) {
            int total = snapshotManager.getTotalFileCount();
            int infected = snapshotManager.getInfectedFileCount();
            int safe = total - infected;

            tvTotalFiles.setText(String.valueOf(total));
            tvInfectedFiles.setText(String.valueOf(infected));
            tvSafeFiles.setText(String.valueOf(safe));
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
