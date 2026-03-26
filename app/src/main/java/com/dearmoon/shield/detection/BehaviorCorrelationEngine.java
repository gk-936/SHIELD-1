package com.dearmoon.shield.detection;

import android.content.Context;
import android.util.Log;
import com.dearmoon.shield.data.EventDatabase;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Behavior Correlation Engine
public class BehaviorCorrelationEngine {
    private static final String TAG = "BehaviorCorrelation";
    private static final long CORRELATION_WINDOW_MS = 5000; // 5-second window
    
    // CRI calibrated weights
    private static final double WEIGHT_FCHMOD = 0.818;
    private static final double WEIGHT_UNLINK = 0.567;
    private static final double WEIGHT_CREATE_WRITE = 0.542;
    private static final double WEIGHT_CHMOD = 0.514;
    private static final double WEIGHT_FSYNC = 0.514;
    private static final double WEIGHT_NETWORK = 0.545;

    private final EventDatabase database;
    private final PackageAttributor attributor;
    private final Map<Integer, BehaviorProfile> uidProfiles = new ConcurrentHashMap<>();
    
    public BehaviorCorrelationEngine(Context context) {
        this.database = EventDatabase.getInstance(context);
        this.attributor = new PackageAttributor(context);
        Log.i(TAG, "BehaviorCorrelationEngine initialized (REUSE mode)");
    }
    
    // Correlate file events
    public CorrelationResult correlateFileEvent(String filePath, long eventTimestamp, int fileUid,
                                                long sprtLastResetTimestamp) {
        long windowStart = eventTimestamp - CORRELATION_WINDOW_MS;

        // Clamp window start
        long fileQueryStart = Math.max(windowStart, sprtLastResetTimestamp);

        // Query existing events (UID-SCOPED)
        List<JSONObject> fileEvents = queryRecentFileEvents(fileQueryStart, eventTimestamp, fileUid);
        List<JSONObject> networkEvents = queryRecentNetworkEvents(windowStart, eventTimestamp, fileUid);
        List<JSONObject> honeyfileEvents = queryRecentHoneyfileEvents(windowStart, eventTimestamp, fileUid);
        List<JSONObject> lockerEvents = queryRecentLockerEvents(windowStart, eventTimestamp);
        
        // Calculate behavior score
        int behaviorScore = calculateBehaviorScore(
            fileEvents.size(),
            networkEvents.size(),
            honeyfileEvents.size(),
            lockerEvents.size(),
            fileUid
        );

        // Calculate CRI contribution
        double criContribution = calculateCriContribution(filePath, networkEvents.size());
        
        // Resolve package name
        String packageName = attributor.getPackageForUid(fileUid);
        
        // Create correlation result
        CorrelationResult result = new CorrelationResult(
            filePath,
            packageName,
            fileUid,
            behaviorScore,
            criContribution,
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
    
    // Score behavior patterns
    private int calculateBehaviorScore(int fileCount, int networkCount, 
                                       int honeyfileCount, int lockerCount, int uid) {
        int score = 0;
        
        // File/network activity score
        if (fileCount > 5 && networkCount > 0) {
            score += 10; // File encryption + C2
        } else if (fileCount > 3 && networkCount > 0) {
            score += 5;
        }
        
        // Honeyfile access score
        if (honeyfileCount > 0) {
            score += Math.min(honeyfileCount * 5, 15); // Max 15 points
        }
        
        // UI threat score
        if (lockerCount > 0 && fileCount > 0) {
            score += 5; // Locker ransomware pattern
        }
        
        return Math.min(score, 30); // Cap at 30 points
    }

    // Calculate CRI contribution
    private double calculateCriContribution(String filePath, int networkCount) {
        double cri = 0.01; // Noise floor value
        
        // Map common operations (inferred from file extension or database check)
        // Since FileObserver only gives MODIFY/CREATE, we use frequency and 
        // network correlation as primary signals.
        if (networkCount > 0) cri += WEIGHT_NETWORK;
        
        // Operation-specific bumps (requires future deeper syscall integration)
        // Resource-specific CRI bumps
        cri += WEIGHT_CREATE_WRITE * 0.1; // scale to prevent single-event overflow
        
        return cri;
    }
    
    // Efficiency database queries
    private List<JSONObject> queryRecentFileEvents(long start, long end, int uid) {
        return database.queryEventsSince("FILE_SYSTEM", start, uid, 100);
    }
    
    private List<JSONObject> queryRecentNetworkEvents(long start, long end, int uid) {
        return database.queryEventsSince("NETWORK", start, uid, 100);
    }
    
    private List<JSONObject> queryRecentHoneyfileEvents(long start, long end, int uid) {
        return database.queryEventsSince("HONEYFILE_ACCESS", start, uid, 50);
    }
    
    private List<JSONObject> queryRecentLockerEvents(long start, long end) {
        return database.queryEventsSince("LOCKER_SHIELD", start, 50);
    }
    
    // Per-UID behavior profile
    private static class BehaviorProfile {
        int fileModifications = 0;
        int networkConnections = 0;
        int honeyfileAccesses = 0;
        long lastActivity = 0;
    }
}
