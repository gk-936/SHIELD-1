package com.dearmoon.shield.lockerguard;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.dearmoon.shield.R;

public class EmergencyRecoveryActivity extends AppCompatActivity {
    private static final String TAG = "EmergencyRecovery";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_recovery);

        // Force status bar to black
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        String suspiciousPackage = getIntent().getStringExtra("SUSPICIOUS_PACKAGE");
        int riskScore = getIntent().getIntExtra("RISK_SCORE", 0);

        TextView tvWarning = findViewById(R.id.tvWarning);
        TextView tvPackageName = findViewById(R.id.tvPackageName);
        TextView tvRiskScore = findViewById(R.id.tvRiskScore);
        Button btnOpenSettings = findViewById(R.id.btnOpenSettings);
        Button btnDismiss = findViewById(R.id.btnDismiss);

        tvWarning.setText("Locker Shield");
        tvPackageName.setText("Suspicious App: " + suspiciousPackage);
        tvRiskScore.setText("Risk Score: " + riskScore + "/100");

        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + suspiciousPackage));
            startActivity(intent);
        });

        btnDismiss.setOnClickListener(v -> finish());
    }
}
