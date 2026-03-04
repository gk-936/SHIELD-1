package com.dearmoon.shield;

import android.os.Build;
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
        setContentView(R.layout.activity_root_mode_info);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        Button btnBack = findViewById(R.id.btnRootInfoBack);
        btnBack.setOnClickListener(v -> finish());
    }
}
