package com.dearmoon.shield.detection;

import com.dearmoon.shield.data.TelemetryEvent;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pseudo-Kernel Detection Layer: Correlation Result
 * 
 * REUSE STRATEGY:
 * - Extends existing TelemetryEvent base class
 * - Compatible with existing TelemetryStorage routing
 * - Stores in existing EventDatabase (new table)
 */
public class CorrelationResult extends TelemetryEvent {
    private final String filePath;
    private final String packageName;
    private final int uid;
    private final int behaviorScore;
    private final int fileEventCount;
    private final int networkEventCount;
    private final int honeyfileEventCount;
    private final int lockerEventCount;
    
    public CorrelationResult(String filePath, String packageName, int uid, 
                            int behaviorScore, int fileCount, int networkCount,
                            int honeyfileCount, int lockerCount) {
        super("BEHAVIOR_CORRELATION");
        this.filePath = filePath;
        this.packageName = packageName;
        this.uid = uid;
        this.behaviorScore = behaviorScore;
        this.fileEventCount = fileCount;
        this.networkEventCount = networkCount;
        this.honeyfileEventCount = honeyfileCount;
        this.lockerEventCount = lockerCount;
    }
    
    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("filePath", filePath);
        json.put("packageName", packageName);
        json.put("uid", uid);
        json.put("behaviorScore", behaviorScore);
        json.put("fileEventCount", fileEventCount);
        json.put("networkEventCount", networkEventCount);
        json.put("honeyfileEventCount", honeyfileEventCount);
        json.put("lockerEventCount", lockerEventCount);
        json.put("syscallPattern", getSyscallPattern());
        return json;
    }
    
    /**
     * Generate syscall pattern summary
     * REUSES: SyscallMapper for abstraction
     */
    private String getSyscallPattern() {
        StringBuilder pattern = new StringBuilder();
        if (fileEventCount > 0) pattern.append("sys_write(").append(fileEventCount).append(") ");
        if (networkEventCount > 0) pattern.append("sys_connect(").append(networkEventCount).append(") ");
        if (honeyfileEventCount > 0) pattern.append("sys_access(").append(honeyfileEventCount).append(") ");
        return pattern.toString().trim();
    }
    
    public int getBehaviorScore() {
        return behaviorScore;
    }
    
    public boolean isHighRisk() {
        return behaviorScore >= 20; // 20/30 threshold
    }
}
