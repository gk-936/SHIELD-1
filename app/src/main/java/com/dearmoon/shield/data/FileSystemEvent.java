package com.dearmoon.shield.data;

import com.dearmoon.shield.utils.PathNormalizer;
import org.json.JSONException;
import org.json.JSONObject;

public class FileSystemEvent extends TelemetryEvent {
    // Human-readable file identifier
    private String filePath;
    private String fileExtension;
    private String operation;
    private long fileSizeBefore;
    private long fileSizeAfter;

    // Optional Scoped Storage fields
    private String fileUri;       // e.g. content://media/external/file/123
    private String displayName;   // MediaStore DISPLAY_NAME
    private String relativePath;  // MediaStore RELATIVE_PATH (e.g. "DCIM/Camera/")

    // Resolved filesystem path
    private String resolvedPath;

    public FileSystemEvent(String filePath, String operation, long sizeBefore, long sizeAfter) {
        super("FILE_SYSTEM");
        this.filePath = filePath;
        this.operation = operation;
        this.fileSizeBefore = sizeBefore;
        this.fileSizeAfter = sizeAfter;
        this.resolvedPath = PathNormalizer.normalize(filePath);
        this.fileExtension = extractExtension(filePath);
    }

    private String extractExtension(String path) {
        if (path == null) return "";
        int lastDot = path.lastIndexOf('.');
        return lastDot > 0 ? path.substring(lastDot) : "";
    }

    @Override
    public JSONObject toJSON() {
        try {
            JSONObject json = getBaseJSON();
            json.put("filePath", filePath);
            json.put("resolvedPath", resolvedPath);
            json.put("fileUri", fileUri);
            json.put("displayName", displayName);
            json.put("relativePath", relativePath);
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

    // Best-effort display string
    public String getDisplayPath() {
        if (relativePath != null && !relativePath.isEmpty()
                && displayName != null && !displayName.isEmpty()) {
            return relativePath + displayName;
        }
        if (displayName != null && !displayName.isEmpty()) return displayName;
        if (filePath != null && !filePath.isEmpty()) return filePath;
        if (fileUri != null && !fileUri.isEmpty()) return fileUri;
        return "Unknown";
    }

    // Real filesystem path
    public String getResolvedPath() { return resolvedPath; }
    public void setResolvedPath(String resolvedPath) {
        this.resolvedPath = PathNormalizer.normalize(resolvedPath);
        // Sync file extension
        String ext = extractExtension(resolvedPath);
        if ((ext == null || ext.isEmpty()) && displayName != null) ext = extractExtension(displayName);
        if (ext != null && !ext.isEmpty()) this.fileExtension = ext;
    }

    public String getFileUri() { return fileUri; }
    public void setFileUri(String fileUri) { this.fileUri = fileUri; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        String ext = extractExtension(displayName);
        if (ext != null && !ext.isEmpty()) this.fileExtension = ext;
    }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public String getOperation() {
        return operation;
    }

    public long getFileSizeBefore() {
        return fileSizeBefore;
    }

    public long getFileSizeAfter() {
        return fileSizeAfter;
    }

    // EventMerger compatibility PID
    public int getPid() {
        return -1;
    }
}
