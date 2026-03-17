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

    private ViewGroup rootLayout;
    private Button startButton;
    private Button grantStorageButton, grantOverlayButton, grantNotifButton;
    private TextView storageStatus, overlayStatus, notifStatus;

    // Main UI elements
    private LinearLayout scenarioLayout;
    private TextView statusBar;
    private Button stopAllButton;
    private TextView logPanel;
    private int logLines = 0;
    private final int MAX_LOG_LINES = 50;
    private final LinkedList<String> logBuffer = new LinkedList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootLayout = (ViewGroup) findViewById(android.R.id.content);
        LinearLayout mainLayout = (LinearLayout) findViewById(R.id.main_content_root);

        Log.d(TAG, "onCreate: rootLayout=" + rootLayout + ", mainLayout=" + mainLayout);

        if (mainLayout == null) {
            Log.e(TAG, "CRITICAL ERROR: main_content_root NOT FOUND in activity_main.xml");
            // Fallback to child(0) if ID search fails
            if (rootLayout != null && rootLayout.getChildCount() > 0) {
                View firstChild = rootLayout.getChildAt(0);
                if (firstChild instanceof LinearLayout) {
                    mainLayout = (LinearLayout) firstChild;
                    Log.d(TAG, "Fallback: Using first child as mainLayout");
                }
            }
        }

        if (mainLayout != null) {
            // Add permissions checklist UI
            addPermissionChecklist(mainLayout);
            updatePermissionStatus();
        } else {
            Log.e(TAG, "CRITICAL ERROR: Could not initialize UI. rootLayout children: " + 
                (rootLayout != null ? rootLayout.getChildCount() : "rootLayout is NULL"));
        }
    }

    private void addPermissionChecklist(LinearLayout parent) {
        // Checklist title
        TextView checklistTitle = new TextView(this);
        checklistTitle.setText("Permissions required:");
        checklistTitle.setTextColor(getResources().getColor(R.color.white));
        checklistTitle.setTextSize(18);
        checklistTitle.setPadding(0, 32, 0, 8);
        parent.addView(checklistTitle);

        // Storage
        LinearLayout storageRow = new LinearLayout(this);
        storageRow.setOrientation(LinearLayout.HORIZONTAL);
        storageStatus = new TextView(this);
        storageStatus.setText("Checking...");
        storageStatus.setTextColor(getResources().getColor(R.color.white));
        grantStorageButton = new Button(this);
        grantStorageButton.setText("Grant");
        grantStorageButton.setOnClickListener(v -> requestManageStorage());
        storageRow.addView(storageStatus);
        storageRow.addView(grantStorageButton);
        parent.addView(storageRow);

        // Overlay
        LinearLayout overlayRow = new LinearLayout(this);
        overlayRow.setOrientation(LinearLayout.HORIZONTAL);
        overlayStatus = new TextView(this);
        overlayStatus.setText("Checking...");
        overlayStatus.setTextColor(getResources().getColor(R.color.white));
        grantOverlayButton = new Button(this);
        grantOverlayButton.setText("Grant");
        grantOverlayButton.setOnClickListener(v -> requestOverlayPermission());
        overlayRow.addView(overlayStatus);
        overlayRow.addView(grantOverlayButton);
        parent.addView(overlayRow);

        // Notifications
        LinearLayout notifRow = new LinearLayout(this);
        notifRow.setOrientation(LinearLayout.HORIZONTAL);
        notifStatus = new TextView(this);
        notifStatus.setText("Checking...");
        notifStatus.setTextColor(getResources().getColor(R.color.white));
        grantNotifButton = new Button(this);
        grantNotifButton.setText("Grant");
        grantNotifButton.setOnClickListener(v -> requestNotifPermission());
        notifRow.addView(notifStatus);
        notifRow.addView(grantNotifButton);
        parent.addView(notifRow);

        // Start button
        startButton = new Button(this);
        startButton.setText("All permissions granted — START");
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> onAllPermissionsGranted());
        parent.addView(startButton);
    }

    private void updatePermissionStatus() {
        boolean storageGranted = hasManageStorage();
        boolean overlayGranted = hasOverlayPermission();
        boolean notifGranted = hasNotifPermission();

        storageStatus.setText(storageGranted ? "✅ Granted" : "❌ Missing");
        overlayStatus.setText(overlayGranted ? "✅ Granted" : "❌ Missing");
        notifStatus.setText(notifGranted ? "✅ Granted" : "❌ Missing");

        grantStorageButton.setEnabled(!storageGranted);
        grantOverlayButton.setEnabled(!overlayGranted);
        grantNotifButton.setEnabled(!notifGranted);

        startButton.setEnabled(storageGranted && overlayGranted && notifGranted);
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

    private void onAllPermissionsGranted() {
        Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
        showMainScenarioUI();
    }

    private void showMainScenarioUI() {
        rootLayout.removeAllViews();
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(getResources().getColor(R.color.dark_bg));
        main.setPadding(16, 16, 16, 16);

        // Header
        TextView title = new TextView(this);
        title.setText("SHIELD RanSim");
        title.setTextColor(getResources().getColor(R.color.red));
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, Typeface.BOLD);
        main.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ransomware Behaviour Simulator — Security Research Only");
        subtitle.setTextColor(getResources().getColor(R.color.white));
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        main.addView(subtitle);

        // Status bar
        statusBar = new TextView(this);
        statusBar.setText("IDLE");
        statusBar.setTextColor(getResources().getColor(R.color.yellow));
        statusBar.setTextSize(16);
        statusBar.setGravity(Gravity.CENTER);
        statusBar.setPadding(0, 8, 0, 8);
        main.addView(statusBar);

        // Scenario cards
        scenarioLayout = new LinearLayout(this);
        scenarioLayout.setOrientation(LinearLayout.VERTICAL);
        scenarioLayout.setPadding(0, 16, 0, 16);
        addScenarioCards();
        main.addView(scenarioLayout);

        // STOP ALL button
        stopAllButton = new Button(this);
        stopAllButton.setText("⏹ STOP ALL & RESTORE");
        stopAllButton.setBackgroundColor(getResources().getColor(R.color.red));
        stopAllButton.setTextColor(getResources().getColor(R.color.white));
        stopAllButton.setTextSize(18);
        stopAllButton.setOnClickListener(v -> stopAll());
        main.addView(stopAllButton);

        // RESET button
        Button resetBtn = new Button(this);
        resetBtn.setText("♻️ RESET ENVIRONMENT");
        resetBtn.setOnClickListener(v -> resetEnvironment());
        main.addView(resetBtn);

        // Log panel
        logPanel = new TextView(this);
        logPanel.setTextColor(getResources().getColor(R.color.white));
        logPanel.setTypeface(Typeface.MONOSPACE);
        logPanel.setTextSize(12);
        logPanel.setMaxLines(MAX_LOG_LINES);
        logPanel.setVerticalScrollBarEnabled(true);
        logPanel.setPadding(0, 16, 0, 0);
        main.addView(logPanel);

        rootLayout.addView(main);
        log("Main scenario UI loaded");
    }

    private void addScenarioCards() {
        scenarioLayout.removeAllViews();
        // Card 1: Crypto Ransomware
        scenarioLayout.addView(makeScenarioCard(
                "CRYPTO RANSOMWARE",
                "Encrypts test files at ~5 files/sec using XOR cipher.\nMimics SOVA v5 / Cerber file encryption behaviour.\nTargets: sandbox/documents/, sandbox/photos/, sandbox/notes/",
                getResources().getColor(R.color.orange),
                "▶ START CRYPTO",
                v -> startCryptoScenario()
        ));
        // Card 2: Locker Ransomware
        scenarioLayout.addView(makeScenarioCard(
                "LOCKER RANSOMWARE",
                "Displays full-screen overlay simulating a screen locker.\nMimics Android/Koler, Svpeng locker behaviour.\nPassword is always visible on screen. Press STOP to exit.",
                getResources().getColor(R.color.red),
                "▶ START LOCKER",
                v -> startLockerScenario()
        ));
        // Card 3: Hybrid Attack
        scenarioLayout.addView(makeScenarioCard(
                "HYBRID ATTACK",
                "Simultaneous encryption + screen lock + C2 simulation.\nMimics SOVA full attack chain. Most aggressive scenario.\nTests all SHIELD detection layers at once.",
                getResources().getColor(R.color.dark_red),
                "▶ START HYBRID",
                v -> startHybridScenario()
        ));
        // Card 4: Recon → Encrypt
        scenarioLayout.addView(makeScenarioCard(
                "RECON → ENCRYPT",
                "30 seconds of slow file reconnaissance, then rapid encryption.\nTests SHIELD's SPRT threshold transition.\nWatch SHIELD's score climb from ~10 to 130.",
                getResources().getColor(R.color.yellow),
                "▶ START RECON",
                v -> startReconScenario()
        ));
    }

    // --- Locker Ransomware Scenario ---
    private void startLockerScenario() {
        log("Starting LOCKER RANSOMWARE scenario");
        statusBar.setText("RUNNING: LOCKER RANSOMWARE");
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.LOCKER;
        simState.isRunning = true;
        Intent svc = new Intent(this, OverlayService.class);
        ContextCompat.startForegroundService(this, svc);
        // Send broadcast
        sendBroadcast(new Intent("com.dearmoon.shield.ransim.LOCKER_ACTIVE"));
    }

    // --- Hybrid Attack Scenario ---
    private void startHybridScenario() {
        log("Starting HYBRID ATTACK scenario");
        statusBar.setText("RUNNING: HYBRID ATTACK");
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.HYBRID;
        simState.isRunning = true;
        simState.startTimeMs = System.currentTimeMillis();
        // Setup phase
        simulator.seedTestFiles(new File(SANDBOX_ROOT), this::log);
        // Thread A: Encryption (100ms delay)
        Thread encryptThread = new Thread(() -> {
            try {
                List<File> files = simulator.collectTargetFiles(new File(SANDBOX_ROOT));
                simState.filesTotal = files.size();
                simState.filesEncrypted = 0;
                for (File f : files) {
                    if (!simState.isRunning) break;
                    if (!validatePath(f)) continue;
                    byte[] orig = simulator.readFileBytes(f);
                    simState.originalFiles.put(f.getCanonicalPath(), orig);
                    File encFile = new File(f.getAbsolutePath() + ".enc");
                    simulator.xorEncryptToFile(orig, encFile);
                    f.delete();
                    simState.filesEncrypted++;
                    runOnUiThread(() -> log("SIM_FILE_ENCRYPTED path=" + encFile.getName() + " size=" + orig.length));
                    Thread.sleep(100);
                }
                File ransomNote = new File(SANDBOX_ROOT, "RANSOM_NOTE.txt");
                simulator.writeRansomNote(ransomNote);
            } catch (Exception e) { log("[ERROR] " + e.getMessage()); }
        });
        simState.activeThreads.add(encryptThread);
        encryptThread.start();
        // Thread B: Locker overlay (after 3s)
        new Handler(Looper.getMainLooper()).postDelayed(() -> startLockerScenario(), 3000);
        // Thread C: C2 simulation (every 2s for 30s)
        Thread c2Thread = new Thread(() -> {
            int[] ports = {4444, 6666, 8888};
            for (int port : ports) {
                if (!simState.isRunning) break;
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                    log("SIM_C2_ATTEMPT port=" + port + " result=connected");
                } catch (IOException e) {
                    log("SIM_C2_ATTEMPT port=" + port + " result=refused");
                }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        });
        simState.activeThreads.add(c2Thread);
        c2Thread.start();
        sendBroadcast(new Intent("com.dearmoon.shield.ransim.HYBRID_ACTIVE"));
    }

    // --- Recon → Encrypt Scenario ---
    private void startReconScenario() {
        log("Starting RECON → ENCRYPT scenario");
        statusBar.setText("RUNNING: RECON → ENCRYPT");
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.RECON;
        simState.isRunning = true;
        simState.startTimeMs = System.currentTimeMillis();
        simulator.seedTestFiles(new File(SANDBOX_ROOT), this::log);
        Thread reconThread = new Thread(() -> {
            try {
                List<File> files = simulator.collectTargetFiles(new File(SANDBOX_ROOT));
                int found = 0;
                for (File f : files) {
                    if (!simState.isRunning) break;
                    boolean readable = f.canRead();
                    log("SIM_RECON_FILE: " + f.getName() + " size=" + f.length() + " readable=" + readable);
                    found++;
                    Thread.sleep(500);
                }
                log("SIM_RECON_COMPLETE: found " + found + " files, starting encryption in 5s");
                runOnUiThread(() -> statusBar.setText("Reconnaissance complete — preparing encryption..."));
                Thread.sleep(5000);
                // Start encryption (150ms delay)
                for (File f : files) {
                    if (!simState.isRunning) break;
                    if (!validatePath(f)) continue;
                    byte[] orig = simulator.readFileBytes(f);
                    simState.originalFiles.put(f.getCanonicalPath(), orig);
                    File encFile = new File(f.getAbsolutePath() + ".enc");
                    simulator.xorEncryptToFile(orig, encFile);
                    f.delete();
                    simState.filesEncrypted++;
                    runOnUiThread(() -> log("SIM_FILE_ENCRYPTED path=" + encFile.getName() + " size=" + orig.length));
                    Thread.sleep(150);
                }
                File ransomNote = new File(SANDBOX_ROOT, "RANSOM_NOTE.txt");
                simulator.writeRansomNote(ransomNote);
            } catch (Exception e) { log("[ERROR] " + e.getMessage()); }
        });
        simState.activeThreads.add(reconThread);
        reconThread.start();
    }

    // --- STOP/RESTORE logic ---
    private void stopAll() {
        log("STOP ALL & RESTORE triggered");
        simState.isRunning = false;
        for (Thread t : simState.activeThreads) t.interrupt();
        simState.activeThreads.clear();
        // Stop overlay
        stopService(new Intent(this, OverlayService.class));
        // Release wakelock if held
        if (simState.wakeLock != null && simState.wakeLock.isHeld()) simState.wakeLock.release();
        simState.wakeLock = null;
        // Restore files
        for (Map.Entry<String, byte[]> entry : simState.originalFiles.entrySet()) {
            try {
                File orig = new File(entry.getKey());
                if (!validatePath(orig)) continue;
                try (FileOutputStream fos = new FileOutputStream(orig)) {
                    fos.write(entry.getValue());
                }
                File enc = new File(orig.getAbsolutePath() + ".enc");
                if (enc.exists()) enc.delete();
            } catch (Exception e) { log("[ERROR] Restore: " + e.getMessage()); }
        }
        // Delete ransom note
        File ransomNote = new File(SANDBOX_ROOT, "RANSOM_NOTE.txt");
        if (ransomNote.exists()) ransomNote.delete();
        simState.reset();
        log("SHIELD_RANSIM: CLEANUP_COMPLETE all files restored");
        sendBroadcast(new Intent("com.dearmoon.shield.ransim.CLEANUP_COMPLETE"));
        runOnUiThread(() -> Toast.makeText(this, "✅ All files restored", Toast.LENGTH_SHORT).show());
        statusBar.setText("IDLE");
    }

    private void resetEnvironment() {
        log("Resetting environment...");
        stopAll();
        simulator.clearSandbox(new File(SANDBOX_ROOT), this::log);
        simulator.seedTestFiles(new File(SANDBOX_ROOT), this::log);
        Toast.makeText(this, "Environment Reset Complete", Toast.LENGTH_SHORT).show();
    }

    // --- SHIELD detection listener ---
    private BroadcastReceiver shieldReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.dearmoon.shield.HIGH_RISK_ALERT".equals(action)) {
                log("✅ SHIELD DETECTED! Score: " + intent.getIntExtra("score", 0));
                statusBar.setText("✅ SHIELD DETECTED!");
                // Stop simulation after 3s
                new Handler(Looper.getMainLooper()).postDelayed(() -> stopAll(), 3000);
            } else if ("com.dearmoon.shield.EMERGENCY_MODE".equals(action)) {
                log("EMERGENCY MODE triggered by SHIELD");
            } else if ("com.dearmoon.shield.RESTORE_COMPLETE".equals(action)) {
                log("SHIELD restore complete");
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(shieldReceiver);
    }

    // STOP button handler
    // (replace log in STOP ALL button with stopAll call)
    //
    // In showMainScenarioUI():
    // stopAllButton.setOnClickListener(v -> stopAll());

    // --- Crypto Ransomware Scenario ---
    private static final String SANDBOX_ROOT = "/sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/";
    private SimulationState simState = new SimulationState();
    private RansomwareSimulator simulator = new RansomwareSimulator();

    private void startCryptoScenario() {
        log("Starting CRYPTO RANSOMWARE scenario");
        statusBar.setText("RUNNING: CRYPTO RANSOMWARE");
        simState.reset();
        simState.activeScenario = SimulationState.Scenario.CRYPTO;
        simState.isRunning = true;
        simState.startTimeMs = System.currentTimeMillis();
        // Seed files if needed
        runOnUiThread(() -> log("Seeding test files..."));
        simulator.seedTestFiles(new File(SANDBOX_ROOT), this::log);
        // Start encryption thread
        Thread encryptThread = new Thread(() -> {
            try {
                List<File> files = simulator.collectTargetFiles(new File(SANDBOX_ROOT));
                simState.filesTotal = files.size();
                simState.filesEncrypted = 0;
                for (File f : files) {
                    if (!simState.isRunning) break;
                    if (!validatePath(f)) {
                        log("[ERROR] File outside sandbox: " + f.getAbsolutePath());
                        continue;
                    }
                    byte[] orig = simulator.readFileBytes(f);
                    simState.originalFiles.put(f.getCanonicalPath(), orig);
                    File encFile = new File(f.getAbsolutePath() + ".enc");
                    simulator.xorEncryptToFile(orig, encFile);
                    f.delete();
                    simState.filesEncrypted++;
                    runOnUiThread(() -> log("SIM_FILE_ENCRYPTED path=" + encFile.getName() + " size=" + orig.length));
                    Thread.sleep(200);
                }
                // Drop ransom note
                File ransomNote = new File(SANDBOX_ROOT, "RANSOM_NOTE.txt");
                simulator.writeRansomNote(ransomNote);
                // Simulate C2
                simulator.simulateC2(this::log);
                // Broadcast complete
                sendBroadcast(new Intent("com.dearmoon.shield.ransim.CRYPTO_COMPLETE"));
                runOnUiThread(() -> statusBar.setText("STOPPED"));
            } catch (Exception e) {
                log("[ERROR] " + e.getMessage());
            }
        });
        simState.activeThreads.add(encryptThread);
        encryptThread.start();
    }

    private boolean validatePath(File f) {
        try {
            String canon = f.getCanonicalPath();
            if (!canon.startsWith(SANDBOX_ROOT)) {
                Log.e(TAG, "[SECURITY] File outside sandbox: " + canon);
                throw new IllegalArgumentException("File outside sandbox: " + canon);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "[SECURITY] Path validation failed: " + e.getMessage());
            return false;
        }
    }

    private CardView makeScenarioCard(String title, String desc, int accentColor, String buttonText, View.OnClickListener onClick) {
        CardView card = new CardView(this);
        card.setCardBackgroundColor(accentColor);
        card.setRadius(16);
        card.setUseCompatPadding(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.white));
        t.setTextSize(20);
        t.setTypeface(null, Typeface.BOLD);
        layout.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(getResources().getColor(R.color.white));
        d.setTextSize(14);
        d.setPadding(0, 8, 0, 8);
        layout.addView(d);
        Button b = new Button(this);
        b.setText(buttonText);
        b.setOnClickListener(onClick);
        layout.addView(b);
        card.addView(layout);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 24);
        card.setLayoutParams(lp);
        return card;
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            if (logBuffer.size() >= MAX_LOG_LINES) logBuffer.removeFirst();
            logBuffer.add(msg);
            StringBuilder sb = new StringBuilder();
            for (String line : logBuffer) sb.append(line).append("\n");
            if (logPanel != null) {
                logPanel.setText(sb.toString());
            }
        });
        Log.d(TAG, msg);
    }
}
