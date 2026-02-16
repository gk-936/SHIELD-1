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
    private Button btnModeA;
    private Button btnModeB;
    private Button btnBlockingToggle;
    private Button btnClearHoneyfiles;
    private HighRiskAlertReceiver alertReceiver;

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
    }

    private void initializeViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus);
        btnModeA = findViewById(R.id.btnModeA);
        btnModeB = findViewById(R.id.btnModeB);
        btnBlockingToggle = findViewById(R.id.btnBlockingToggle);
        btnClearHoneyfiles = findViewById(R.id.btnClearHoneyfiles);

        btnModeA.setOnClickListener(v -> Toast.makeText(this, "Mode A: Standby", Toast.LENGTH_SHORT).show());
        btnModeB.setOnClickListener(v -> toggleProtection());
        
        if (btnBlockingToggle != null) {
            btnBlockingToggle.setOnClickListener(v -> toggleBlocking());
            updateBlockingButton();
        }

        findViewById(R.id.btnNavLocker).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnNavLogs).setOnClickListener(v -> startActivity(new Intent(this, LogViewerActivity.class)));
        findViewById(R.id.btnNavHome).setOnClickListener(v -> Toast.makeText(this, "Home", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btnNavFile).setOnClickListener(v -> startActivity(new Intent(this, FileAccessActivity.class)));
        findViewById(R.id.btnNavSnapshot).setOnClickListener(v -> startActivity(new Intent(this, com.dearmoon.shield.snapshot.RecoveryActivity.class)));

        Button btnTestSuite = findViewById(R.id.btnTestSuite);
        if (btnTestSuite != null) {
            btnTestSuite.setOnClickListener(v -> startActivity(new Intent(this, com.dearmoon.shield.testing.TestActivity.class)));
        }

        if (btnClearHoneyfiles != null) {
            btnClearHoneyfiles.setOnClickListener(v -> clearHoneyfiles());
        }

        updateStatusDisplay();
        
        alertReceiver = new HighRiskAlertReceiver();
        android.content.IntentFilter filter = new android.content.IntentFilter("com.dearmoon.shield.HIGH_RISK_ALERT");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alertReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(alertReceiver, filter);
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
            btnModeB.setText("Mode B");
            btnModeB.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) return false;
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
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
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusDisplay();
        updateBlockingButton();
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
    }

    private void toggleBlocking() {
        if (btnBlockingToggle == null) return;
        boolean isEnabled = getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE).getBoolean("blocking_enabled", false);
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE).edit().putBoolean("blocking_enabled", !isEnabled).apply();
        Intent intent = new Intent("com.dearmoon.shield.TOGGLE_BLOCKING");
        intent.putExtra("enabled", !isEnabled);
        sendBroadcast(intent);
        updateBlockingButton();
        Toast.makeText(this, !isEnabled ? "Network Blocking Enabled" : "Network Blocking Disabled", Toast.LENGTH_SHORT).show();
    }

    private void updateBlockingButton() {
        if (btnBlockingToggle == null) return;
        boolean isEnabled = getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE).getBoolean("blocking_enabled", false);
        if (isEnabled) {
            btnBlockingToggle.setBackgroundResource(R.drawable.bg_glass_button_active);
            btnBlockingToggle.setTextColor(0xFFFFFFFF);
            btnBlockingToggle.setText("Blocking: ON");
        } else {
            btnBlockingToggle.setBackgroundResource(R.drawable.bg_glass_button_inactive);
            btnBlockingToggle.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            btnBlockingToggle.setText("Blocking: OFF");
        }
    }

    private void clearHoneyfiles() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear Honeyfiles")
                .setMessage("This will delete all deployed honeyfiles from monitored directories. Continue?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    int deletedCount = deleteAllHoneyfiles();
                    Toast.makeText(this, "Deleted " + deletedCount + " honeyfiles", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int deleteAllHoneyfiles() {
        String[] honeyfileNames = {"IMPORTANT_BACKUP.txt", "PRIVATE_KEYS.dat", "CREDENTIALS.txt", "SECURE_VAULT.bin", "FINANCIAL_DATA.xlsx", "PASSWORDS.txt"};
        String[] directories = getMonitoredDirectories();
        int deletedCount = 0;
        for (String dir : directories) {
            java.io.File directory = new java.io.File(dir);
            if (!directory.exists()) continue;
            for (String name : honeyfileNames) {
                java.io.File honeyfile = new java.io.File(directory, name);
                if (honeyfile.exists() && honeyfile.delete()) {
                    deletedCount++;
                }
            }
        }
        return deletedCount;
    }

    private String[] getMonitoredDirectories() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        java.io.File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage != null && externalStorage.exists()) {
            addIfExists(dirs, new java.io.File(externalStorage, "Documents"));
            addIfExists(dirs, new java.io.File(externalStorage, "Download"));
            addIfExists(dirs, new java.io.File(externalStorage, "Pictures"));
            addIfExists(dirs, new java.io.File(externalStorage, "DCIM"));
        }
        return dirs.toArray(new String[0]);
    }

    private void addIfExists(java.util.List<String> list, java.io.File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            list.add(dir.getAbsolutePath());
        }
    }

    private class HighRiskAlertReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.dearmoon.shield.HIGH_RISK_ALERT".equals(intent.getAction())) {
                String filePath = intent.getStringExtra("file_path");
                int score = intent.getIntExtra("confidence_score", 0);
                runOnUiThread(() -> {
                    new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("⚠️ RANSOMWARE DETECTED")
                        .setMessage("High-risk activity detected!\n\n" +
                                "File: " + (filePath != null ? new java.io.File(filePath).getName() : "Unknown") + "\n" +
                                "Confidence: " + score + "/100\n\n" +
                                "Network has been isolated.\n" +
                                "Check logs for details.")
                        .setPositiveButton("View Logs", (dialog, which) -> startActivity(new Intent(MainActivity.this, LogViewerActivity.class)))
                        .setNegativeButton("Dismiss", null)
                        .setCancelable(false)
                        .show();
                });
            }
        }
    }
}
