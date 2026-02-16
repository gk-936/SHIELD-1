package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public abstract class TelemetryEvent {
    protected long timestamp;
    protected String eventType;
    protected String mode = "MODE_B";

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
        return json;
    }

    public long getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
}
