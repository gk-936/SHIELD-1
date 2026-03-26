package com.dearmoon.shield.detection;

public class SPRTDetector {
    private static final double ALPHA = 0.05; // False positive rate
    private static final double BETA = 0.05;  // False negative rate
    
    private static final double A = BETA / (1 - ALPHA);
    private static final double B = (1 - BETA) / ALPHA;

    // Minimum event samples
    private static final int MIN_SAMPLES_FOR_H1 = 5;
    
    private double logLikelihoodRatio = 0.0;
    private int sampleCount = 0;
    private SPRTState currentState = SPRTState.CONTINUE;
    // M-02: Track reset boundary so BehaviorCorrelationEngine can exclude pre-reset events
    private long lastResetTimestamp = System.currentTimeMillis();
    private int epoch = 0;
    private double cumulativeRisk = 0.0;

    // Thresholds for file modification rate
    private static final double NORMAL_RATE     = 0.0265;
    private static final double RANSOMWARE_RATE = 0.0766;

    public enum SPRTState {
        CONTINUE,
        ACCEPT_H0,  // Normal behavior
        ACCEPT_H1   // Ransomware behavior
    }

    // Record single event
    public synchronized void recordEvent(double riskWeight) {
        logLikelihoodRatio += riskWeight * Math.log(RANSOMWARE_RATE / NORMAL_RATE);
        cumulativeRisk += riskWeight;
        sampleCount++;
        updateState();
        // H-03: Debug telemetry to help measure baseline on real devices
        if (android.os.Build.VERSION.SDK_INT > 0 /* always */ && sampleCount % 5 == 0) {
            android.util.Log.d("SPRTDetector",
                "SPRT_METRIC samples=" + sampleCount
                + " llr=" + String.format(java.util.Locale.US, "%.4f", logLikelihoodRatio)
                + " cri=" + String.format(java.util.Locale.US, "%.4f", riskWeight)
                + " state=" + currentState);
        }
    }

    // Records elapsed time
    public synchronized void recordTimePassed(double seconds) {
        logLikelihoodRatio += (NORMAL_RATE - RANSOMWARE_RATE) * seconds;
        updateState();
    }

    private void updateState() {
        // Check decision boundaries.
        // ACCEPT_H1 is gated on MIN_SAMPLES_FOR_H1 to prevent the single-event
        // false trigger caused by log(λ₁/λ₀) > log(B) when λ₁/λ₀ is large.
        if (logLikelihoodRatio >= Math.log(B) && sampleCount >= MIN_SAMPLES_FOR_H1) {
            currentState = SPRTState.ACCEPT_H1;
        } else if (logLikelihoodRatio <= Math.log(A)) {
            currentState = SPRTState.ACCEPT_H0;
        } else {
            currentState = SPRTState.CONTINUE;
        }
    }

    @Deprecated
    public synchronized SPRTState addObservation(double fileModificationRate) {
        // Legacy method for compatibility - now implements correct math for 1s window
        recordEvent(1.0); // This is not quite right for the old API but we are moving away from it
        recordTimePassed(1.0);
        return currentState;
    }

    public synchronized void reset() {
        logLikelihoodRatio = 0.0;
        sampleCount = 0;
        currentState = SPRTState.CONTINUE;
        // M-02: Record reset boundary so BehaviorCorrelationEngine filters old events
        lastResetTimestamp = System.currentTimeMillis();
        epoch++;
    }

    // Get last reset
    public synchronized long getLastResetTimestamp() { return lastResetTimestamp; }

    // Get current epoch
    public synchronized int getEpoch() { return epoch; }

    public SPRTState getCurrentState() {
        return currentState;
    }

    public double getLogLikelihoodRatio() {
        return logLikelihoodRatio;
    }

    public int getSampleCount() {
        return sampleCount;
    }
}
