package com.dearmoon.shield;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
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
import com.dearmoon.shield.data.EventDatabase;
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
        android.view.View btnClearHoneyfiles = findViewById(R.id.btnClearHoneyfilesSettings);
        android.view.View btnUserGuide = findViewById(R.id.btnUserGuide);
        android.view.View btnManageWhitelist = findViewById(R.id.btnManageWhitelist);
        android.view.View btnAccessibility = findViewById(R.id.btnAccessibility);
        
        TextView tvPermissionCount = findViewById(R.id.tvPermissionCount);
        TextView tvPermissionList = findViewById(R.id.tvPermissionList);

        // Clear Honeyfiles
        btnClearHoneyfiles.setOnClickListener(v -> clearHoneyfiles());

        // User Guide
        btnUserGuide.setOnClickListener(v -> showUserGuide());

        // Manage Whitelist
        btnManageWhitelist.setOnClickListener(v -> startActivity(new Intent(this, WhitelistActivity.class)));

        // Accessibility Service
        btnAccessibility.setOnClickListener(v ->
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // Explore Features Showcase
        findViewById(R.id.btnFeatures).setOnClickListener(v -> 
            startActivity(new Intent(this, com.dearmoon.shield.features.FeaturesActivity.class)));

        // Clear All Data
        findViewById(R.id.btnClearAllData).setOnClickListener(v -> clearAllData());

        // Permission list container elements
        android.view.View permissionListContainer = findViewById(R.id.permissionListContainer);
        android.view.View permissionToggleArea = findViewById(R.id.permissionToggleArea);

        // Load permissions
        loadPermissions(tvPermissionCount, tvPermissionList);

        android.widget.ImageView ivExpandIcon = findViewById(R.id.ivExpandIcon);

        // Toggle permissions list visibility
        permissionToggleArea.setOnClickListener(v -> {
            if (permissionListContainer.getVisibility() == android.view.View.GONE) {
                // Expanding
                permissionListContainer.setVisibility(android.view.View.VISIBLE);
                if (ivExpandIcon != null) {
                    ivExpandIcon.setRotation(180f);
                }
            } else {
                // Collapsing
                permissionListContainer.setVisibility(android.view.View.GONE);
                if (ivExpandIcon != null) {
                    ivExpandIcon.setRotation(0f);
                }
            }
        });
    }

    private void clearAllData() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear All Data")
                .setMessage("This will permanently delete all events, detection results, snapshots, whitelist, and dashboard stats. This cannot be undone. Continue?")
                .setPositiveButton("Clear All", (dialog, which) -> {
                    // 1. Wipe event + detection DB
                    EventDatabase.getInstance(this).clearAllEvents();

                    // 2. Delete snapshot DB file
                    File snapshotDb = getDatabasePath("shield_snapshots.db");
                    if (snapshotDb.exists()) snapshotDb.delete();
                    File snapshotDbShm = getDatabasePath("shield_snapshots.db-shm");
                    if (snapshotDbShm.exists()) snapshotDbShm.delete();
                    File snapshotDbWal = getDatabasePath("shield_snapshots.db-wal");
                    if (snapshotDbWal.exists()) snapshotDbWal.delete();

                    // 3. Clear whitelist SharedPreferences
                    SharedPreferences wlPrefs = getSharedPreferences("shield_whitelist", MODE_PRIVATE);
                    wlPrefs.edit().clear().apply();

                    // 4. Reset dashboard stats (attacks found, files scanned, threats)
                    // Also clear the seeded_v1 flag so ShieldStats re-seeds to 0 on next launch.
                    SharedPreferences statsPrefs = getSharedPreferences("ShieldStats", MODE_PRIVATE);
                    statsPrefs.edit().clear().apply();

                    // 5. Delete legacy telemetry file if present
                    new File(getFilesDir(), "modeb_telemetry.json").delete();

                    // 6. Delete all honeyfiles (both static and dynamic)
                    deleteAllHoneyfiles();

                    Toast.makeText(this, "All SHIELD data cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        int deletedCount = 0;
        String[] directories = getMonitoredDirectories();

        // 1. Delete legacy static honeyfiles
        String[] legacyNames = {
                "IMPORTANT_BACKUP.txt", "PRIVATE_KEYS.dat", "CREDENTIALS.txt",
                "SECURE_VAULT.bin", "FINANCIAL_DATA.xlsx", "PASSWORDS.txt"
        };
        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) continue;
            for (String name : legacyNames) {
                File honeyfile = new File(directory, name);
                if (honeyfile.exists() && honeyfile.delete()) deletedCount++;
            }
        }

        // 2. Delete dynamic hashed honeyfiles
        SharedPreferences prefs = getSharedPreferences("ShieldHoneyfiles", MODE_PRIVATE);
        java.util.Collection<?> dynamicNames = prefs.getAll().values();
        for (String dir : directories) {
            File directory = new File(dir);
            if (!directory.exists()) continue;
            for (Object nameObj : dynamicNames) {
                if (nameObj instanceof String) {
                    File honeyfile = new File(directory, (String) nameObj);
                    if (honeyfile.exists() && honeyfile.delete()) deletedCount++;
                }
            }
        }
        
        // Clear the preferences so new dynamic names are generated next time
        prefs.edit().clear().apply();

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
