package com.dearmoon.shield;

import android.app.Application;
import android.util.Log;

import com.dearmoon.shield.data.EventMerger;
import com.dearmoon.shield.detection.UnifiedDetectionEngine;
import com.dearmoon.shield.snapshot.SnapshotManager;

/**
 * Application class — holds the single, process-scoped instances of the core
 * detection pipeline components.
 *
 * Both ShieldProtectionService (Mode B) and ModeAService (Mode A) obtain
 * these shared objects from here instead of each creating their own.  This
 * guarantees that:
 *
 *   • The SPRT accumulator sees every file event regardless of which
 *     collection channel (FileObserver or eBPF) produced it.
 *   • The SnapshotManager's in-memory activeAttackId is consistent across
 *     both services — there is only one attack window at a time.
 *   • The EventMerger's 1-second dedup window can actually match Mode A
 *     and Mode B events about the same file write into one DB row.
 *
 * Lifecycle ownership
 * -------------------
 *   ShieldProtectionService is the primary service and calls shutdownEngine()
 *   in its onDestroy().  ModeAService is a data-source add-on — it only
 *   attaches ModeAFileCollector to the shared engine and must NOT call
 *   shutdown when it stops.
 */
public class ShieldApplication extends Application {

    private static final String TAG = "ShieldApplication";

    private static ShieldApplication instance;

    private SnapshotManager        snapshotManager;
    private UnifiedDetectionEngine detectionEngine;
    private EventMerger            eventMerger;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "ShieldApplication initialised");
    }

    public static ShieldApplication get() {
        return instance;
    }

    // -------------------------------------------------------------------------
    // Shared component accessors (lazy, thread-safe)
    // -------------------------------------------------------------------------

    /** Returns the single SnapshotManager for this process. */
    public synchronized SnapshotManager getSnapshotManager() {
        if (snapshotManager == null) {
            snapshotManager = new SnapshotManager(this);
            Log.i(TAG, "SnapshotManager created (shared instance)");
        }
        return snapshotManager;
    }

    /**
     * Returns the single UnifiedDetectionEngine for this process.
     * The engine is created with the shared SnapshotManager so that attack
     * tracking and backup restores stay in sync regardless of which service
     * triggers a detection.
     */
    public synchronized UnifiedDetectionEngine getDetectionEngine() {
        if (detectionEngine == null) {
            detectionEngine = new UnifiedDetectionEngine(this, getSnapshotManager());
            Log.i(TAG, "UnifiedDetectionEngine created (shared instance)");
        }
        return detectionEngine;
    }

    /**
     * Returns the single EventMerger so Mode A and Mode B events about the
     * same file write are deduplicated into one database row.
     */
    public synchronized EventMerger getEventMerger() {
        if (eventMerger == null) {
            eventMerger = new EventMerger();
        }
        return eventMerger;
    }

    // -------------------------------------------------------------------------
    // Lifecycle management — called by ShieldProtectionService.onDestroy()
    // -------------------------------------------------------------------------

    /**
     * Tears down the detection engine and snapshot manager so they are
     * re-created fresh if the service is restarted by the watchdog.
     * Must only be called by ShieldProtectionService.
     */
    public synchronized void shutdownEngine() {
        if (detectionEngine != null) {
            detectionEngine.shutdown();
            detectionEngine = null;
            Log.i(TAG, "UnifiedDetectionEngine shut down");
        }
        if (snapshotManager != null) {
            snapshotManager.shutdown();
            snapshotManager = null;
            Log.i(TAG, "SnapshotManager shut down");
        }
        eventMerger = null;
    }
}
