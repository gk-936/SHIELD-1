package com.dearmoon.shield.detection;

import org.json.JSONException;
import org.json.JSONObject;

public class DetectionResult {
    private String mode = "MODE_B";
    private double entropy;
    private double klDivergence;
    private String sprtState;
    private int confidenceScore;
    private long timestamp;
    private String filePath;

    public DetectionResult(double entropy, double klDivergence, String sprtState, 
                          int confidenceScore, String filePath) {
        this.entropy = entropy;
        this.klDivergence = klDivergence;
        this.sprtState = sprtState;
        this.confidenceScore = confidenceScore;
        this.filePath = filePath;
        this.timestamp = System.currentTimeMillis();
    }

    public JSONObject toJSON() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("mode", mode);
        json.put("entropy", String.format("%.2f", entropy));
        json.put("kl_divergence", String.format("%.4f", klDivergence));
        json.put("sprt_state", sprtState);
        json.put("confidence_score", confidenceScore);
        json.put("timestamp", timestamp);
        json.put("file_path", filePath);
        return json;
    }

    public int getConfidenceScore() { return confidenceScore; }
    public boolean isHighRisk() { return confidenceScore >= 70; }
}
