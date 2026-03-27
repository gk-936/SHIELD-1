package com.dearmoon.shield.detection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Centrally manages volatile security contexts for all active processes.
 * Decouples process-specific telemetry from the primary analysis engine.
 */
public class AppSecurityContextManager {
    private static final String TAG = "AppSecurityContextManager";
    private static AppSecurityContextManager instance;

    /**
     * Context container for a specific UID's detection state.
     */
    public static class AppDetectionContext {
        public final SPRTDetector sprtDetector = new SPRTDetector();
        public final ConcurrentLinkedQueue<Long> recentModifications = new ConcurrentLinkedQueue<>();
        public final java.util.Set<String> affectedExtensions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        public long lastEventTimestamp = System.currentTimeMillis();
    }

    private final Map<Integer, AppDetectionContext> appContexts = new ConcurrentHashMap<>();

    private AppSecurityContextManager() {}

    public static synchronized AppSecurityContextManager getInstance() {
        if (instance == null) {
            instance = new AppSecurityContextManager();
        }
        return instance;
    }

    /**
     * Retrieves or initializes the context for a given UID.
     * System-level or unknown processes (UID <= 0) map to UID 0.
     */
    public AppDetectionContext getContext(int uid) {
        int targetUid = (uid <= 0) ? 0 : uid;
        return appContexts.computeIfAbsent(targetUid, k -> new AppDetectionContext());
    }

    /**
     * Resets detection state for a specific UID.
     */
    public void resetContext(int uid) {
        AppDetectionContext context = appContexts.get(uid);
        if (context != null) {
            context.sprtDetector.reset();
            context.recentModifications.clear();
            context.affectedExtensions.clear();
            context.lastEventTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Purges all active security contexts.
     */
    public void clearAllContexts() {
        appContexts.clear();
    }
}
