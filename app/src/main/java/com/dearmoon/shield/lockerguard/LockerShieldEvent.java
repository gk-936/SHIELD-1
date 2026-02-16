package com.dearmoon.shield.lockerguard;

import org.json.JSONException;
import org.json.JSONObject;
import com.dearmoon.shield.data.TelemetryEvent;

public class LockerShieldEvent extends TelemetryEvent {
    private String packageName;
    private String threatType;
    private int riskScore;
    private String details;

    public LockerShieldEvent(String packageName, String threatType, int riskScore, String details) {
        super("LOCKER_SHIELD");
        this.packageName = packageName;
        this.threatType = threatType;
        this.riskScore = riskScore;
        this.details = details;
    }

    @Override
    public JSONObject toJSON() {
        try {
            JSONObject json = getBaseJSON();
            json.put("packageName", packageName);
            json.put("threatType", threatType);
            json.put("riskScore", riskScore);
            json.put("details", details);
            return json;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public String getPackageName() {
        return packageName;
    }

    public int getRiskScore() {
        return riskScore;
    }
}
