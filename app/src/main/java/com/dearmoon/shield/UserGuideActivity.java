package com.dearmoon.shield;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class UserGuideActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "ShieldPrefs";
    private static final String KEY_FIRST_TIME = "first_time_user";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_guide);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        Button btnComplete = findViewById(R.id.btnCompleteGuide);
        btnComplete.setOnClickListener(v -> {
            markGuideAsComplete();
            finish();
        });
    }

    private void markGuideAsComplete() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_FIRST_TIME, false).apply();
    }

    public static boolean isFirstTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_FIRST_TIME, true);
    }
}
