package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public class AccessibilityEventData extends TelemetryEvent {
    private String packageName;
    private String className;
    private int eventTypeCode;

    public AccessibilityEventData(String packageName, String className, int eventType) {
        super("ACCESSIBILITY");
        this.packageName = packageName;
        this.className = className;
        this.eventTypeCode = eventType;
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("packageName", packageName);
        json.put("className", className);
        json.put("eventTypeCode", eventTypeCode);
        return json;
    }
}
