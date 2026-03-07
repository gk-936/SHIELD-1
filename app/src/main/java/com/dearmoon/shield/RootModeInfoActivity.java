package com.dearmoon.shield;

import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

/**
 * RootModeInfoActivity — Task 2
 *
 * Replaces the dead Toast on Mode A with an honest, visually polished
 * "coming soon" screen that explains what root mode adds and why it
 * requires a rooted device. Shows product vision instead of a dead end.
 */
public class RootModeInfoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Edge-to-Edge Immersive Status Bar (MUST be before setContentView) ─
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                new androidx.core.view.WindowInsetsControllerCompat(
                        getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
        // ───────────────────────────────────────────────────────────────────

        setContentView(R.layout.activity_root_mode_info);

        // Push NestedScrollView content below the transparent status bar
        android.view.View rootScrollView = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootScrollView, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, insets.top, 0, insets.bottom);
            return windowInsets;
        });

        Button btnBack = findViewById(R.id.btnRootInfoBack);
        btnBack.setOnClickListener(v -> finish());
    }
}
