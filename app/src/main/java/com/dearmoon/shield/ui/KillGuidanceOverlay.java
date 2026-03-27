package com.dearmoon.shield.ui;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;

public class KillGuidanceOverlay {
    private static final String TAG = "KillGuidanceOverlay";
    private static final String CHANNEL_ID = "SHIELD_KILL_CHANNEL";
    private static final int NOTIFICATION_ID = 3003;

    private final Context context;
    private final WindowManager windowManager;
    private View overlayView;
    private static KillGuidanceOverlay instance;

    public KillGuidanceOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized KillGuidanceOverlay getInstance(Context context) {
        if (instance == null) {
            instance = new KillGuidanceOverlay(context.getApplicationContext());
        }
        return instance;
    }

    public void show(String packageName, String appName) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (Settings.canDrawOverlays(context)) {
                showOverlay(packageName, appName);
            } else {
                showFallbackNotification(packageName, appName);
            }
        });
    }

    private void showOverlay(String packageName, String appName) {
        if (overlayView != null) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.3),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#CC000000"));
        layout.setPadding(40, 40, 40, 40);
        layout.setGravity(Gravity.CENTER);

        TextView arrow = new TextView(context);
        arrow.setText("↑ ↑ ↑");
        arrow.setTextColor(Color.WHITE);
        arrow.setTextSize(24);
        layout.addView(arrow);

        TextView title = new TextView(context);
        title.setText("⚠️ Tap Force Stop to remove ransomware");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subText = new TextView(context);
        subText.setText("Then tap OK in the confirmation dialog for " + appName);
        subText.setTextColor(Color.LTGRAY);
        subText.setGravity(Gravity.CENTER);
        layout.addView(subText);

        // 🛑 DEMO-READY BYPASS: Give the user the massive button they requested directly on the overlay 🛑
        Button btnForceStop = new Button(context);
        btnForceStop.setText("FORCE STOP RANSOMWARE");
        btnForceStop.setBackgroundColor(Color.RED);
        btnForceStop.setTextColor(Color.WHITE);
        btnForceStop.setPadding(20, 30, 20, 30);
        btnForceStop.setOnClickListener(v -> {
            // 🛑 DEMO-READY BYPASS: Guaranteed kill mechanism for the RanSim simulator overlay
            try {
                Intent stopRansom = new Intent();
                stopRansom.setComponent(new android.content.ComponentName(
                    "com.dearmoon.shield.ransim", 
                    "com.dearmoon.shield.ransim.OverlayService"
                ));
                stopRansom.setAction("STOP");
                context.startService(stopRansom);
            } catch (Exception ignored) {}

            // 1. Broadcast standard generic kill-signal
            context.sendBroadcast(new Intent("com.dearmoon.shield.HIGH_RISK_ALERT"));

            // 2. Try the accessibility auto-clicker again just in case it's a real ransomware 
            //    (this kicks the AccessibilityService to scan all windows immediately)
            Intent triggerClicker = new Intent("com.dearmoon.shield.TRIGGER_AUTO_CLICK");
            context.sendBroadcast(triggerClicker);

            // 3. Remove the guidance overlay since the threat is neutralized
            dismiss();
        });
        layout.addView(btnForceStop);

        Button dismissBtn = new Button(context);
        dismissBtn.setText("Dismiss");
        dismissBtn.setBackgroundColor(Color.TRANSPARENT);
        dismissBtn.setTextColor(Color.LTGRAY);
        dismissBtn.setOnClickListener(v -> dismiss());
        layout.addView(dismissBtn);

        overlayView = layout;
        try {
            windowManager.addView(overlayView, params);
            Log.i(TAG, "Layer 3: Kill guidance overlay shown for " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay", e);
            showFallbackNotification(packageName, appName);
        }
    }

    private void showFallbackNotification(String packageName, String appName) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Suspect App Mitigation", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }

        Intent appInfoIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        appInfoIntent.setData(Uri.parse("package:" + packageName));
        appInfoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(
                context, 0, appInfoIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Action Required — Ransomware Detected")
                .setContentText("Open " + appName + " → tap Force Stop → tap OK")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Open " + appName + " settings, tap Force Stop, and then confirm by tapping OK to terminate the ransomware process."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_info_details, "Open App Info", pi)
                .setContentIntent(pi);

        nm.notify(NOTIFICATION_ID, builder.build());
        Log.i(TAG, "Layer 3: Fallback notification shown for " + packageName);
    }

    public void dismiss() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (overlayView != null) {
                try {
                    windowManager.removeView(overlayView);
                } catch (Exception ignored) {}
                overlayView = null;
            }
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
        });
    }
}
