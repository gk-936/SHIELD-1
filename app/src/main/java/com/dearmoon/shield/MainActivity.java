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
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.dearmoon.shield.services.NetworkGuardService;
import com.dearmoon.shield.services.ShieldProtectionService;
import com.dearmoon.shield.ui.GlitchTextView;
import com.dearmoon.shield.ui.DataLeakTextView;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private GlitchTextView tvProtectionStatus;
    private Button btnModeA;
    private Button btnModeB;
    private Button btnVpn;
    private Button btnBlockingToggle;
    private Button btnClearHoneyfiles;
    private HighRiskAlertReceiver alertReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Force status bar to black on all devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000); // Pure black
        }

        // Make status bar icons light (white) for visibility on black background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0); // Clear light status bar flag
        }

        initializeViews();
    }

    private void initializeViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus);
        btnModeA = findViewById(R.id.btnModeA);
        btnModeB = findViewById(R.id.btnModeB);
        btnVpn = findViewById(R.id.btnVpn);
        btnBlockingToggle = findViewById(R.id.btnBlockingToggle);
        btnClearHoneyfiles = findViewById(R.id.btnClearHoneyfiles);
        
        if (btnBlockingToggle == null) {
            Log.e(TAG, "btnBlockingToggle not found in layout");
        }

        // Mode A: Empty/Inactive
        btnModeA.setOnClickListener(v -> {
            Toast.makeText(this, "Mode A: Standby", Toast.LENGTH_SHORT).show();
            // Implement any specific Mode A logic here if needed
        });

        // Mode B: Toggle Shield Service
        btnModeB.setOnClickListener(v -> toggleProtection());

        // VPN: Toggle Network Guard
        btnVpn.setOnClickListener(v -> toggleVpn());
        
        // Blocking Toggle
        if (btnBlockingToggle != null) {
            btnBlockingToggle.setOnClickListener(v -> toggleBlocking());
            updateBlockingButton();
        }

        // Bottom Navigation
        findViewById(R.id.btnNavLocker).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        findViewById(R.id.btnNavLogs).setOnClickListener(v -> {
            Intent intent = new Intent(this, LogViewerActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btnNavHome).setOnClickListener(v -> {
            // Already on Home
            Toast.makeText(this, "Home", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnNavFile).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileAccessActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btnNavSnapshot).setOnClickListener(v -> {
            Intent intent = new Intent(this, com.dearmoon.shield.snapshot.RecoveryActivity.class);
            startActivity(intent);
        });

        // Test Suite Button
        Button btnTestSuite = findViewById(R.id.btnTestSuite);
        if (btnTestSuite != null) {
            btnTestSuite.setOnClickListener(v -> {
                Intent intent = new Intent(this, com.dearmoon.shield.testing.TestActivity.class);
                startActivity(intent);
            });
        }

        // Clear Honeyfiles Button
        if (btnClearHoneyfiles != null) {
            btnClearHoneyfiles.setOnClickListener(v -> clearHoneyfiles());
        }

        updateStatusDisplay();
        
        // Register high-risk alert receiver
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
            startShieldService();
        }
    }

    private void toggleVpn() {
        boolean isVpnRunning = isServiceRunning(NetworkGuardService.class);
        if (isVpnRunning) {
            stopVpnService();
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, 0);
        } else {
            onActivityResult(0, RESULT_OK, null);
        }
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, NetworkGuardService.class);
        intent.setAction(NetworkGuardService.ACTION_STOP);
        startService(intent);

        Toast.makeText(this, "Network Guard Disabled", Toast.LENGTH_SHORT).show();
        
        // Update UI after short delay to allow service to stop
        new android.os.Handler().postDelayed(() -> updateStatusDisplay(), 300);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            Intent intent = new Intent(this, NetworkGuardService.class);
            startService(intent);
            Toast.makeText(this, "Network Guard Protected", Toast.LENGTH_SHORT).show();
            updateStatusDisplay();
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
        updateStatusDisplay();
    }

    private void stopShieldService() {
        Intent serviceIntent = new Intent(this, ShieldProtectionService.class);
        stopService(serviceIntent);
        updateStatusDisplay();
    }

    private void updateStatusDisplay() {
        updateStatusDisplay(true);
    }

    private void updateStatusDisplay(boolean checkVpn) {
        boolean isServiceRunning = isServiceRunning(ShieldProtectionService.class);
        boolean isVpnRunning = checkVpn && isServiceRunning(NetworkGuardService.class);
        boolean isLockerGuardEnabled = isAccessibilityServiceEnabled();

        if (isServiceRunning) {
            // Stop glitch effect
            tvProtectionStatus.stopGlitchEffect();

            // Trigger scan beam animation, then show cursor
            tvProtectionStatus.setText("System Protected");
            tvProtectionStatus.setTextColor(0xFF10B981); // Emerald 500

            tvProtectionStatus.startScanBeam(() -> {
                // After scan completes, start cursor blink
                tvProtectionStatus.startCursorBlink();
            });

            // Mode B Active State
            btnModeB.setBackgroundResource(R.drawable.bg_glass_button_active);
            btnModeB.setText("Active");
            btnModeB.setTextColor(0xFFFFFFFF);
        } else {
            // Stop cursor and scan effects
            tvProtectionStatus.stopCursorBlink();

            tvProtectionStatus.setText("Protection Inactive");
            tvProtectionStatus.setTextColor(0xFF94A3B8); // Muted gray

            // Start glitch effect for inactive state
            tvProtectionStatus.startGlitchEffect();

            // Mode B Inactive State
            btnModeB.setBackgroundResource(R.drawable.bg_glass_button_inactive);
            btnModeB.setText("Mode B");
            btnModeB.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }

        if (checkVpn) {
            if (isVpnRunning) {
                btnVpn.setBackgroundResource(R.drawable.bg_glass_button_active);
                btnVpn.setTextColor(0xFFFFFFFF);
                btnVpn.setText("Network Guard: ON");
            } else {
                btnVpn.setBackgroundResource(R.drawable.bg_glass_button_inactive);
                btnVpn.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                btnVpn.setText("Network Guard");
            }
        }

        // Permission status removed - no longer displayed
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/com.dearmoon.shield.lockerguard.LockerShieldService";
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled == 1) {
                String services = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                return services != null && services.contains(service);
            }
        } catch (Exception e) {
        }
        return false;
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
        
        boolean isEnabled = getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .getBoolean("blocking_enabled", false);
        
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("blocking_enabled", !isEnabled)
                .apply();
        
        Intent intent = new Intent("com.dearmoon.shield.TOGGLE_BLOCKING");
        intent.putExtra("enabled", !isEnabled);
        sendBroadcast(intent);
        
        updateBlockingButton();
        Toast.makeText(this, !isEnabled ? "Network Blocking Enabled" : "Network Blocking Disabled", 
                Toast.LENGTH_SHORT).show();
    }
    
    private void updateBlockingButton() {
        if (btnBlockingToggle == null) return;
        
        boolean isEnabled = getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .getBoolean("blocking_enabled", false);
        
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
        String[] honeyfileNames = {
            "IMPORTANT_BACKUP.txt",
            "PRIVATE_KEYS.dat",
            "CREDENTIALS.txt",
            "SECURE_VAULT.bin",
            "FINANCIAL_DATA.xlsx",
            "PASSWORDS.txt"
        };

        String[] directories = getMonitoredDirectories();
        int deletedCount = 0;

        for (String dir : directories) {
            java.io.File directory = new java.io.File(dir);
            if (!directory.exists()) continue;

            for (String name : honeyfileNames) {
                java.io.File honeyfile = new java.io.File(directory, name);
                if (honeyfile.exists() && honeyfile.delete()) {
                    Log.d(TAG, "Deleted honeyfile: " + honeyfile.getAbsolutePath());
                    deletedCount++;
                }
            }
        }

        Log.i(TAG, "Cleared " + deletedCount + " honeyfiles");
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
                        .setPositiveButton("View Logs", (dialog, which) -> {
                            Intent logIntent = new Intent(MainActivity.this, LogViewerActivity.class);
                            startActivity(logIntent);
                        })
                        .setNegativeButton("Dismiss", null)
                        .setCancelable(false)
                        .show();
                });
            }
        }
    }
}