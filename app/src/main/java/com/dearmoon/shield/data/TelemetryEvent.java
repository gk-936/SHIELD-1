package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public abstract class TelemetryEvent {
    protected long timestamp;
    protected String eventType;
    protected String mode = "MODE_B";
    protected int uid = -1;
    protected String packageName = "unknown";
    protected String appLabel = "unknown";

    public TelemetryEvent(String eventType) {
        this.timestamp = System.currentTimeMillis();
        this.eventType = eventType;
    }

    public abstract JSONObject toJSON() throws JSONException;

    protected JSONObject getBaseJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("timestamp", timestamp);
        json.put("eventType", eventType);
        json.put("mode", mode);
        json.put("uid", uid);
        json.put("packageName", packageName);
        json.put("appLabel", appLabel);
        return json;
    }

    public long getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
    public int getUid() { return uid; }
    public void setUid(int uid) { this.uid = uid; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getAppLabel() { return appLabel; }
    public void setAppLabel(String appLabel) { this.appLabel = appLabel; }
}
