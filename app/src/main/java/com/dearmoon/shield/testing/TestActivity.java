package com.dearmoon.shield.testing;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.dearmoon.shield.R;

public class TestActivity extends AppCompatActivity {
    private RealisticRansomwareSimulator simulator;
    private TextView tvTestResults;
    private ScrollView scrollView;
    private Button btnTest1, btnTest2, btnTest3, btnTest4, btnTest5;
    private Button btnStopTest, btnCleanup, btnViewLogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        simulator = new RealisticRansomwareSimulator(this);

        tvTestResults = findViewById(R.id.tvTestResults);
        scrollView = findViewById(R.id.scrollView);
        btnTest1 = findViewById(R.id.btnTest1);
        btnTest2 = findViewById(R.id.btnTest2);
        btnTest3 = findViewById(R.id.btnTest3);
        btnTest4 = findViewById(R.id.btnTest4);
        btnTest5 = findViewById(R.id.btnTest5);
        btnStopTest = findViewById(R.id.btnStopTest);
        btnCleanup = findViewById(R.id.btnCleanup);
        btnViewLogs = findViewById(R.id.btnViewLogs);

        btnTest1.setOnClickListener(v -> runTest("Test 1: Multi-Stage Ransomware Attack", () -> simulator.testMultiStageAttack()));
        btnTest2.setOnClickListener(v -> runTest("Test 2: Rapid File Modification (SPRT)", () -> simulator.testRapidFileModification()));
        btnTest3.setOnClickListener(v -> runTest("Test 3: High Entropy Files", () -> simulator.testHighEntropyFiles()));
        btnTest4.setOnClickListener(v -> runTest("Test 4: Network Activity", () -> simulator.testNetworkActivity()));
        btnTest5.setOnClickListener(v -> runTest("Test 5: Benign Activity (False Positive Check)", () -> simulator.testBenignActivity()));

        btnStopTest.setOnClickListener(v -> {
            simulator.stopSimulation();
            appendResult("\n[STOPPED] Test execution stopped\n");
            Toast.makeText(this, "Test stopped", Toast.LENGTH_SHORT).show();
        });

        btnCleanup.setOnClickListener(v -> {
            TestFileManager.CleanupResult result = simulator.cleanupTestFiles();
            appendResult("\n[CLEANUP] " + result.toString() + "\n");
            if (!result.isComplete()) {
                appendResult("[WARNING] Failed to delete " + result.failedCount + " files\n");
                for (String path : result.failedFiles) {
                    appendResult("  - " + path + "\n");
                }
            }
            Toast.makeText(this, result.toString(), Toast.LENGTH_SHORT).show();
        });

        btnViewLogs.setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, com.dearmoon.shield.LogViewerActivity.class));
        });

        appendResult("=== SHIELD RANSOMWARE SIMULATOR (REDESIGNED) ===\n");
        appendResult("Safe testing environment - Dedicated test directory\n");
        appendResult("Test directory: /storage/emulated/0/SHIELD_TEST/\n\n");
        appendResult("Prerequisites:\n");
        appendResult("1. Start Protection Service\n");
        appendResult("2. Start VPN Network Monitoring (optional)\n\n");
        appendResult("Tests:\n");
        appendResult("1. Multi-Stage Attack (REALISTIC)\n");
        appendResult("2. Rapid File Modification (SPRT)\n");
        appendResult("3. High Entropy Files\n");
        appendResult("4. Network Activity\n");
        appendResult("5. Benign Activity (False Positive Check)\n\n");
        appendResult("Select a test to begin...\n\n");
    }

    private void runTest(String testName, Runnable testRunnable) {
        if (simulator.isRunning()) {
            Toast.makeText(this, "Test already running. Stop first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if ShieldProtectionService is running
        if (!isServiceRunning(com.dearmoon.shield.services.ShieldProtectionService.class)) {
            Toast.makeText(this, "Start Protection Service first!", Toast.LENGTH_LONG).show();
            appendResult("\n[ERROR] ShieldProtectionService not running\n");
            appendResult("Please start Mode B before testing\n\n");
            return;
        }

        appendResult("========================================\n");
        appendResult("Running: " + testName + "\n");
        appendResult("========================================\n");
        testRunnable.run();
        Toast.makeText(this, testName + " started", Toast.LENGTH_SHORT).show();
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void appendResult(String text) {
        runOnUiThread(() -> {
            tvTestResults.append(text);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        simulator.stopSimulation();
    }
}
