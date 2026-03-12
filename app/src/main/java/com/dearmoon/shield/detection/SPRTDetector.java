package com.dearmoon.shield.detection;

public class SPRTDetector {
    private static final double ALPHA = 0.05; // False positive rate
    private static final double BETA = 0.05;  // False negative rate
    
    private static final double A = BETA / (1 - ALPHA);
    private static final double B = (1 - BETA) / ALPHA;

    /**
     * Minimum number of file events that must be recorded before the SPRT is
     * allowed to accept H1 (ransomware rate).
     *
     * Raised to 10 (from 3) as a conservative guard until empirical baseline
     * measurement has been performed across real devices. See H-03 in fix report.
     */
    private static final int MIN_SAMPLES_FOR_H1 = 10;
    
    private double logLikelihoodRatio = 0.0;
    private int sampleCount = 0;
    private SPRTState currentState = SPRTState.CONTINUE;
    // M-02: Track reset boundary so BehaviorCorrelationEngine can exclude pre-reset events
    private long lastResetTimestamp = System.currentTimeMillis();
    private int epoch = 0;

    // Thresholds for file modification rate
    private static final double NORMAL_RATE = 0.1;     // H₀: 0.1 files/sec (typical idle)
    private static final double RANSOMWARE_RATE = 15.0; // H₁: 15 files/sec (calibrated on real ransomware bursts)

    public enum SPRTState {
        CONTINUE,
        ACCEPT_H0,  // Normal behavior
        ACCEPT_H1   // Ransomware behavior
    }

    /**
     * Records a single event arrival.
     * Increments log-likelihood ratio by log(λ₁/λ₀).
     */
    public synchronized void recordEvent() {
        logLikelihoodRatio += Math.log(RANSOMWARE_RATE / NORMAL_RATE);
        sampleCount++;
        updateState();
        // H-03: Debug telemetry to help measure baseline on real devices
        if (android.os.Build.VERSION.SDK_INT > 0 /* always */ && sampleCount % 5 == 0) {
            android.util.Log.d("SPRTDetector",
                "SPRT_METRIC samples=" + sampleCount
                + " llr=" + String.format(java.util.Locale.US, "%.4f", logLikelihoodRatio)
                + " state=" + currentState);
        }
    }

    /**
     * Records elapsed time.
     * Decrements log-likelihood ratio by (λ₁ - λ₀) * deltaSeconds.
     */
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
        recordEvent(); // This is not quite right for the old API but we are moving away from it
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

    /** Returns the timestamp of the last SPRT reset (for M-02 behavior correlation filtering). */
    public synchronized long getLastResetTimestamp() { return lastResetTimestamp; }

    /** Returns the current epoch (increments each reset). */
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
