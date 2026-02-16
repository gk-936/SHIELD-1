package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public class HoneyfileEvent extends TelemetryEvent {
    private String filePath;
    private String accessType;
    private int callingUid;
    private String packageName;

    public HoneyfileEvent(String filePath, String accessType, int uid, String pkg) {
        super("HONEYFILE_ACCESS");
        this.filePath = filePath;
        this.accessType = accessType;
        this.callingUid = uid;
        this.packageName = pkg;
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("filePath", filePath);
        json.put("accessType", accessType);
        json.put("callingUid", callingUid);
        json.put("packageName", packageName != null ? packageName : "unknown");
        return json;
    }
}
