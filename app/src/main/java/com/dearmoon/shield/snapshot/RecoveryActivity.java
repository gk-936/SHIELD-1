package com.dearmoon.shield.snapshot;

import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.dearmoon.shield.R;
import com.dearmoon.shield.ui.GradientShiftButton;

public class RecoveryActivity extends AppCompatActivity {
    private RestoreEngine restoreEngine;
    private SnapshotManager snapshotManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recovery);

        // Force status bar to black
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        restoreEngine = new RestoreEngine(this);
        snapshotManager = new SnapshotManager(this);

        TextView tvStatus = findViewById(R.id.tvRecoveryStatus);
        TextView tvSnapshotInfo = findViewById(R.id.tvSnapshotInfo);
        GradientShiftButton btnCreateSnapshot = findViewById(R.id.btnCreateSnapshot);
        GradientShiftButton btnRestore = findViewById(R.id.btnStartRestore);
        Button btnCancel = findViewById(R.id.btnCancelRestore);

        updateSnapshotInfo(tvSnapshotInfo);

        long attackId = snapshotManager.getActiveAttackId();

        if (attackId > 0) {
            tvStatus.setText("Attack Detected");
        } else {
            tvStatus.setText("No Active Threat");
        }

        btnCreateSnapshot.setOnClickListener(v -> {
            btnCreateSnapshot.setEnabled(false);
            tvStatus.setText("Creating snapshot...");

            new Thread(() -> {
                String[] dirs = getMonitoredDirectories();
                snapshotManager.createBaselineSnapshot(dirs);

                try {
                    Thread.sleep(2000);
                } catch (Exception e) {
                }

                runOnUiThread(() -> {
                    updateSnapshotInfo(tvSnapshotInfo);
                    tvStatus.setText("Snapshot Created");
                    btnCreateSnapshot.setEnabled(true);
                });
            }).start();
        });

        btnRestore.setOnClickListener(v -> {
            long lastSnapshotTime = getSharedPreferences("ShieldPrefs", MODE_PRIVATE)
                    .getLong("last_snapshot_time", 0);

            if (lastSnapshotTime == 0) {
                tvStatus.setText("No Snapshot Available");
                return;
            }

            long restoreId = attackId > 0 ? attackId : snapshotManager.getActiveAttackId();

            if (restoreId <= 0) {
                tvStatus.setText("No Active Threat");
                return;
            }

            btnRestore.setEnabled(false);
            tvStatus.setText("Restoring files...");

            new Thread(() -> {
                if (attackId > 0) {
                    snapshotManager.stopAttackTracking();
                }

                RestoreEngine.RestoreResult result = restoreEngine.restoreFromAttack(restoreId);

                runOnUiThread(() -> {
                    if (result.noChanges) {
                        tvStatus.setText("No Changes Detected");
                    } else if (result.failedCount > 0) {
                        tvStatus.setText("Restore Complete\nRestored: " + result.restoredCount + " | Failed: "
                                + result.failedCount);
                    } else {
                        tvStatus.setText("Restore Complete\n" + result.restoredCount + " files restored");
                    }
                    btnRestore.setEnabled(true);
                });
            }).start();
        });

        btnCancel.setOnClickListener(v -> finish());
    }

    private void updateSnapshotInfo(TextView tvSnapshotInfo) {
        long lastSnapshotTime = getSharedPreferences("ShieldPrefs", MODE_PRIVATE)
                .getLong("last_snapshot_time", 0);

        if (lastSnapshotTime > 0) {
            long minutesAgo = (System.currentTimeMillis() - lastSnapshotTime) / 60000;
            if (minutesAgo < 1) {
                tvSnapshotInfo.setText("Last snapshot: Just now");
            } else {
                tvSnapshotInfo.setText("Last snapshot: " + minutesAgo + " minutes ago");
            }
        } else {
            tvSnapshotInfo.setText("No snapshot created yet");
        }
    }

    private String[] getMonitoredDirectories() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        java.io.File externalStorage = android.os.Environment.getExternalStorageDirectory();
        if (externalStorage != null && externalStorage.exists()) {
            dirs.add(new java.io.File(externalStorage, "Documents").getAbsolutePath());
            dirs.add(new java.io.File(externalStorage, "Download").getAbsolutePath());
            dirs.add(new java.io.File(externalStorage, "Pictures").getAbsolutePath());
            dirs.add(new java.io.File(externalStorage, "DCIM").getAbsolutePath());
        }
        return dirs.toArray(new String[0]);
    }
}
