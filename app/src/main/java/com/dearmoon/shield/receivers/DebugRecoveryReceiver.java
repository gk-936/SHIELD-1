package com.dearmoon.shield.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.dearmoon.shield.ShieldApplication;
import com.dearmoon.shield.snapshot.SnapshotManager;
import java.io.File;

/**
 * Debug receiver to trigger snapshot recovery via ADB for testing.
 * Command: adb shell am broadcast -a com.dearmoon.shield.TEST_RESTORE
 */
public class DebugRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "DebugRecoveryReceiver";
    public static final String ACTION_TEST_RESTORE = "com.dearmoon.shield.TEST_RESTORE";
    public static final String ACTION_TEST_SNAPSHOT = "com.dearmoon.shield.TEST_SNAPSHOT";
    public static final String ACTION_TEST_ATTACK_START = "com.dearmoon.shield.TEST_ATTACK_START";
    public static final String ACTION_TEST_ATTACK_STOP = "com.dearmoon.shield.TEST_ATTACK_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.e(TAG, "DEBUG: External trigger: " + action);

        SnapshotManager snapshotManager = ShieldApplication.get().getSnapshotManager();
        if (snapshotManager == null) {
            Log.e(TAG, "SnapshotManager is null!");
            return;
        }

        if (ACTION_TEST_RESTORE.equals(action)) {
            Log.i(TAG, "Triggering automated restore via ADB...");
            new Thread(() -> {
                try {
                    snapshotManager.performAutomatedRestore();
                    Log.i(TAG, "Restore command successfully executed.");
                } catch (Exception e) {
                    Log.e(TAG, "Restore error: " + e.getMessage());
                }
            }).start();
        } else if (ACTION_TEST_SNAPSHOT.equals(action)) {
            Log.i(TAG, "Triggering baseline snapshot via ADB...");
            String[] monitoredDirs = {
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS).getAbsolutePath(),
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                new File(android.os.Environment.getExternalStorageDirectory(), "Documents/shield_ransim_sandbox").getAbsolutePath()
            };
            snapshotManager.createBaselineSnapshot(monitoredDirs);
        } else if (ACTION_TEST_ATTACK_START.equals(action)) {
            Log.i(TAG, "Simulating ATTACK START threshold...");
            snapshotManager.startAttackTracking();
        } else if (ACTION_TEST_ATTACK_STOP.equals(action)) {
            Log.i(TAG, "Simulating ATTACK STOP threshold...");
            snapshotManager.stopAttackTracking();
        }
    }
}
