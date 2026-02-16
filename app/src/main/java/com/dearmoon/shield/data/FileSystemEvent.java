package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

public class FileSystemEvent extends TelemetryEvent {
    private String filePath;
    private String fileExtension;
    private String operation;
    private long fileSizeBefore;
    private long fileSizeAfter;

    public FileSystemEvent(String filePath, String operation, long sizeBefore, long sizeAfter) {
        super("FILE_SYSTEM");
        this.filePath = filePath;
        this.operation = operation;
        this.fileSizeBefore = sizeBefore;
        this.fileSizeAfter = sizeAfter;
        this.fileExtension = extractExtension(filePath);
    }

    private String extractExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot) : "";
    }

    @Override
    public JSONObject toJSON() {
        try {
            JSONObject json = getBaseJSON();
            json.put("filePath", filePath);
            json.put("fileExtension", fileExtension);
            json.put("operation", operation);
            json.put("fileSizeBefore", fileSizeBefore);
            json.put("fileSizeAfter", fileSizeAfter);
            return json;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public String getFilePath() {
        return filePath;
    }

    public String getOperation() {
        return operation;
    }
}
