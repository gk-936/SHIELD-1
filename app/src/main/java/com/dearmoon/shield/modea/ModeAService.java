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

import com.dearmoon.shield.R;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.snapshot.SnapshotManager;

import java.util.concurrent.atomic.AtomicBoolean;

/** Mode-A orchestration service */
public class ModeAService extends Service {

    private static final String TAG              = "SHIELD_MODE_A";
    private static final String CHANNEL_ID       = "shield_modea_channel";
    private static final int    NOTIF_ID         = 9001;
    private static final long   POLL_INTERVAL_MS = 20L;   // 50 events/sec max

    public static final String ACTION_STARTED     = "com.dearmoon.shield.MODEA_STARTED";
    public static final String ACTION_UNAVAILABLE = "com.dearmoon.shield.MODEA_UNAVAILABLE";
    public static final String ACTION_KILL_PID    = "com.dearmoon.shield.MODEA_KILL_PID";

    // ------------------------------------------------------------------
    private ModeAController        controller;
    private ModeAJni               jni;
    private ModeAFileCollector     collector;
    private UnifiedDetectionEngine detectionEngine;

    private HandlerThread          eventThread;
    private Handler                eventHandler;
    private final AtomicBoolean    running   = new AtomicBoolean(false);
    private final AtomicBoolean    connected = new AtomicBoolean(false);
    // Set to true at the top of onDestroy() so initModeA() can abort early
    // instead of spending 10 s waiting for a socket that will never exist.
    private final AtomicBoolean    destroyed = new AtomicBoolean(false);

    private static volatile boolean daemonConnected = false;

    public static boolean isConnected() {
        return daemonConnected;
    }

    // Event reuse
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

    // Service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        controller      = new ModeAController(this);
        jni             = new ModeAJni();
        // Shared detection engine
        // Unified kernel telemetry
        detectionEngine = com.dearmoon.shield.ShieldApplication.get().getDetectionEngine();
        collector       = new ModeAFileCollector(
                detectionEngine,
                com.dearmoon.shield.ShieldApplication.get().getEventMerger());

        IntentFilter filter = new IntentFilter(ACTION_KILL_PID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killReceiver, filter, "com.dearmoon.shield.RESTART_PERMISSION", null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(killReceiver, filter, "com.dearmoon.shield.RESTART_PERMISSION", null);
        }
        Log.i(TAG, "ModeAService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Mode-A requires eBPF tracepoints — available from Android 11 (API 30)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "Mode-A requires API 30+, device is API "
                    + Build.VERSION.SDK_INT + " — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Mode-A initialising…"));
        new Thread(this::initModeA, "ModeAInit").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ModeAService stopping");
        // Signal initModeA() thread to abort before we tear down the daemon.
        destroyed.set(true);
        daemonConnected = false;
        stopEventLoop();
        if (jni != null)         jni.nativeDisconnect();
        if (controller != null)  controller.stopDaemon();
        // Do NOT shutdown the detection engine here — it is owned by ShieldApplication
        // and shared with ShieldProtectionService (Mode B).  Shutting it down here
        // would kill Mode B detection while Mode B is still running.
        detectionEngine = null;
        try { unregisterReceiver(killReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    // Mode-A initialization

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

        if (destroyed.get()) return;
        Log.i(TAG, "Deploying binaries from APK assets…");
        if (!controller.deployBinaries()) {
            Log.e(TAG, "Binary deployment failed");
            broadcast(ACTION_UNAVAILABLE, "Failed to deploy Mode-A binaries");
            stopSelf();
            return;
        }

        if (destroyed.get()) return;
        Log.i(TAG, "Starting root daemon…");
        if (!controller.startDaemon()) {
            broadcast(ACTION_UNAVAILABLE, "Root daemon failed to start");
            stopSelf();
            return;
        }

        // Poll daemon socket
        boolean socketReady = false;
        for (int i = 0; i < 20; i++) {          // 20 × 500 ms = 10 s
            sleep(500);
            // Bail immediately if onDestroy() was called while we were sleeping.
            if (destroyed.get()) {
                Log.i(TAG, "Service destroyed during daemon wait — aborting init");
                return;
            }
            if (controller.isDaemonRunning()) { socketReady = true; break; }
            Log.d(TAG, "Waiting for daemon socket… (" + (i + 1) + "/20)");
        }

        if (!socketReady) {
            String stderr = controller.readDaemonStderr();
            Log.e(TAG, "Daemon socket not found after 10 s. stderr:\n" + stderr);
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
        daemonConnected = true;
        updateNotification("Mode-A active — kernel telemetry running");
        sendBroadcast(new Intent(ACTION_STARTED));
        Log.i(TAG, "Mode-A fully operational");

        startEventLoop();
    }

    // Event poll loop

    private void startEventLoop() {
        eventThread = new HandlerThread("ModeAEventLoop");
        eventThread.start();
        eventHandler = new Handler(eventThread.getLooper());
        running.set(true);
        scheduleNextPoll();
        Log.i(TAG, "Event poll loop started (interval=" + POLL_INTERVAL_MS + " ms)");
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
                if (got) collector.onKernelEvent(copyEvent(reusableEvent));
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

    // Utilities

    private static ShieldEventData copyEvent(ShieldEventData s) {
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
                .setSmallIcon(R.drawable.ic_launcher_foreground)
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
