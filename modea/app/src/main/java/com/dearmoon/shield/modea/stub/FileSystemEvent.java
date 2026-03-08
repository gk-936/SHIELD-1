package com.dearmoon.shield.modea.stub;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * STUB — mirrors com.dearmoon.shield.data.FileSystemEvent.
 *
 * Identical field layout to the real class. The standalone APK uses this
 * while the real SHIELD analysis engine is absent.
 *
 * When merging Mode-A into the main SHIELD project:
 *   1. Delete this file.
 *   2. Update imports in ModeAFileCollector to use
 *      com.dearmoon.shield.data.FileSystemEvent instead.
 */
public class FileSystemEvent extends TelemetryEvent {
    private final String filePath;
    private final String fileExtension;
    private final String operation;
    private final long   fileSizeBefore;
    private final long   fileSizeAfter;

    public FileSystemEvent(String filePath, String operation,
                           long sizeBefore, long sizeAfter) {
        super("FILE_SYSTEM");
        this.filePath       = filePath;
        this.operation      = operation;
        this.fileSizeBefore = sizeBefore;
        this.fileSizeAfter  = sizeAfter;
        this.fileExtension  = extractExtension(filePath);
    }

    private static String extractExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(dot) : "";
    }

    @Override
    public JSONObject toJSON() {
        try {
            JSONObject json = getBaseJSON();
            json.put("filePath",       filePath);
            json.put("fileExtension",  fileExtension);
            json.put("operation",      operation);
            json.put("fileSizeBefore", fileSizeBefore);
            json.put("fileSizeAfter",  fileSizeAfter);
            return json;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public String getFilePath()    { return filePath; }
    public String getOperation()   { return operation; }
    public String getFileExtension(){ return fileExtension; }
}
