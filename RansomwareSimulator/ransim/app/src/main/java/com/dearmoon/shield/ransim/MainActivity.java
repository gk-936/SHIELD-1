/**
 * SHIELD RANSOMWARE SIMULATOR — SECURITY RESEARCH ONLY
 * =====================================================
 * Package: com.dearmoon.shield.ransim
 *
 * SAFETY CONSTRAINTS:
 * - All file operations confined to sandbox directory only
 * - XOR cipher only (key 0x5A) — NOT real encryption  
 * - Locker overlay always shows password (TEST PASSWORD: 1234)
 * - STOP TEST button always accessible, no password needed
 * - Network simulation targets localhost only (127.0.0.1)
 * - Cleanup/restore runs automatically on stop or app exit
 *
 * SANDBOX PATH:
 * /sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/
 *
 * TO FORCE CLEANUP IF APP CRASHES:
 * adb shell rm -rf /sdcard/Android/data/com.dearmoon.shield.ransim/
 *
 * FILTER LOGS:
 * adb logcat -s SHIELD_RANSIM
 */
package com.dearmoon.shield.ransim;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SHIELD_RANSIM";
    private static final int REQ_MANAGE_STORAGE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_POST_NOTIF = 1003;

    private ViewGroup permissionContainer, scenarioContainer;
    private View permissionCard, scenarioHeader, btnStopAll;
    private Button btnGrantStorage, btnGrantOverlay, btnGrantNotif;
    private TextView storageStatus, overlayStatus, notifStatus, statusBar, logPanel;

    private final int MAX_LOG_LINES = 50;
    private final LinkedList<String> logBuffer = new LinkedList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind Views
        permissionCard = findViewById(R.id.permission_card);
        permissionContainer = findViewById(R.id.permission_container);
        scenarioHeader = findViewById(R.id.scenario_header);
        scenarioContainer = findViewById(R.id.scenario_container);
        statusBar = findViewById(R.id.status_bar);
        logPanel = findViewById(R.id.log_panel);
        btnStopAll = findViewById(R.id.btn_stop_all);

        findViewById(R.id.btn_reset).setOnClickListener(v -> resetEnvironment());
        btnStopAll.setOnClickListener(v -> stopAll());

        setupPermissionRows();
        updatePermissionStatus();
    }

    private void setupPermissionRows() {
        // We reuse the container but make the rows pretty
        permissionContainer.removeAllViews();
        
        TextView title = new TextView(this);
        title.setText("Safety Checklist");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, 16);
        permissionContainer.addView(title);

        storageStatus = addPermissionRow("Storage Access", v -> requestManageStorage());
        overlayStatus = addPermissionRow("Overlay Permission", v -> requestOverlayPermission());
        notifStatus = addPermissionRow("Notification Access", v -> requestNotifPermission());
    }

    private TextView addPermissionRow(String label, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8, 0, 8);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(getResources().getColor(R.color.text_secondary));
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        
        TextView status = new TextView(this);
        status.setText("CHECKING");
        status.setPadding(16, 0, 16, 0);
        
        MaterialButton btn = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        btn.setText("GRANT");
        btn.setTextColor(getResources().getColor(R.color.primary));
        btn.setOnClickListener(listener);

        row.addView(name);
        row.addView(status);
        row.addView(btn);
        permissionContainer.addView(row);
        
        return status;
    }

    private void updatePermissionStatus() {
        boolean storageGranted = hasManageStorage();
        boolean overlayGranted = hasOverlayPermission();
        boolean notifGranted = hasNotifPermission();

        updateStatusText(storageStatus, storageGranted);
        updateStatusText(overlayStatus, overlayGranted);
        updateStatusText(notifStatus, notifGranted);

        if (storageGranted && overlayGranted && notifGranted) {
            permissionCard.setVisibility(View.GONE);
            scenarioHeader.setVisibility(View.VISIBLE);
            scenarioContainer.setVisibility(View.VISIBLE);
            if (scenarioContainer.getChildCount() == 0) addScenarioCards();
        } else {
            permissionCard.setVisibility(View.VISIBLE);
            scenarioHeader.setVisibility(View.GONE);
            scenarioContainer.setVisibility(View.GONE);
        }
    }

    private void updateStatusText(TextView tv, boolean granted) {
        tv.setText(granted ? "READY" : "MISSING");
        tv.setTextColor(getResources().getColor(granted ? R.color.accent_green : R.color.accent_red));
        ViewGroup parent = (ViewGroup) tv.getParent();
        parent.getChildAt(parent.getChildCount() - 1).setEnabled(!granted);
    }

    private void addScenarioCards() {
        scenarioContainer.removeAllViews();
        
        addScenario(
                "Crypto Ransomware",
                "Encrypts sandbox files using XOR. Mimics SOVA/Cerber patterns.",
                v -> startCryptoScenario()
        );
        addScenario(
                "Locker UI",
                "Simulates a full-screen block. Test password: 1234.",
                v -> startLockerScenario()
        );
        addScenario(
                "Full Hybrid Attack",
                "Lock + Encryption + C2. The most aggressive chain.",
                v -> startHybridScenario()
        );
        addScenario(
                "Evasive Reconnaissance",
                "Slow scans followed by rapid encryption. Tests SPRT thresholds.",
                v -> startReconScenario()
        );
    }

    private void addScenario(String title, String desc, View.OnClickListener listener) {
        View card = getLayoutInflater().inflate(R.layout.item_scenario, scenarioContainer, false);
        ((TextView) card.findViewById(R.id.scenario_title)).setText(title);
        ((TextView) card.findViewById(R.id.scenario_description)).setText(desc);
        Button btn = card.findViewById(R.id.scenario_button);
        btn.setText("LAUNCH TEST");
        btn.setOnClickListener(listener);
        scenarioContainer.addView(card);
    }

    private boolean hasManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestManageStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivityForResult(intent, REQ_MANAGE_STORAGE);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_MANAGE_STORAGE);
        }
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_OVERLAY);
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updatePermissionStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissionStatus();
    }

    // --- Locker Ransomware Scenario ---
    private void startLockerScenario() {
        log("Starting LOCKER scenario...");
        statusBar.setText("STATUS: RUNNING (LOCKER)");
        btnStopAll.setVisibility(View.VISIBLE);
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.LOCKER;
        simState.isRunning = true;
        Intent svc = new Intent(this, OverlayService.class);
        ContextCompat.startForegroundService(this, svc);
        sendBroadcast(new Intent("com.dearmoon.shield.ransim.LOCKER_ACTIVE"));
    }

    // --- Hybrid Attack Scenario ---
    private void startHybridScenario() {
        log("Starting HYBRID attack...");
        statusBar.setText("STATUS: RUNNING (HYBRID)");
        btnStopAll.setVisibility(View.VISIBLE);
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.HYBRID;
        simState.isRunning = true;
        simState.startTimeMs = System.currentTimeMillis();
        simulator.seedTestFiles(getSandboxRoot(), this::log);
        
        Thread encryptThread = new Thread(() -> {
            try {
                List<File> files = simulator.collectTargetFiles(getSandboxRoot());
                for (File f : files) {
                    if (!simState.isRunning) break;
                    if (!validatePath(f)) continue;
                    byte[] orig = simulator.readFileBytes(f);
                    simState.originalFiles.put(f.getCanonicalPath(), orig);
                    File encFile = new File(f.getAbsolutePath() + ".enc");
                    simulator.xorEncryptToFile(orig, encFile);
                    f.delete();
                    runOnUiThread(() -> log("ENCRYPTED: " + encFile.getName()));
                    Thread.sleep(100);
                }
                simulator.writeRansomNote(new File(getSandboxRoot(), "RANSOM_NOTE.txt"));
            } catch (Exception e) { log("[ERR] " + e.getMessage()); }
        });
        simState.activeThreads.add(encryptThread);
        encryptThread.start();
        
        new Handler(Looper.getMainLooper()).postDelayed(this::startLockerScenario, 2000);
        
        Thread c2Thread = new Thread(() -> {
            int[] ports = {4444, 6666, 8888};
            for (int port : ports) {
                if (!simState.isRunning) break;
                simulator.simulateC2(this::log);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        });
        simState.activeThreads.add(c2Thread);
        c2Thread.start();
        sendBroadcast(new Intent("com.dearmoon.shield.ransim.HYBRID_ACTIVE"));
    }

    // --- Recon → Encrypt Scenario ---
    private void startReconScenario() {
        log("Starting RECON scan...");
        statusBar.setText("STATUS: SCANNING FILES");
        btnStopAll.setVisibility(View.VISIBLE);
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.RECON;
        simState.isRunning = true;
        simulator.seedTestFiles(getSandboxRoot(), this::log);
        
        Thread reconThread = new Thread(() -> {
            try {
                List<File> files = simulator.collectTargetFiles(getSandboxRoot());
                for (File f : files) {
                    if (!simState.isRunning) break;
                    log("READ: " + f.getName());
                    Thread.sleep(500);
                }
                log("RECON COMPLETE. Starting encryption...");
                runOnUiThread(() -> statusBar.setText("STATUS: RAPID ENCRYPTION"));
                Thread.sleep(2000);
                for (File f : files) {
                    if (!simState.isRunning) break;
                    if (!validatePath(f)) continue;
                    byte[] orig = simulator.readFileBytes(f);
                    simState.originalFiles.put(f.getCanonicalPath(), orig);
                    File encFile = new File(f.getAbsolutePath() + ".enc");
                    simulator.xorEncryptToFile(orig, encFile);
                    f.delete();
                    runOnUiThread(() -> log("ENCRYPTED: " + encFile.getName()));
                    Thread.sleep(150);
                }
                simulator.writeRansomNote(new File(getSandboxRoot(), "RANSOM_NOTE.txt"));
            } catch (Exception e) { log("[ERR] " + e.getMessage()); }
        });
        simState.activeThreads.add(reconThread);
        reconThread.start();
    }

    // --- STOP/RESTORE ---
    private void stopAll() {
        log("Stopping tests & restoring sandbox...");
        simState.isRunning = false;
        for (Thread t : simState.activeThreads) t.interrupt();
        simState.activeThreads.clear();
        stopService(new Intent(this, OverlayService.class));
        
        if (simState.wakeLock != null && simState.wakeLock.isHeld()) simState.wakeLock.release();
        
        for (Map.Entry<String, byte[]> entry : simState.originalFiles.entrySet()) {
            try {
                File orig = new File(entry.getKey());
                if (!validatePath(orig)) continue;
                try (FileOutputStream fos = new FileOutputStream(orig)) {
                    fos.write(entry.getValue());
                }
                new File(orig.getAbsolutePath() + ".enc").delete();
            } catch (Exception e) { log("[ERR] Restore: " + e.getMessage()); }
        }
        new File(getSandboxRoot(), "RANSOM_NOTE.txt").delete();
        simState.reset();
        log("Cleanup finished. System restored.");
        sendBroadcast(new Intent("com.dearmoon.shield.ransim.CLEANUP_COMPLETE"));
        runOnUiThread(() -> {
            statusBar.setText("STATUS: IDLE");
            btnStopAll.setVisibility(View.GONE);
            Toast.makeText(this, "✅ Cleaned & Restored", Toast.LENGTH_SHORT).show();
        });
    }

    private void resetEnvironment() {
        log("Resetting sandbox...");
        stopAll();
        simulator.clearSandbox(getSandboxRoot(), this::log);
        simulator.seedTestFiles(getSandboxRoot(), this::log);
        Toast.makeText(this, "Environment Reset", Toast.LENGTH_SHORT).show();
    }

    private BroadcastReceiver shieldReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.dearmoon.shield.HIGH_RISK_ALERT".equals(action)) {
                log("⚠️ SHIELD DETECTED ATTACK!");
                statusBar.setText("⚠️ ATTACK BLOCKED BY SHIELD");
                new Handler(Looper.getMainLooper()).postDelayed(MainActivity.this::stopAll, 2000);
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction("com.dearmoon.shield.HIGH_RISK_ALERT");
        f.addAction("com.dearmoon.shield.EMERGENCY_MODE");
        f.addAction("com.dearmoon.shield.RESTORE_COMPLETE");
        registerReceiver(shieldReceiver, f);
        updatePermissionStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(shieldReceiver);
    }

    private File getSandboxRoot() {
        File root = new File(getExternalFilesDir(null), "shield_ransim_sandbox");
        if (!root.exists()) {
            boolean created = root.mkdirs();
            if (!created) log("[WRN] Failed to create sandbox root directory");
        }
        return root;
    }
    private SimulationState simState = new SimulationState();
    private RansomwareSimulator simulator = new RansomwareSimulator();

    private void startCryptoScenario() {
        log("Launching CRYPTO scenario...");
        statusBar.setText("STATUS: RUNNING (CRYPTO)");
        btnStopAll.setVisibility(View.VISIBLE);
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.CRYPTO;
        simState.isRunning = true;
        simulator.seedTestFiles(getSandboxRoot(), this::log);
        
        Thread encryptThread = new Thread(() -> {
            try {
                List<File> files = simulator.collectTargetFiles(getSandboxRoot());
                for (File f : files) {
                    if (!simState.isRunning) break;
                    if (!validatePath(f)) continue;
                    byte[] orig = simulator.readFileBytes(f);
                    simState.originalFiles.put(f.getCanonicalPath(), orig);
                    File encFile = new File(f.getAbsolutePath() + ".enc");
                    simulator.xorEncryptToFile(orig, encFile);
                    f.delete();
                    runOnUiThread(() -> log("ENCRYPTED: " + encFile.getName()));
                    Thread.sleep(200);
                }
                simulator.writeRansomNote(new File(getSandboxRoot(), "RANSOM_NOTE.txt"));
                simulator.simulateC2(this::log);
                runOnUiThread(() -> {
                    statusBar.setText("STATUS: COMPLETED");
                    btnStopAll.setVisibility(View.GONE);
                });
            } catch (Exception e) { log("[ERR] " + e.getMessage()); }
        });
        simState.activeThreads.add(encryptThread);
        encryptThread.start();
    }

    private boolean validatePath(File f) {
        try {
            String canon = f.getCanonicalPath();
            return canon.startsWith(getSandboxRoot().getAbsolutePath());
        } catch (IOException e) { return false; }
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            if (logBuffer.size() >= MAX_LOG_LINES) logBuffer.removeFirst();
            logBuffer.add("> " + msg);
            StringBuilder sb = new StringBuilder();
            for (String line : logBuffer) sb.append(line).append("\n");
            if (logPanel != null) logPanel.setText(sb.toString());
        });
        Log.d(TAG, msg);
    }
}
