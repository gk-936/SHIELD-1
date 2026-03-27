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
        
        // 1. Mass File Activity (High frequency is inherently suspicious)
        if (fileCount >= 20) score += 30;
        else if (fileCount >= 10) score += 20;
        else if (fileCount >= 5) score += 10;

        // 2. Behavioral Correlation (File + Network)
        if (fileCount > 3 && networkCount > 0) {
            score += 15; // Encryption + C2 Leak pattern
        }
        
        // 3. Honeyfile access (DIRECT evidence of malicious traversal)
        if (honeyfileCount > 0) {
            score += 40; // High confidence trigger
        }
        
        // 4. UI threat (Locker behavior)
        if (lockerCount > 0) {
            score += 20;
        }
        
        return Math.min(score, 50); // Cap at 50 points (UnifiedDetectionEngine adds the rest)
    }

    // Calculate CRI contribution (Sequential probability weight)
    private double calculateCriContribution(String filePath, int networkCount) {
        // Base weight corresponds to the inherent risk of a single file modification
        double cri = WEIGHT_CREATE_WRITE; 
        
        // Boost if network activity is correlated (C2 signaling)
        if (networkCount > 0) {
            cri += WEIGHT_NETWORK;
        }
        
        // Future: add entropy-based feedback here if needed
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
