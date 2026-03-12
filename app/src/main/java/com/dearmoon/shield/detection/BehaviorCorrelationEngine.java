package com.dearmoon.shield.detection;

import android.content.Context;
import android.util.Log;
import com.dearmoon.shield.data.EventDatabase;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Behavior Correlation Engine
 *
 * <p>Correlates file-system events with concurrent network and honeyfile activity
 * within a 5-second sliding window to produce a supplementary behavior score
 * (0–30 points) on top of the entropy/KL/SPRT file score.
 *
 * <p><b>Attribution contract:</b> the {@code fileUid} parameter passed to
 * {@link #correlateFileEvent} is the best available attribution for the process
 * that triggered the file event. It is resolved by
 * {@code UnifiedDetectionEngine.resolveAttributionUid()} using path-based parsing
 * and a foreground-process heuristic. A value of {@code -1} means the event is
 * <em>unattributed</em> — no confident UID could be derived for the file path.
 * Network-event filtering uses this UID; when uid is -1 the filter is relaxed
 * and all recent network events in the window are considered.
 *
 * <p>Note: Android’s {@code FileObserver} does not expose the writing PID/UID.
 * Deterministic per-write attribution requires root or a kernel eBPF probe,
 * neither of which is available in SHIELD’s rootless design. The heuristics
 * used here are documented limitations, not bugs.
 */
public class BehaviorCorrelationEngine {
    private static final String TAG = "BehaviorCorrelation";
    private static final long CORRELATION_WINDOW_MS = 5000; // 5-second window
    
    private final EventDatabase database;
    private final PackageAttributor attributor;
    private final Map<Integer, BehaviorProfile> uidProfiles = new ConcurrentHashMap<>();
    
    public BehaviorCorrelationEngine(Context context) {
        this.database = EventDatabase.getInstance(context);
        this.attributor = new PackageAttributor(context);
        Log.i(TAG, "BehaviorCorrelationEngine initialized (REUSE mode)");
    }
    
    /**
     * Correlate recent events for a specific file operation.
     *
     * @param filePath      the path of the file that was modified
     * @param eventTimestamp epoch-ms timestamp of the file event
     * @param fileUid       the attributed UID of the writing process, or {@code -1}
     *                      when attribution was not possible (unattributed event)
     */
    public CorrelationResult correlateFileEvent(String filePath, long eventTimestamp, int fileUid,
                                                long sprtLastResetTimestamp) {
        long windowStart = eventTimestamp - CORRELATION_WINDOW_MS;

        // M-02: Clamp query start to the last SPRT reset boundary so pre-reset events
        // from the previous detection window don't inflate the current behavior score.
        long fileQueryStart = Math.max(windowStart, sprtLastResetTimestamp);

        // Query existing events within time window (REUSE database)
        List<JSONObject> fileEvents = queryRecentFileEvents(fileQueryStart, eventTimestamp);
        List<JSONObject> networkEvents = queryRecentNetworkEvents(windowStart, eventTimestamp, fileUid);
        List<JSONObject> honeyfileEvents = queryRecentHoneyfileEvents(windowStart, eventTimestamp);
        List<JSONObject> lockerEvents = queryRecentLockerEvents(windowStart, eventTimestamp);
        
        // Calculate behavior score (0-30 points)
        int behaviorScore = calculateBehaviorScore(
            fileEvents.size(),
            networkEvents.size(),
            honeyfileEvents.size(),
            lockerEvents.size(),
            fileUid
        );
        
        // Get package name (REUSE attribution)
        String packageName = attributor.getPackageForUid(fileUid);
        
        // Create correlation result
        CorrelationResult result = new CorrelationResult(
            filePath,
            packageName,
            fileUid,
            behaviorScore,
            fileEvents.size(),
            networkEvents.size(),
            honeyfileEvents.size(),
            lockerEvents.size()
        );
        
        Log.d(TAG, "Correlation: " + packageName + " score=" + behaviorScore + 
              " (file=" + fileEvents.size() + ", net=" + networkEvents.size() + 
              ", honey=" + honeyfileEvents.size() + ", locker=" + lockerEvents.size() + ")");
        
        return result;
    }
    
    /**
     * Calculate behavior score based on cross-signal patterns
     * Extends existing 0-100 file score with 0-30 behavior score
     */
    private int calculateBehaviorScore(int fileCount, int networkCount, 
                                       int honeyfileCount, int lockerCount, int uid) {
        int score = 0;
        
        // Pattern 1: Rapid file modification + network activity (0-10 points)
        if (fileCount > 5 && networkCount > 0) {
            score += 10; // File encryption + C2 communication
        } else if (fileCount > 3 && networkCount > 0) {
            score += 5;
        }
        
        // Pattern 2: Honeyfile access (0-15 points)
        if (honeyfileCount > 0) {
            score += Math.min(honeyfileCount * 5, 15); // Max 15 points
        }
        
        // Pattern 3: UI threat + file activity (0-5 points)
        if (lockerCount > 0 && fileCount > 0) {
            score += 5; // Locker ransomware pattern
        }
        
        return Math.min(score, 30); // Cap at 30 points
    }
    
    // REUSE: Query existing database tables with efficiency fixes
    private List<JSONObject> queryRecentFileEvents(long start, long end) {
        return database.queryEventsSince("FILE_SYSTEM", start, 100);
    }
    
    private List<JSONObject> queryRecentNetworkEvents(long start, long end, int uid) {
        List<JSONObject> all = database.queryEventsSince("NETWORK", start, 100);
        // When uid == -1 the file event is unattributed (FileObserver does not expose
        // the writing process and no path-based heuristic resolved it). In this case
        // return all network events in the window so the behavior score still benefits
        // from concurrent C2 activity, even without per-UID filtering.
        if (uid == -1) {
            return all;
        }
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject event : all) {
            int eventUid = event.optInt("appUid", -1);
            if (eventUid == uid) {
                filtered.add(event);
            }
        }
        return filtered;
    }
    
    private List<JSONObject> queryRecentHoneyfileEvents(long start, long end) {
        return database.queryEventsSince("HONEYFILE_ACCESS", start, 50);
    }
    
    private List<JSONObject> queryRecentLockerEvents(long start, long end) {
        return database.queryEventsSince("LOCKER_SHIELD", start, 50);
    }
    
    /**
     * Behavior profile tracking per UID
     */
    private static class BehaviorProfile {
        int fileModifications = 0;
        int networkConnections = 0;
        int honeyfileAccesses = 0;
        long lastActivity = 0;
    }
}
