package com.dearmoon.shield;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.dearmoon.shield.testing.TestActivity;
import java.io.File;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ── Edge-to-Edge Immersive Status Bar ───────────────────────────────
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                new androidx.core.view.WindowInsetsControllerCompat(
                        getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
        // ───────────────────────────────────────────────────────────────────

        setContentView(R.layout.activity_settings);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }

        // Push AppBar down by status bar height in Edge-to-Edge mode
        com.google.android.material.appbar.AppBarLayout appBar =
                (com.google.android.material.appbar.AppBarLayout) toolbar.getParent();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, insets.top, 0, 0);
            return windowInsets;
        });

        initializeViews();
    }

    private void initializeViews() {
        // Demo Mode — wired first so it's the easiest thing to find
        Button btnDemoMode = findViewById(R.id.btnDemoMode);
        btnDemoMode.setOnClickListener(v -> startActivity(new Intent(this, DemoActivity.class)));

        Button btnClearHoneyfiles = findViewById(R.id.btnClearHoneyfilesSettings);
        Button btnTestSuite = findViewById(R.id.btnTestSuiteSettings);
        Button btnUserGuide = findViewById(R.id.btnUserGuide);
        Button btnManageWhitelist = findViewById(R.id.btnManageWhitelist);
        TextView tvPermissionCount = findViewById(R.id.tvPermissionCount);
        TextView tvPermissionList = findViewById(R.id.tvPermissionList);

        // Clear Honeyfiles
        btnClearHoneyfiles.setOnClickListener(v -> clearHoneyfiles());

        // Test Suite
        btnTestSuite.setOnClickListener(v -> startActivity(new Intent(this, TestActivity.class)));

        // User Guide
        btnUserGuide.setOnClickListener(v -> showUserGuide());

        // Manage Whitelist
        btnManageWhitelist.setOnClickListener(v -> startActivity(new Intent(this, WhitelistActivity.class)));

        // Accessibility Service
        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // Permission list container
        android.view.View permissionListContainer = findViewById(R.id.permissionListContainer);

        // Load permissions
        loadPermissions(tvPermissionCount, tvPermissionList);

        // Toggle permissions list visibility
        tvPermissionCount.setOnClickListener(v -> {
            if (permissionListContainer.getVisibility() == android.view.View.GONE) {
                permissionListContainer.setVisibility(android.view.View.VISIBLE);
                String currentText = tvPermissionCount.getText().toString();
                tvPermissionCount.setText(currentText.replace("(Tap to expand)", "(Tap to collapse)"));
            } else {
                permissionListContainer.setVisibility(android.view.View.GONE);
                String currentText = tvPermissionCount.getText().toString();
                tvPermissionCount.setText(currentText.replace("(Tap to collapse)", "(Tap to expand)"));
            }
        });
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
            File directory = new File(dir);
            if (!directory.exists())
                continue;
            for (String name : honeyfileNames) {
                File honeyfile = new File(directory, name);
                if (honeyfile.exists() && honeyfile.delete()) {
                    deletedCount++;
                }
            }
        }
        return deletedCount;
    }

    private String[] getMonitoredDirectories() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage != null && externalStorage.exists()) {
            addIfExists(dirs, new File(externalStorage, "Documents"));
            addIfExists(dirs, new File(externalStorage, "Download"));
            addIfExists(dirs, new File(externalStorage, "Pictures"));
            addIfExists(dirs, new File(externalStorage, "DCIM"));
        }
        return dirs.toArray(new String[0]);
    }

    private void addIfExists(java.util.List<String> list, File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            list.add(dir.getAbsolutePath());
        }
    }

    private void loadPermissions(TextView tvCount, TextView tvList) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(
                    getPackageName(),
                    PackageManager.GET_PERMISSIONS);

            String[] permissions = packageInfo.requestedPermissions;
            if (permissions != null) {
                tvCount.setText("Total Permissions: " + permissions.length);

                StringBuilder permissionList = new StringBuilder();
                for (String permission : permissions) {
                    String shortName = permission.substring(permission.lastIndexOf('.') + 1);
                    boolean granted = ContextCompat.checkSelfPermission(this,
                            permission) == PackageManager.PERMISSION_GRANTED;

                    permissionList.append(granted ? "✓ " : "✗ ")
                            .append(shortName)
                            .append("\n");
                }
                tvList.setText(permissionList.toString());
            }
        } catch (Exception e) {
            tvCount.setText("Error loading permissions");
            tvList.setText(e.getMessage());
        }
    }

    private void showUserGuide() {
        Intent intent = new Intent(this, UserGuideActivity.class);
        intent.putExtra(UserGuideActivity.EXTRA_FROM_SETTINGS, true);
        startActivity(intent);
    }
}
