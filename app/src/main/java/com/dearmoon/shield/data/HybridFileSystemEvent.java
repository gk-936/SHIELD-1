package com.dearmoon.shield.data;

import org.json.JSONException;
import org.json.JSONObject;

// Merged hybrid event
public class HybridFileSystemEvent extends FileSystemEvent {
    private final String source; // MODE_A, MODE_B, MERGED
    private final boolean merged;

    public HybridFileSystemEvent(FileSystemEvent base, String source, boolean merged) {
        super(base.getFilePath(), base.getOperation(), base.getFileSizeBefore(), base.getFileSizeAfter());
        this.setUid(base.getUid());
        this.setPackageName(base.getPackageName());
        this.setAppLabel(base.getAppLabel());
        this.source = source;
        this.merged = merged;
    }

    public String getSource() { return source; }
    public boolean isMerged() { return merged; }

    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        try {
            json.put("source", source);
            json.put("mergeFlag", merged ? 1 : 0);
        } catch (JSONException ignored) {}
        return json;
    }
}
