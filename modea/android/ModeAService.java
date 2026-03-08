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
import com.dearmoon.shield.data.TelemetryStorage;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.snapshot.SnapshotManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModeAService — Android foreground service that orchestrates Mode-A.
 *
 * Lifecycle
 * ---------
 *   1. onStartCommand()
 *        a) Run pre-flight checks on a background thread (root + BPF).
 *        b) If checks pass: deploy binaries, start daemon, connect socket.
 *        c) If checks fail: broadcast MODEA_UNAVAILABLE, stop self.
 *
 *   2. Event loop (background HandlerThread "ModeAEventLoop")
 *        – Poll ModeAJni.nativeReadEvent() every 20 ms.
 *        – Hand each ShieldEventData to ModeAFileCollector.
 *        – ModeAFileCollector converts it to FileSystemEvent.
 *        – FileSystemEvent is forwarded to UnifiedDetectionEngine.
 *
 *   3. Kill broadcast receiver
 *        – Listens for com.dearmoon.shield.MODEA_KILL_PID.
 *        – Sends kill command to daemon via ModeAJni.nativeSendKill().
 *
 *   4. onDestroy()
 *        – Stop event loop.
 *        – Disconnect JNI socket.
 *        – Stop root daemon via ModeAController.
 *
 * Intents broadcast by this service
 * ----------------------------------
 *   com.dearmoon.shield.MODEA_STARTED      — Mode-A running
 *   com.dearmoon.shield.MODEA_UNAVAILABLE  — root or BPF not available
 *
 * Intents consumed by this service
 * ---------------------------------
 *   com.dearmoon.shield.MODEA_KILL_PID     — extra "pid" (int)
 */
public class ModeAService extends Service {

    private static final String TAG              = "SHIELD_MODE_A";
    private static final String CHANNEL_ID       = "shield_modea_channel";
    private static final int    NOTIF_ID         = 9001;
    private static final long   POLL_INTERVAL_MS = 20L;   // 50 events/sec max

    // Broadcast action constants
    public static final String ACTION_STARTED     = "com.dearmoon.shield.MODEA_STARTED";
    public static final String ACTION_UNAVAILABLE = "com.dearmoon.shield.MODEA_UNAVAILABLE";
    public static final String ACTION_KILL_PID    = "com.dearmoon.shield.MODEA_KILL_PID";

    // ---------------------------------------------------------------
    // Internal state
    // ---------------------------------------------------------------
    private ModeAController      controller;
    private ModeAJni             jni;
    private ModeAFileCollector   collector;
    private UnifiedDetectionEngine detectionEngine;
    private TelemetryStorage     storage;

    private HandlerThread        eventThread;
    private Handler              eventHandler;
    private final AtomicBoolean  running      = new AtomicBoolean(false);
    private final AtomicBoolean  connected    = new AtomicBoolean(false);

    // Reusable ShieldEventData — allocated once, reused across poll loop
    private final ShieldEventData reusableEvent = new ShieldEventData();

    // BroadcastReceiver for kill commands from detection engine
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

    // ---------------------------------------------------------------
    // Service lifecycle
    // ---------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        controller     = new ModeAController(this);
        jni            = new ModeAJni();
        storage        = new TelemetryStorage(this);

        // Use the same SnapshotManager + UnifiedDetectionEngine as Mode-B
        // if they have been started already; otherwise create fresh instances.
        SnapshotManager snapshotManager = new SnapshotManager(this);
        detectionEngine = new UnifiedDetectionEngine(this, snapshotManager);
        collector       = new ModeAFileCollector(detectionEngine);

        // Register kill-PID receiver
        IntentFilter filter = new IntentFilter(ACTION_KILL_PID);
        registerReceiver(killReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);

        Log.i(TAG, "ModeAService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Mode-A initialising…"));
        Log.i(TAG, "ModeAService starting");

        // Run pre-flight and startup on a temporary background thread
        // so we never block the main thread.
        new Thread(this::initModeA, "ModeAInit").start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ModeAService stopping");
        stopEventLoop();

        if (jni != null) jni.nativeDisconnect();
        if (controller != null) controller.stopDaemon();

        unregisterReceiver(killReceiver);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;   // not a bound service
    }

    // ---------------------------------------------------------------
    // Initialisation sequence (runs on background thread)
    // ---------------------------------------------------------------

    private void initModeA() {
        Log.i(TAG, "Pre-flight: checking root access");
        if (!controller.isRootAvailable()) {
            Log.e(TAG, "Root not available — Mode-A disabled, falling back to Mode-B");
            broadcastUnavailable("Mode A requires root access");
            stopSelf();
            return;
        }

        Log.i(TAG, "Pre-flight: checking kernel BPF compatibility");
        if (!controller.isKernelCompatible()) {
            Log.e(TAG, "Kernel does not support BPF/tracepoints — Mode-A disabled");
            broadcastUnavailable("Kernel does not support eBPF");
            stopSelf();
            return;
        }

        Log.i(TAG, "Deploying daemon binaries");
        if (!controller.deployBinaries()) {
            Log.e(TAG, "Binary deployment failed — Mode-A disabled");
            broadcastUnavailable("Failed to deploy Mode-A binaries");
            stopSelf();
            return;
        }

        Log.i(TAG, "Starting root daemon");
        if (!controller.startDaemon()) {
            Log.e(TAG, "Daemon failed to start — Mode-A disabled");
            broadcastUnavailable("Root daemon failed to start");
            stopSelf();
            return;
        }

        // Give the daemon 1.5 s to bind its socket and attach tracepoints
        sleep(1500);

        if (!controller.isDaemonRunning()) {
            Log.e(TAG, "Daemon socket not found after startup — Mode-A disabled");
            broadcastUnavailable("Root daemon socket not found");
            stopSelf();
            return;
        }

        Log.i(TAG, "Connecting JNI to daemon socket");
        if (!jni.nativeConnect(ModeAController.SOCKET_PATH)) {
            Log.e(TAG, "JNI socket connect failed — Mode-A disabled");
            broadcastUnavailable("Could not connect to daemon socket");
            stopSelf();
            return;
        }

        connected.set(true);

        // Update notification to reflect active status
        updateNotification("Mode-A active — kernel telemetry running");

        // Broadcast success so MainActivity / SettingsActivity can update UI
        sendBroadcast(new Intent(ACTION_STARTED));
        Log.i(TAG, "Mode-A fully operational");

        // Start the event poll loop
        startEventLoop();
    }

    // ---------------------------------------------------------------
    // Event poll loop
    // ---------------------------------------------------------------

    private void startEventLoop() {
        eventThread = new HandlerThread("ModeAEventLoop");
        eventThread.start();
        eventHandler = new Handler(eventThread.getLooper());
        running.set(true);
        scheduleNextPoll();
        Log.i(TAG, "Event poll loop started (interval=" + POLL_INTERVAL_MS + " ms)");
    }

    private void scheduleNextPoll() {
        if (!running.get()) return;
        eventHandler.postDelayed(this::pollEvents, POLL_INTERVAL_MS);
    }

    private void pollEvents() {
        if (!running.get() || !connected.get()) return;

        // Drain all pending events from the socket in one poll iteration
        try {
            boolean gotEvent;
            do {
                gotEvent = jni.nativeReadEvent(reusableEvent);
                if (gotEvent) {
                    // Defensive copy so the collector can't see state torn
                    // by a subsequent nativeReadEvent() call.
                    ShieldEventData copy = copyEvent(reusableEvent);
                    collector.onKernelEvent(copy);
                }
            } while (gotEvent);
        } catch (Exception e) {
            Log.e(TAG, "Error in poll loop: " + e.getMessage());
        }

        scheduleNextPoll();
    }

    private void stopEventLoop() {
        running.set(false);
        if (eventHandler != null) {
            eventHandler.removeCallbacksAndMessages(null);
        }
        if (eventThread != null) {
            eventThread.quitSafely();
            eventThread = null;
        }
    }

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    private static ShieldEventData copyEvent(ShieldEventData src) {
        ShieldEventData dst = new ShieldEventData();
        dst.pid       = src.pid;
        dst.uid       = src.uid;
        dst.timestamp = src.timestamp;
        dst.bytes     = src.bytes;
        dst.operation = src.operation;
        dst.filename  = src.filename;
        return dst;
    }

    private void broadcastUnavailable(String reason) {
        Intent i = new Intent(ACTION_UNAVAILABLE);
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
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SHIELD Mode-A",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("eBPF kernel telemetry collection");
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
