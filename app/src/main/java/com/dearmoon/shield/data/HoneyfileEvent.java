package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public class HoneyfileEvent extends TelemetryEvent {
    private String filePath;
    private String accessType;

    public HoneyfileEvent(String filePath, String accessType, int uid, String pkg) {
        super("HONEYFILE_ACCESS");
        this.filePath = filePath;
        this.accessType = accessType;
        this.uid = uid;
        this.packageName = pkg;
    }

    @Override
    public JSONObject toJSON() throws JSONException {
        JSONObject json = getBaseJSON();
        json.put("filePath", filePath);
        json.put("accessType", accessType);
        return json;
    }

    public String getFilePath() { return filePath; }
    public int getUid() { return uid; }
}
