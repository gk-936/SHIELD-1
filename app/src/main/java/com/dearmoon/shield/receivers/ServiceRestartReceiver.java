package com.dearmoon.shield.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.dearmoon.shield.services.ShieldProtectionService;

public class ServiceRestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean intentionallyStopped = context.getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                .getBoolean("intentionally_stopped", false);

        if (intentionallyStopped) {
            return; // User intentionally stopped service
        }

        Intent serviceIntent = new Intent(context, ShieldProtectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
