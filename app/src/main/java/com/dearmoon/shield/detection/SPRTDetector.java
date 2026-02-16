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

    public synchronized SPRTState addObservation(double fileModificationRate) {
        // Don't reset on decision - keep accumulating evidence
        // Calculate log-likelihood ratio increment
        // Using Poisson distribution: log(P(k|λ₁)/P(k|λ₀)) = k·log(λ₁/λ₀) + (λ₀ - λ₁)
        // where k = number of events observed in time window
        // fileModificationRate is events/sec, so multiply by 1 sec to get count
        int eventCount = (int) Math.round(fileModificationRate);
        
        double logLR = eventCount * Math.log(RANSOMWARE_RATE / NORMAL_RATE) 
                     + (NORMAL_RATE - RANSOMWARE_RATE);
        
        logLikelihoodRatio += logLR;
        sampleCount++;

        // Check decision boundaries
        if (logLikelihoodRatio >= Math.log(B)) {
            currentState = SPRTState.ACCEPT_H1;
        } else if (logLikelihoodRatio <= Math.log(A)) {
            currentState = SPRTState.ACCEPT_H0;
        } else {
            currentState = SPRTState.CONTINUE;
        }

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
