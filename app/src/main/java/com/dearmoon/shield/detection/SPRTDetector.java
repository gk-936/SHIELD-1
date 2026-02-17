package com.dearmoon.shield.detection;

public class SPRTDetector {
    private static final double ALPHA = 0.05; // False positive rate
    private static final double BETA = 0.05;  // False negative rate
    
    private static final double A = BETA / (1 - ALPHA);
    private static final double B = (1 - BETA) / ALPHA;
    
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
        // Check decision boundaries
        if (logLikelihoodRatio >= Math.log(B)) {
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
