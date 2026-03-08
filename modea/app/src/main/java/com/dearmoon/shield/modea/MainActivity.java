package com.dearmoon.shield.modea;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dearmoon.shield.modea.stub.UnifiedDetectionEngine;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MainActivity — debug UI for the standalone Mode-A APK.
 *
 * Lets the tester start/stop ModeAService and watch live events
 * (from UnifiedDetectionEngine.EVENT_LOG) scroll by in a text view.
 */
public class MainActivity extends AppCompatActivity {

    private static final int    REQ_NOTIF   = 1;
    private static final int    REQ_STORAGE = 2;
    private static final long   UI_INTERVAL = 1_000L;    // refresh every 1 s
    private static final String TS_FORMAT   = "HH:mm:ss";

    private TextView   tvStatus;
    private Button     btnStart;
    private Button     btnStop;
    private Button     btnClear;
    private ScrollView scrollEvents;
    private TextView   tvEvents;
    private TextView   tvEventCount;

    private final Handler   uiHandler   = new Handler(Looper.getMainLooper());
    private       boolean   uiRunning   = false;
    private       int       lastLogSize = 0;

    private final SimpleDateFormat tsFormat =
            new SimpleDateFormat(TS_FORMAT, Locale.getDefault());

    // ------------------------------------------------------------------
    // BroadcastReceiver for service status
    // ------------------------------------------------------------------
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ModeAService.ACTION_STARTED.equals(action)) {
                setStatus("Mode-A RUNNING",
                        getResources().getColor(R.color.colorRunning, getTheme()));
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
            } else if (ModeAService.ACTION_UNAVAILABLE.equals(action)) {
                String reason = intent.getStringExtra("reason");
                setStatus("UNAVAILABLE: " + reason,
                        getResources().getColor(R.color.colorError, getTheme()));
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
            }
        }
    };

    // ------------------------------------------------------------------
    // Activity lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus      = findViewById(R.id.tvStatus);
        btnStart      = findViewById(R.id.btnStart);
        btnStop       = findViewById(R.id.btnStop);
        btnClear      = findViewById(R.id.btnClear);
        scrollEvents  = findViewById(R.id.scrollEvents);
        tvEvents      = findViewById(R.id.tvEvents);
        tvEventCount  = findViewById(R.id.tvEventCount);

        btnStop.setEnabled(false);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v  -> onStopClicked());
        btnClear.setOnClickListener(v -> onClearClicked());

        requestRequiredPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter f = new IntentFilter();
        f.addAction(ModeAService.ACTION_STARTED);
        f.addAction(ModeAService.ACTION_UNAVAILABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }

        startUiRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUiRefresh();
        unregisterReceiver(statusReceiver);
    }

    // ------------------------------------------------------------------
    // Button handlers
    // ------------------------------------------------------------------

    private void onStartClicked() {
        setStatus("Starting…",
                getResources().getColor(R.color.colorIdle, getTheme()));
        btnStart.setEnabled(false);
        Intent i = new Intent(this, ModeAService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void onStopClicked() {
        stopService(new Intent(this, ModeAService.class));
        setStatus("Stopped",
                getResources().getColor(R.color.colorIdle, getTheme()));
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void onClearClicked() {
        UnifiedDetectionEngine.EVENT_LOG.clear();
        tvEvents.setText("");
        tvEventCount.setText("Events: 0");
        lastLogSize = 0;
    }

    // ------------------------------------------------------------------
    // Live event display
    // ------------------------------------------------------------------

    private void startUiRefresh() {
        uiRunning = true;
        scheduleRefresh();
    }

    private void stopUiRefresh() {
        uiRunning = false;
        uiHandler.removeCallbacksAndMessages(null);
    }

    private void scheduleRefresh() {
        if (uiRunning) uiHandler.postDelayed(this::refreshEvents, UI_INTERVAL);
    }

    private void refreshEvents() {
        List<String> log = UnifiedDetectionEngine.EVENT_LOG;
        int size = log.size();
        if (size != lastLogSize) {
            StringBuilder sb = new StringBuilder();
            for (String entry : log) sb.append(entry).append('\n');
            String text = sb.toString();
            tvEvents.setText(text);
            tvEventCount.setText("Events: " + size);
            scrollEvents.post(() ->
                    scrollEvents.fullScroll(View.FOCUS_DOWN));
            lastLogSize = size;
        }
        scheduleRefresh();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void setStatus(String msg, int color) {
        tvStatus.setText(msg);
        tvStatus.setTextColor(color);
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private void requestRequiredPermissions() {
        // POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
        }

        // MANAGE_EXTERNAL_STORAGE (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // No hard dependency — permissions are informational for this debug APK
    }
}
