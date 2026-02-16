package com.dearmoon.shield.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class NetworkBlockReceiver extends BroadcastReceiver {
    private static final String TAG = "NetworkBlockReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.dearmoon.shield.BLOCK_NETWORK".equals(intent.getAction())) {
            Log.e(TAG, "EMERGENCY: Activating network block");
            
            // Trigger emergency mode in NetworkGuardService
            Intent serviceIntent = new Intent("com.dearmoon.shield.EMERGENCY_MODE");
            context.sendBroadcast(serviceIntent);
        }
    }
}
