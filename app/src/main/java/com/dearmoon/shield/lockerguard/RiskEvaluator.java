package com.dearmoon.shield.lockerguard;

import android.util.Log;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class RiskEvaluator {
    private static final String TAG = "RiskEvaluator";
    private static final int THRESHOLD = 70;
    private static final long TIME_WINDOW = 1000;
    private static final long DECAY_INTERVAL = 5000;
    
    private final Map<String, Integer> packageScores = new HashMap<>();
    private final Map<String, LinkedList<Long>> eventHistory = new HashMap<>();
    private final Map<String, Long> lastDecay = new HashMap<>();

    public synchronized int evaluateRisk(String packageName, String eventType) {
        long now = System.currentTimeMillis();
        
        applyDecay(packageName, now);
        recordEvent(packageName, now);
        
        int score = packageScores.getOrDefault(packageName, 0);
        
        switch (eventType) {
            case "FULLSCREEN_OVERLAY":
                score += 25;
                break;
            case "RAPID_FOCUS_REGAIN":
                score += 30;
                break;
            case "LOCKSCREEN_HIJACK":
                score += 40;
                break;
            case "IMMERSIVE_MODE":
                score += 20;
                break;
            case "HIGH_FREQUENCY":
                score += 15;
                break;
        }
        
        packageScores.put(packageName, Math.min(score, 100));
        
        Log.d(TAG, packageName + " risk: " + score + " (" + eventType + ")");
        return score;
    }

    public boolean isThresholdExceeded(String packageName) {
        return packageScores.getOrDefault(packageName, 0) >= THRESHOLD;
    }

    public void reset(String packageName) {
        packageScores.remove(packageName);
        eventHistory.remove(packageName);
        lastDecay.remove(packageName);
    }

    private void applyDecay(String packageName, long now) {
        Long last = lastDecay.get(packageName);
        if (last != null && now - last > DECAY_INTERVAL) {
            int score = packageScores.getOrDefault(packageName, 0);
            packageScores.put(packageName, Math.max(0, score - 10));
            lastDecay.put(packageName, now);
        } else if (last == null) {
            lastDecay.put(packageName, now);
        }
    }

    private void recordEvent(String packageName, long now) {
        LinkedList<Long> events = eventHistory.computeIfAbsent(packageName, k -> new LinkedList<>());
        events.add(now);
        
        while (!events.isEmpty() && now - events.getFirst() > TIME_WINDOW) {
            events.removeFirst();
        }
    }

    public int getEventFrequency(String packageName) {
        LinkedList<Long> events = eventHistory.get(packageName);
        return events != null ? events.size() : 0;
    }
}
