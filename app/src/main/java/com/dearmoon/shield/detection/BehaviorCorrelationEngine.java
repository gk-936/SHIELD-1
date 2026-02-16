package com.dearmoon.shield.detection;

import android.content.Context;
import android.util.Log;
import com.dearmoon.shield.data.EventDatabase;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pseudo-Kernel Detection Layer: Behavior Correlation Engine
 * 
 * REUSE STRATEGY:
 * - Uses existing EventDatabase for querying events
 * - Leverages existing timestamp-based correlation
 * - Extends existing confidence scoring (adds 0-30 behavior points)
 * - No duplicate event collection or storage
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
     * Correlate recent events for a specific file operation
     * REUSES: Existing event timestamps and database queries
     */
    public CorrelationResult correlateFileEvent(String filePath, long eventTimestamp, int fileUid) {
        long windowStart = eventTimestamp - CORRELATION_WINDOW_MS;
        
        // Query existing events within time window (REUSE database)
        List<JSONObject> fileEvents = queryRecentFileEvents(windowStart, eventTimestamp);
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
    
    // REUSE: Query existing database tables
    private List<JSONObject> queryRecentFileEvents(long start, long end) {
        // Simplified query - in production, add WHERE timestamp BETWEEN start AND end
        return database.getAllEvents("FILE_SYSTEM", 100);
    }
    
    private List<JSONObject> queryRecentNetworkEvents(long start, long end, int uid) {
        // Simplified query - filter by UID and timestamp
        List<JSONObject> all = database.getAllEvents("NETWORK", 100);
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject event : all) {
            long ts = event.optLong("timestamp", 0);
            int eventUid = event.optInt("appUid", -1);
            if (ts >= start && ts <= end && eventUid == uid) {
                filtered.add(event);
            }
        }
        return filtered;
    }
    
    private List<JSONObject> queryRecentHoneyfileEvents(long start, long end) {
        List<JSONObject> all = database.getAllEvents("HONEYFILE_ACCESS", 50);
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject event : all) {
            long ts = event.optLong("timestamp", 0);
            if (ts >= start && ts <= end) {
                filtered.add(event);
            }
        }
        return filtered;
    }
    
    private List<JSONObject> queryRecentLockerEvents(long start, long end) {
        // Note: LockerShield events not in getAllEvents() - would need database extension
        return new ArrayList<>(); // Placeholder
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
