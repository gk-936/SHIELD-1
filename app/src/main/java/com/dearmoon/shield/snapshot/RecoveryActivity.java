package com.dearmoon.shield.snapshot;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.dearmoon.shield.R;

public class RecoveryActivity extends AppCompatActivity {
    private RestoreEngine restoreEngine;
    private SnapshotManager snapshotManager;
    private final Handler animHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Edge-to-Edge Immersive ──────────────────────────────────────────
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        WindowInsetsControllerCompat insetsCtrl =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsCtrl.setAppearanceLightStatusBars(false);   // white icons on dark bg
        insetsCtrl.setAppearanceLightNavigationBars(false);

        setContentView(R.layout.activity_recovery);

        // Apply system bar insets
        View root = findViewById(R.id.recoveryRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, insets.top, 0, insets.bottom);
            return windowInsets;
        });

        // ── Init engines ────────────────────────────────────────────────────
        restoreEngine   = new RestoreEngine(this);
        snapshotManager = com.dearmoon.shield.ShieldApplication.get().getSnapshotManager();

        // ── Find views ──────────────────────────────────────────────────────
        LinearLayout headerSection = findViewById(R.id.headerSection);
        LinearLayout statusPanel   = findViewById(R.id.statusPanel);
        FrameLayout tileRestore    = findViewById(R.id.tileRestore);
        LinearLayout restoreContent= findViewById(R.id.restoreContent);
        FrameLayout tileCapture    = findViewById(R.id.tileCapture);
        FrameLayout captureContent = findViewById(R.id.captureContent);
        LinearLayout btnCyberBack  = findViewById(R.id.btnCyberBack);

        TextView tvTitle           = findViewById(R.id.tvRecoveryTitle);
        TextView tvStatus          = findViewById(R.id.tvRecoveryStatus);
        TextView tvSnapshotInfo    = findViewById(R.id.tvSnapshotInfo);

        View scanningBeam          = findViewById(R.id.scanningBeam);
        ImageView radarBackground  = findViewById(R.id.radarBackground);
        View captureHighlight      = findViewById(R.id.captureHighlight);

        float density = getResources().getDisplayMetrics().density;

        // ════════════════════════════════════════════════════════════════════
        //  CYBER SECURITY STYLING
        // ════════════════════════════════════════════════════════════════════

        // 1. Status Panel — dark block with slight border
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(Color.parseColor("#0A1128"));
        statusBg.setStroke((int)(1.5f * density), Color.parseColor("#1A4A90E2"));
        statusBg.setCornerRadius(16 * density);
        statusPanel.setBackground(statusBg);

        // 2. Restore Tile — Deep purple
        GradientDrawable restoreBg = new GradientDrawable();
        restoreBg.setColor(Color.parseColor("#1B0C30"));
        restoreBg.setStroke((int)(2f * density), Color.parseColor("#609D00FF"));
        restoreBg.setCornerRadius(12 * density);
        restoreContent.setBackground(restoreBg);

        // 3. Capture Tile — Electric blue
        GradientDrawable captureBg = new GradientDrawable();
        captureBg.setColor(Color.parseColor("#0A183D"));
        captureBg.setStroke((int)(2f * density), Color.parseColor("#6000C8FF"));
        captureBg.setCornerRadius(12 * density);
        captureContent.setBackground(captureBg);

        // 4. Back Button — Cyber matte
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(Color.parseColor("#050A15"));
        backBg.setStroke((int)(1.5f * density), Color.parseColor("#4000C8FF"));
        backBg.setCornerRadius(8 * density);
        btnCyberBack.setBackground(backBg);

        // 5. Scanning Beam
        GradientDrawable beamBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, Color.parseColor("#00C8FF"), Color.TRANSPARENT});
        scanningBeam.setBackground(beamBg);

        // 6. Capture Tile Highlight Sweep
        GradientDrawable highlightBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, Color.parseColor("#3000E5FF"), Color.TRANSPARENT});
        captureHighlight.setBackground(highlightBg);

        // 7. Title Gradient Shader
        tvTitle.post(() -> {
            float textW = tvTitle.getPaint().measureText(tvTitle.getText().toString());
            if (textW <= 0) return;
            LinearGradient titleGradient = new LinearGradient(
                    0, 0, textW, 0,
                    new int[]{
                            Color.parseColor("#00C8FF"),
                            Color.parseColor("#E0F7FF"),
                            Color.parseColor("#00C8FF")
                    },
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP);
            tvTitle.getPaint().setShader(titleGradient);
            tvTitle.invalidate();
        });

        // 8. Radar Circular Sweep
        ShapeDrawable radarDrawable = new ShapeDrawable(new OvalShape());
        radarDrawable.getPaint().setShader(new SweepGradient(160 * density, 160 * density,
                new int[]{Color.TRANSPARENT, Color.parseColor("#2000C8FF"), Color.TRANSPARENT},
                new float[]{0f, 0.95f, 1f}
        ));
        radarBackground.setImageDrawable(radarDrawable);


        // ════════════════════════════════════════════════════════════════════
        //  DATA POPULATION
        // ════════════════════════════════════════════════════════════════════
        updateSnapshotInfo(tvSnapshotInfo);
        long attackId = snapshotManager.getActiveAttackId();

        if (attackId > 0) {
            tvStatus.setText("⚠  System Breach Detected");
            tvStatus.setTextColor(Color.parseColor("#FF3B3B"));
        } else {
            tvStatus.setText("No Active Threat");
            tvStatus.setTextColor(Color.parseColor("#00E676"));
        }

        // ════════════════════════════════════════════════════════════════════
        //  ACTION TILE HANDLERS
        // ════════════════════════════════════════════════════════════════════

        // ── Capture Snapshot ─────────────────────────────────────────────────
        tileCapture.setOnClickListener(v -> {
            tileCapture.setEnabled(false);
            tvStatus.setText("Scanning directory structure…");
            tvStatus.setTextColor(Color.parseColor("#FFB300"));

            flashCaptureTile(tileCapture);

            new Thread(() -> {
                String[] dirs = getMonitoredDirectories();
                snapshotManager.createBaselineSnapshot(dirs);
                try { Thread.sleep(2000); } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    updateSnapshotInfo(tvSnapshotInfo);
                    tvStatus.setText("✓  Snapshot Secured");
                    tvStatus.setTextColor(Color.parseColor("#00E676"));
                    tileCapture.setEnabled(true);
                });
            }).start();
        });

        // ── Restore Files ────────────────────────────────────────────────────
        tileRestore.setOnClickListener(v -> {
            long lastSnapshotTime = getSharedPreferences("ShieldPrefs", MODE_PRIVATE)
                    .getLong("last_snapshot_time", 0);
            if (lastSnapshotTime == 0) {
                tvStatus.setText("No Baseline Snapshot Available");
                tvStatus.setTextColor(Color.parseColor("#FF3B3B"));
                return;
            }
            long restoreId = attackId > 0 ? attackId : snapshotManager.getActiveAttackId();
            tileRestore.setEnabled(false);
            if (restoreId <= 0) {
                tvStatus.setText("Executing Deep System Integrity Audit…");
                tvStatus.setTextColor(Color.parseColor("#00C8FF"));
            } else {
                tvStatus.setText("Executing Rescue Protocol…");
                tvStatus.setTextColor(Color.parseColor("#00C8FF"));
            }
            
            animateRestoreRipple(tileRestore, density);

            new Thread(() -> {
                if (attackId > 0) snapshotManager.stopAttackTracking();
                RestoreEngine.RestoreResult result = restoreEngine.restoreFromAttack(restoreId);
                runOnUiThread(() -> {
                    if (result.noChanges) {
                        tvStatus.setText("Zero Drift Detected");
                        tvStatus.setTextColor(Color.parseColor("#00C8FF"));
                    } else if (result.failedCount > 0) {
                        tvStatus.setText("Restored: " + result.restoredCount
                                + " | Corrupted: " + result.failedCount);
                    } else {
                        tvStatus.setText("✓  System Rollback Complete");
                    }
                    tvStatus.setTextColor(Color.parseColor("#00E676"));
                    tileRestore.setEnabled(true);
                });
            }).start();
        });

        // ── Back ─────────────────────────────────────────────────────────────
        btnCyberBack.setOnClickListener(v -> {
            GradientDrawable pressedBg = new GradientDrawable();
            pressedBg.setCornerRadius(8 * density);
            pressedBg.setColor(Color.parseColor("#0AFFFFFF"));
            pressedBg.setStroke((int)(2f * density), Color.parseColor("#00C8FF"));
            btnCyberBack.setBackground(pressedBg);
            animHandler.postDelayed(this::finish, 150);
        });

        // ════════════════════════════════════════════════════════════════════
        //  START PAGE ENTRY AND CONTINUOUS ANIMATIONS
        // ════════════════════════════════════════════════════════════════════
        startEntryAnimations(headerSection, statusPanel, tileRestore, tileCapture, 
                             tvTitle, scanningBeam, radarBackground, captureHighlight, density);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PAGE ENTRY ORCHESTRATOR
    // ════════════════════════════════════════════════════════════════════════
    private void startEntryAnimations(
            View header, View status, View restore, View capture,
            TextView title, View scanBeam, ImageView radar, View highlight, float density) {

        // 1️⃣ Header fades in
        header.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(100)
            .start();

        // 2️⃣ Status panel slides up
        status.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(450)
            .setStartDelay(250)
            .setInterpolator(new DecelerateInterpolator(1.2f))
            .start();

        // 3️⃣ Restore tile scales in
        restore.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(400)
            .setInterpolator(new OvershootInterpolator(1.2f))
            .start();

        // 4️⃣ Capture tile scales in shortly after
        capture.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(500)
            .setInterpolator(new OvershootInterpolator(1.2f))
            .start();

        // Start ambient/continuous actions
        animHandler.postDelayed(() -> startTitleFlicker(title), 800);
        animHandler.postDelayed(() -> startScanBeamPulse(scanBeam), 1000);
        animHandler.postDelayed(() -> startRadarRotation(radar), 600);
        animHandler.postDelayed(() -> startCaptureHighlight(highlight), 1500);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CONTINUOUS AMBIENT ANIMATIONS
    // ════════════════════════════════════════════════════════════════════════

    private void startTitleFlicker(TextView title) {
        Runnable flickerTask = new Runnable() {
            @Override
            public void run() {
                title.setAlpha(Math.random() > 0.85 ? 0.6f : 1f);
                animHandler.postDelayed(this, (long)(Math.random() * 150 + 50));
            }
        };
        animHandler.post(flickerTask);
    }

    private void startScanBeamPulse(View scanBeam) {
        View parent = (View) scanBeam.getParent();
        scanBeam.post(() -> {
            int parentW = parent.getWidth();
            if (parentW <= 0) return;
            scanBeam.getLayoutParams().width = (int)(parentW * 0.3f);
            scanBeam.requestLayout();

            ValueAnimator sweep = ValueAnimator.ofFloat(-parentW*0.3f, parentW);
            sweep.setDuration(2200);
            sweep.setRepeatCount(ValueAnimator.INFINITE);
            sweep.setStartDelay(1000); // Wait between sweeps
            sweep.setInterpolator(new LinearInterpolator());
            sweep.addUpdateListener(a -> scanBeam.setTranslationX((float) a.getAnimatedValue()));
            sweep.start();
        });
    }

    private void startRadarRotation(ImageView radar) {
        ObjectAnimator rotate = ObjectAnimator.ofFloat(radar, "rotation", 0f, 360f);
        rotate.setDuration(6000);
        rotate.setRepeatCount(ValueAnimator.INFINITE);
        rotate.setInterpolator(new LinearInterpolator());
        rotate.start();
    }

    private void startCaptureHighlight(View highlight) {
        View parent = (View) highlight.getParent();
        highlight.post(() -> {
            int parentW = parent.getWidth();
            if (parentW <= 0) return;
            
            ValueAnimator sweep = ValueAnimator.ofFloat(-100, parentW);
            sweep.setDuration(1500);
            sweep.setRepeatCount(ValueAnimator.INFINITE);
            sweep.setStartDelay(3000);
            sweep.setInterpolator(new LinearInterpolator());
            sweep.addUpdateListener(a -> highlight.setTranslationX((float) a.getAnimatedValue()));
            sweep.start();
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INTERACTIVE TILE ANIMATIONS
    // ════════════════════════════════════════════════════════════════════════

    private void animateRestoreRipple(View tile, float density) {
        // Tile expands slightly
        AnimatorSet press = new AnimatorSet();
        press.playTogether(
            ObjectAnimator.ofFloat(tile, "scaleX", 1f, 1.04f, 1f).setDuration(400),
            ObjectAnimator.ofFloat(tile, "scaleY", 1f, 1.04f, 1f).setDuration(400)
        );
        press.start();

        // Intense border glow
        LinearLayout content = tile.findViewById(R.id.restoreContent);
        GradientDrawable intenseBg = new GradientDrawable();
        intenseBg.setColor(Color.parseColor("#1B0C30"));
        intenseBg.setStroke((int)(3f * density), Color.parseColor("#E09D00FF")); // brighter, thicker
        intenseBg.setCornerRadius(12 * density);
        content.setBackground(intenseBg);

        // Reset the background after a moment
        animHandler.postDelayed(() -> {
            GradientDrawable normalBg = new GradientDrawable();
            normalBg.setColor(Color.parseColor("#1B0C30"));
            normalBg.setStroke((int)(2f * density), Color.parseColor("#609D00FF"));
            normalBg.setCornerRadius(12 * density);
            content.setBackground(normalBg);
        }, 600);
    }

    private void flashCaptureTile(View tile) {
        // Pulse effect
        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(
            ObjectAnimator.ofFloat(tile, "scaleX", 1f, 0.95f, 1f).setDuration(300),
            ObjectAnimator.ofFloat(tile, "scaleY", 1f, 0.95f, 1f).setDuration(300)
        );
        pulse.start();

        // Camera Flash Overlay
        if (tile instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) tile;
            View flash = new View(this);
            flash.setBackgroundColor(Color.parseColor("#90FFFFFF"));
            flash.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));
            vg.addView(flash);

            ObjectAnimator flashAnim = ObjectAnimator.ofFloat(flash, "alpha", 1f, 0f);
            flashAnim.setDuration(350);
            flashAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    vg.removeView(flash);
                }
            });
            flashAnim.start();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DATA HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private void updateSnapshotInfo(TextView tvSnapshotInfo) {
        long lastSnapshotTime = getSharedPreferences("ShieldPrefs", MODE_PRIVATE)
                .getLong("last_snapshot_time", 0);

        StringBuilder info = new StringBuilder();
        if (lastSnapshotTime > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "dd MMM yyyy  •  HH:mm:ss", java.util.Locale.US);
            info.append("Last snapshot: ").append(sdf.format(new java.util.Date(lastSnapshotTime)));
        } else {
            info.append("No snapshot available");
        }

        // Show the most recently added snapshot files (last 5)
        try {
            java.util.List<com.dearmoon.shield.snapshot.FileMetadata> files =
                new com.dearmoon.shield.snapshot.SnapshotDatabase(this).getAllBackedUpFiles();
            int n = files.size();
            if (n > 0) {
                info.append("\n\nRecently added files:\n");
                int shown = 0;
                for (int i = n - 1; i >= 0 && shown < 5; i--, shown++) {
                    com.dearmoon.shield.snapshot.FileMetadata f = files.get(i);
                    String name = new java.io.File(f.filePath).getName();
                    info.append("• ").append(name).append("\n");
                }
            }
        } catch (Exception e) {
            info.append("\n[Error loading snapshot file list]");
        }

        tvSnapshotInfo.setText(info.toString().trim());
    }

    private String[] getMonitoredDirectories() {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        java.io.File ext = android.os.Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists()) {
            dirs.add(new java.io.File(ext, "Documents").getAbsolutePath());
            dirs.add(new java.io.File(ext, "Download").getAbsolutePath());
            dirs.add(new java.io.File(ext, "Pictures").getAbsolutePath());
            dirs.add(new java.io.File(ext, "DCIM").getAbsolutePath());

            // Add RanSim sandbox (matching the protect service)
            java.io.File ransimSandbox = new java.io.File(ext, "Documents/shield_ransim_sandbox");
            if (ransimSandbox.exists()) {
                dirs.add(ransimSandbox.getAbsolutePath());
            }
        }
        return dirs.toArray(new String[0]);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        animHandler.removeCallbacksAndMessages(null);
    }
}
