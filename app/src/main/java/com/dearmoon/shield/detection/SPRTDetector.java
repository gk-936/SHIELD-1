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
     * Rationale: with λ₁/λ₀ = 50 and α = β = 0.05 the Wald boundary is
     * log(B) = log(19) ≈ 2.94, while a single event contributes log(50) ≈ 3.91
     * which already exceeds the boundary. Without this guard, SPRT fires ACCEPT_H1
     * on the very first file event of every session, permanently contributing
     * 30 points to every subsequent detection score.
     *
     * Requiring at least 3 events means the detector needs a minimum burst consistent
     * with ransomware behavior (≥ 3 file modifications within the session) before
     * committing to H1.
     */
    private static final int MIN_SAMPLES_FOR_H1 = 3;
    
    private double logLikelihoodRatio = 0.0;
    private int sampleCount = 0;
    private SPRTState currentState = SPRTState.CONTINUE;

    // Thresholds for file modification rate
    private static final double NORMAL_RATE = 0.1;    // H₀: 0.1 files/sec
    private static final double RANSOMWARE_RATE = 5.0; // H₁: 5 files/sec

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
    }

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
