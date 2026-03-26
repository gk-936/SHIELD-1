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
import com.dearmoon.shield.security.SecurityUtils;
import com.dearmoon.shield.ui.GlitchTextView;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import java.util.concurrent.Executor;
import android.graphics.RectF;
import android.view.MotionEvent;
import com.dearmoon.shield.ui.FluidMenuView;
import java.util.Arrays;
import java.util.Locale;
import androidx.appcompat.app.AlertDialog;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int VPN_REQUEST_CODE = 200;
    private static final int MODE_CONFIRM_REQUEST   = 300;   // Mode B result
    private static final int MODE_A_CONFIRM_REQUEST = 301;   // Mode A result

    private GlitchTextView tvProtectionStatus;
    private android.widget.TextView tvInfectionTimer;
    private android.view.View timerContainer;
    private Button btnUnifiedProtection;
    private com.dearmoon.shield.ui.SecurityRippleView securityRipple;
    private HighRiskAlertReceiver alertReceiver;
    private android.content.BroadcastReceiver dataUpdateReceiver;
    private com.dearmoon.shield.snapshot.SnapshotManager snapshotManager;
    private ShieldStats shieldStats;

    // Permission gate state
    private boolean viewsInitialized = false;
    private AlertDialog permissionDialog;

    private com.dearmoon.shield.ui.SecurityGaugeView gaugeView;
    private android.os.Handler bgHandler;
    private Runnable gaugeUpdateRunnable;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge display
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat windowInsetsController =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (windowInsetsController != null) {
            // Force light icons
            windowInsetsController.setAppearanceLightStatusBars(false); 
            windowInsetsController.setAppearanceLightNavigationBars(false);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }

        setContentView(R.layout.activity_main);
        
        // Apply window insets
        android.view.View root = findViewById(R.id.mainRoot);
        if (root != null) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
                androidx.core.graphics.Insets insets = windowInsets.getInsets(
                        androidx.core.view.WindowInsetsCompat.Type.systemBars());
                v.setPadding(0, insets.top, 0, insets.bottom);
                return windowInsets;
            });
        }

        if (hasRequiredPermissions()) {
            initializeViews();
            viewsInitialized = true;
        } else {
            showPermissionBlockingDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!viewsInitialized) {
            if (hasRequiredPermissions()) {
                if (permissionDialog != null && permissionDialog.isShowing()) {
                    permissionDialog.dismiss();
                }
                initializeViews();
                viewsInitialized = true;
            } else {
                showPermissionBlockingDialog();
            }
        }
    }

    // Permission gate layer

    private void showPermissionBlockingDialog() {
        if (permissionDialog != null && permissionDialog.isShowing()) {
            // Refresh permission message
            permissionDialog.setMessage(buildPermissionMessage());
            return;
        }
        permissionDialog = new AlertDialog.Builder(this)
                .setTitle("\uD83D\uDEE1 Permissions Required")
                .setMessage(buildPermissionMessage())
                .setCancelable(false)
                .setPositiveButton("Grant Permissions", (d, w) -> requestNextMissingPermission())
                .create();
        permissionDialog.setCanceledOnTouchOutside(false);
        // Back button behavior
        permissionDialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                d.dismiss();
                finishAffinity();
                return true;
            }
            return false;
        });
        permissionDialog.show();
    }

    private String buildPermissionMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("SHIELD needs the following permissions to protect your device.\n");
        sb.append("All must be granted before the app will start.\n\n");

        // File access check
        boolean hasFiles;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasFiles = Environment.isExternalStorageManager();
        } else {
            hasFiles = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                               == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                               == PackageManager.PERMISSION_GRANTED;
        }
        sb.append(hasFiles ? "\u2705" : "\u274C");
        sb.append("  All Files Access\n");
        sb.append("    Needed to scan, back up, and restore your files.\n\n");

        // Notifications check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean hasNotif = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            sb.append(hasNotif ? "\u2705" : "\u274C");
            sb.append("  Notifications\n");
            sb.append("    Needed for ransomware alerts and service status.\n\n");
        }

        // Overlay permission check
        boolean hasOverlay = Settings.canDrawOverlays(this);
        sb.append(hasOverlay ? "\u2705" : "\u274C");
        sb.append("  Display over other apps\n");
        sb.append("    Needed to show emergency guidance over lockscreens.\n\n");

        // Accessibility service check
        boolean hasA11y = isAccessibilityServiceEnabled();
        sb.append(hasA11y ? "\u2705" : "\u274C");
        sb.append("  Accessibility Service\n");
        sb.append("    Needed to detect and block locker-style ransomware.\n\n");

        sb.append("Tap \"Grant Permissions\" to address each item one at a time.");
        return sb.toString();
    }

    private boolean isAccessibilityServiceEnabled() {
        String component = getPackageName() + "/"
                + com.dearmoon.shield.lockerguard.LockerShieldService.class.getName();
        String enabled = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null
                && enabled.toLowerCase(Locale.ROOT).contains(component.toLowerCase(Locale.ROOT));
    }

    // Request missing permissions
    private void requestNextMissingPermission() {
        // File access check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        }, PERMISSION_REQUEST_CODE);
                return;
            }
        }

        // Notifications check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
                return;
            }
        }

        // Overlay permission check
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        // Accessibility request
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this,
                    "Enable \"SHIELD LockerGuard\" in Accessibility Settings, then come back.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasRequiredPermissions()) {
                if (permissionDialog != null && permissionDialog.isShowing()) {
                    permissionDialog.dismiss();
                }
                if (!viewsInitialized) {
                    initializeViews();
                    viewsInitialized = true;
                }
            } else {
                // Permissions missing
                showPermissionBlockingDialog();
            }
        }
    }

    private void initializeViews() {
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus);
        tvInfectionTimer = findViewById(R.id.tvInfectionTimer);
        timerContainer = findViewById(R.id.timerContainer);

        btnUnifiedProtection = findViewById(R.id.btnUnifiedProtection);
        gaugeView = findViewById(R.id.gaugeView);
        securityRipple = findViewById(R.id.securityRipple);

        snapshotManager = com.dearmoon.shield.ShieldApplication.get().getSnapshotManager();
        shieldStats = new ShieldStats(this);
        startGaugeBackgroundUpdater();

        // Unified protection button
        btnUnifiedProtection.setOnClickListener(v -> {
            boolean protectionActive = getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                    .getBoolean("shield_active", false);
            boolean modeARunning = isServiceRunning(com.dearmoon.shield.modea.ModeAService.class);

            if (protectionActive || modeARunning) {
                // Disable active protection
                triggerButtonRipple(btnUnifiedProtection, modeARunning);
                authenticateBiometric(() -> stopProtection());
            } else {
                // Enable protection
                triggerButtonRipple(btnUnifiedProtection, canStartModeA());
                if (canStartModeA()) {
                    // Rooted activation
                    android.content.Intent rootIntent = new android.content.Intent(this, ModeConfirmActivity.class);
                    rootIntent.putExtra("mode", "ROOT");
                    startActivityForResult(rootIntent, MODE_A_CONFIRM_REQUEST);
                    overridePendingTransition(R.anim.slide_up_from_bottom, 0);
                } else {
                    // Standard activation
                    checkPermissionsAndStartAnimation();
                }
            }
        });

        updateStatusDisplay();

        // Fluid bottom menu
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

            // Outside touch closure
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
        // Handle VPN request
        if ("com.dearmoon.shield.REQUEST_VPN_PERMISSION".equals(intent.getAction())) {
            Intent vpnIntent = android.net.VpnService.prepare(this);
            if (vpnIntent != null) {
                startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
            }
        }
    }

    // Trigger button ripple
    private void triggerButtonRipple(android.view.View btn, boolean isRoot) {
        if (securityRipple == null) return;

        // Phase 1: press-in
        android.animation.ObjectAnimator pressIn = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            btn,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.0f, 0.92f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.0f, 0.92f)
        );
        pressIn.setDuration(60);
        pressIn.setInterpolator(new android.view.animation.LinearInterpolator());

        // Phase 2: ping
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

        // Fire ripple burst
        android.util.Pair<Float, Float> centre = securityRipple.centreOf(btn);
        securityRipple.triggerRipple(centre.first, centre.second, isRoot);
    }

    // Open incident report
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
                .setSubtitle("Please authenticate to disable protection")
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG |
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        biometricPrompt.authenticate(promptInfo);
    }



    private void checkPermissionsAndStartAnimation() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Please grant all permissions first", Toast.LENGTH_SHORT).show();
            requestNecessaryPermissions();
            return;
        }

        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            // Show activation animation
            android.content.Intent stdIntent = new android.content.Intent(this, ModeConfirmActivity.class);
            stdIntent.putExtra("mode", "STANDARD");
            startActivityForResult(stdIntent, MODE_CONFIRM_REQUEST);
            overridePendingTransition(R.anim.slide_up_from_bottom, 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // VPN was granted! Now check permissions again and show animation
                checkPermissionsAndStartAnimation();
            } else {
                Toast.makeText(this, "VPN permission required for network monitoring", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == MODE_CONFIRM_REQUEST && resultCode == RESULT_OK) {
            // Start Mode B
            startShieldService();
        } else if (requestCode == MODE_A_CONFIRM_REQUEST && resultCode == RESULT_OK) {
            // Start Mode A
            startModeAService();
        }
    }

    // Check root capability
    private boolean canStartModeA() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;  // Requires Android 11+
        }
        return SecurityUtils.isDeviceRooted();
    }

    // Stop all protection
    private void stopProtection() {
        Log.i(TAG, "Stopping all protection services...");
        
        // Stop Mode A
        stopService(new Intent(this, com.dearmoon.shield.modea.ModeAService.class));
        
        // Stop Mode B
        stopShieldService();
        
        Toast.makeText(this, "Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    private void startModeAService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Root Mode requires Android 11+", Toast.LENGTH_SHORT).show();
            return;
        }
        // Standard infrastructure start
        startShieldService();
        // Start eBPF layer
        Intent intent = new Intent(this, com.dearmoon.shield.modea.ModeAService.class);
        startForegroundService(intent);
        Toast.makeText(this, "Root Mode starting\u2026", Toast.LENGTH_SHORT).show();
        updateStatusDisplay();
    }

    private void startShieldService() {
        // Reset status flags
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("intentionally_stopped", false)
            .putBoolean("shield_active", true)
            .apply();

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
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("shield_active", false)
            .apply();
        stopService(new Intent(this, ShieldProtectionService.class));
        Toast.makeText(this, "Protection Disabled", Toast.LENGTH_SHORT).show();
        updateStatusDisplay();
    }

    private void updateStatusDisplay() {
        boolean isModeBRunning = isServiceRunning(ShieldProtectionService.class);
        boolean isVpnRunning = isServiceRunning(NetworkGuardService.class);
        boolean isModeARunning = isServiceRunning(com.dearmoon.shield.modea.ModeAService.class);

        // Update button state
        String buttonText;
        boolean protectionActive = isModeBRunning || isModeARunning;

        // Persist shield state
        getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("shield_active", protectionActive)
                .apply();
        
        if (protectionActive) {
            // Stop protection state
            buttonText = "Stop Protection";
            btnUnifiedProtection.setBackgroundResource(R.drawable.bg_glass_button_active);
            btnUnifiedProtection.setTextColor(0xFFFFFFFF);
            
            tvProtectionStatus.stopGlitchEffect();
            tvProtectionStatus.setText("System Protected" + (isVpnRunning ? " + Network Guard" : ""));
            tvProtectionStatus.setTextColor(0xFFD2DBEB);
            tvProtectionStatus.startScanBeam(() -> tvProtectionStatus.startCursorBlink());
        } else {
            // Start protection state
            buttonText = "Start Protection";
            btnUnifiedProtection.setBackgroundResource(R.drawable.bg_glass_button_inactive);
            btnUnifiedProtection.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            
            tvProtectionStatus.stopCursorBlink();
            tvProtectionStatus.setText("Protection Inactive");
            tvProtectionStatus.setTextColor(0xFF6A90B4);
            tvProtectionStatus.startGlitchEffect();
        }
        btnUnifiedProtection.setText(buttonText);
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
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        if (!Settings.canDrawOverlays(this))
            return false;
        if (!isAccessibilityServiceEnabled())
            return false;
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
    protected void onDestroy() {
        super.onDestroy();
        
        if (bgHandler != null && gaugeUpdateRunnable != null) {
            bgHandler.removeCallbacks(gaugeUpdateRunnable);
        }
        
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

    private void startGaugeBackgroundUpdater() {
        android.os.HandlerThread thread = new android.os.HandlerThread("GaugeDataThread");
        thread.start();
        bgHandler = new android.os.Handler(thread.getLooper());
        
        gaugeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // Map database stats
                int totalFiles = 0;
                int attackedFiles = 0;
                
                try {
                    totalFiles = (int) com.dearmoon.shield.data.EventDatabase.getInstance(MainActivity.this)
                        .countEvents("file_system_events");
                    
                    try (android.database.Cursor c = com.dearmoon.shield.data.EventDatabase.getInstance(MainActivity.this)
                        .getReadableDatabase()
                        .rawQuery("SELECT COUNT(*) FROM detection_results WHERE confidence_score >= 70", null)) {
                        if (c != null && c.moveToFirst()) {
                            attackedFiles = c.getInt(0);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error querying gauge data", e);
                }

                // Fallback to stats
                if (totalFiles == 0 && shieldStats != null) {
                    totalFiles = shieldStats.getFilesScanned();
                    attackedFiles = shieldStats.getThreatsFound();
                }

                final int fTotal = totalFiles;
                final int fAttacked = attackedFiles;

                runOnUiThread(() -> {
                    if (gaugeView != null) {
                        gaugeView.updateGauge(fAttacked, fTotal);
                    }
                });

                bgHandler.postDelayed(this, 3000);
            }
        };
        bgHandler.post(gaugeUpdateRunnable);
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

                // Record attack detected
                if (shieldStats != null) shieldStats.recordAttackDetected();

                // Store attack window
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
                    // Show persistent timer
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
                // Store restored count
                MainActivity.this.getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .edit().putInt("last_restored_count", restoredCount).apply();

                runOnUiThread(() -> {
                    // Hide timer UI
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
