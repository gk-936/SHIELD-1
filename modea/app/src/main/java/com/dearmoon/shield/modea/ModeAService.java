package com.dearmoon.shield.modea;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.dearmoon.shield.modea.stub.SnapshotManager;
import com.dearmoon.shield.modea.stub.UnifiedDetectionEngine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModeAService — Android ForegroundService that drives Mode-A.
 *
 * Standalone APK version: uses stub classes from .stub package.
 * No dependency on the main SHIELD app packages.
 *
 * -----------------------------------------------------------------------
 * MERGE NOTE — when integrating into the main SHIELD project:
 *   Replace:
 *     import com.dearmoon.shield.modea.stub.SnapshotManager;
 *     import com.dearmoon.shield.modea.stub.UnifiedDetectionEngine;
 *   With:
 *     import com.dearmoon.shield.snapshot.SnapshotManager;
 *     import com.dearmoon.shield.detection.UnifiedDetectionEngine;
 *   Pass the shared engine/manager instances from the main service.
 * -----------------------------------------------------------------------
 */
public class ModeAService extends Service {

    private static final String TAG              = "SHIELD_MODE_A";
    private static final String CHANNEL_ID       = "shield_modea_channel";
    private static final int    NOTIF_ID         = 9001;
    private static final long   POLL_INTERVAL_MS = 20L;

    public static final String ACTION_STARTED     = "com.dearmoon.shield.modea.STARTED";
    public static final String ACTION_UNAVAILABLE = "com.dearmoon.shield.modea.UNAVAILABLE";
    public static final String ACTION_KILL_PID    = "com.dearmoon.shield.modea.KILL_PID";

    // ------------------------------------------------------------------
    private ModeAController       controller;
    private ModeAJni              jni;
    private ModeAFileCollector    collector;
    private UnifiedDetectionEngine detectionEngine;

    private HandlerThread        eventThread;
    private Handler              eventHandler;
    private final AtomicBoolean  running   = new AtomicBoolean(false);
    private final AtomicBoolean  connected = new AtomicBoolean(false);

    private final ShieldEventData reusableEvent = new ShieldEventData();

    private final BroadcastReceiver killReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_KILL_PID.equals(intent.getAction())) return;
            int pid = intent.getIntExtra("pid", -1);
            if (pid > 0 && jni != null) {
                Log.w(TAG, "Kill command received for PID " + pid);
                jni.nativeSendKill(pid);
            }
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        controller      = new ModeAController(this);
        jni             = new ModeAJni();
        SnapshotManager sm = new SnapshotManager(this);
        detectionEngine = new UnifiedDetectionEngine(this, sm);
        collector       = new ModeAFileCollector(detectionEngine);

        IntentFilter filter = new IntentFilter(ACTION_KILL_PID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(killReceiver, filter);
        }
        Log.i(TAG, "ModeAService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Mode-A initialising…"));
        new Thread(this::initModeA, "ModeAInit").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ModeAService stopping");
        stopEventLoop();
        if (jni != null)             jni.nativeDisconnect();
        if (controller != null)      controller.stopDaemon();
        if (detectionEngine != null) detectionEngine.shutdown();
        unregisterReceiver(killReceiver);
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------
    // Init sequence (background thread)
    // ------------------------------------------------------------------

    private void initModeA() {
        Log.i(TAG, "Checking root access…");
        if (!controller.isRootAvailable()) {
            Log.e(TAG, "Root not available — Mode-A disabled");
            broadcast(ACTION_UNAVAILABLE, "Mode A requires root access");
            stopSelf();
            return;
        }

        Log.i(TAG, "Checking kernel BPF compatibility…");
        if (!controller.isKernelCompatible()) {
            Log.e(TAG, "Kernel does not support eBPF tracepoints — Mode-A disabled");
            broadcast(ACTION_UNAVAILABLE, "Kernel does not support eBPF");
            stopSelf();
            return;
        }

        Log.i(TAG, "Deploying binaries from APK assets…");
        if (!controller.deployBinaries()) {
            Log.e(TAG, "Binary deployment failed");
            broadcast(ACTION_UNAVAILABLE, "Failed to deploy Mode-A binaries");
            stopSelf();
            return;
        }

        Log.i(TAG, "Starting root daemon…");
        if (!controller.startDaemon()) {
            broadcast(ACTION_UNAVAILABLE, "Root daemon failed to start");
            stopSelf();
            return;
        }

        // Poll for up to 10 s — BPF JIT compilation can be slow on first run.
        boolean socketReady = false;
        for (int i = 0; i < 20; i++) {          // 20 × 500 ms = 10 s
            sleep(500);
            if (controller.isDaemonRunning()) { socketReady = true; break; }
            Log.d(TAG, "Waiting for daemon socket… (" + (i + 1) + "/20)");
        }

        if (!socketReady) {
            String stderr = controller.readDaemonStderr();
            Log.e(TAG, "Daemon socket not found after 10 s. stderr: " + stderr);
            broadcast(ACTION_UNAVAILABLE, "Root daemon socket not found");
            stopSelf();
            return;
        }

        Log.i(TAG, "Connecting to daemon socket…");
        if (!jni.nativeConnect(ModeAController.SOCKET_PATH)) {
            Log.e(TAG, "JNI socket connect failed");
            broadcast(ACTION_UNAVAILABLE, "Could not connect to daemon socket");
            stopSelf();
            return;
        }

        connected.set(true);
        updateNotification("Mode-A active — kernel telemetry running");
        sendBroadcast(new Intent(ACTION_STARTED));
        Log.i(TAG, "Mode-A fully operational");

        startEventLoop();
    }

    // ------------------------------------------------------------------
    // Event poll loop
    // ------------------------------------------------------------------

    private void startEventLoop() {
        eventThread = new HandlerThread("ModeAEventLoop");
        eventThread.start();
        eventHandler = new Handler(eventThread.getLooper());
        running.set(true);
        scheduleNextPoll();
        Log.i(TAG, "Event poll loop started");
    }

    private void scheduleNextPoll() {
        if (running.get()) eventHandler.postDelayed(this::pollEvents, POLL_INTERVAL_MS);
    }

    private void pollEvents() {
        if (!running.get() || !connected.get()) return;
        try {
            boolean got;
            do {
                got = jni.nativeReadEvent(reusableEvent);
                if (got) collector.onKernelEvent(copy(reusableEvent));
            } while (got);
        } catch (Exception e) {
            Log.e(TAG, "Poll loop error: " + e.getMessage());
        }
        scheduleNextPoll();
    }

    private void stopEventLoop() {
        running.set(false);
        if (eventHandler != null) eventHandler.removeCallbacksAndMessages(null);
        if (eventThread  != null) { eventThread.quitSafely(); eventThread = null; }
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    private static ShieldEventData copy(ShieldEventData s) {
        ShieldEventData d = new ShieldEventData();
        d.pid = s.pid; d.uid = s.uid; d.timestamp = s.timestamp;
        d.bytes = s.bytes; d.operation = s.operation; d.filename = s.filename;
        return d;
    }

    private void broadcast(String action, String reason) {
        Intent i = new Intent(action);
        i.putExtra("reason", reason);
        sendBroadcast(i);
    }

    private void updateNotification(String text) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SHIELD Mode-A")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield_notification)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SHIELD Mode-A", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("eBPF kernel telemetry");
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
