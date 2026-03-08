package com.dearmoon.shield.modea.stub;

import android.content.Context;
import android.util.Log;

/**
 * STUB — stands in for com.dearmoon.shield.snapshot.SnapshotManager.
 *
 * The standalone APK does not perform snapshot / restore operations.
 * This stub satisfies the constructor call in ModeAService without any
 * real implementation.
 *
 * When merging Mode-A into the main SHIELD project:
 *   1. Delete this file.
 *   2. Update imports in ModeAService to use
 *      com.dearmoon.shield.snapshot.SnapshotManager instead.
 */
public class SnapshotManager {

    private static final String TAG = "SHIELD_MODE_A";

    public SnapshotManager(Context context) {
        Log.i(TAG, "[STUB] SnapshotManager created (no-op in prototype)");
    }
}
