package com.dearmoon.shield.detection;

import android.content.Context;
import android.util.Log;
import com.dearmoon.shield.detection.CorrelationResult;

/**
 * Manages the cumulative risk scoring and behavioral correlation.
 */
public class BehaviorMonitor {
    private static final String TAG = "BehaviorMonitor";
    private final BehaviorCorrelationEngine correlationEngine;

    public BehaviorMonitor(Context context) {
        this.correlationEngine = new BehaviorCorrelationEngine(context);
    }

    /**
     * Correlates an event and updates the SPRT state in the provided context.
     * Returns the correlation result for further metadata access.
     */
    public CorrelationResult analyzeBehavior(String filePath, long eventTimestamp, 
                                             int uid, AppSecurityContextManager.AppDetectionContext appCtx) {
        
        // 1. Behavioral Correlation (Historical Context)
        CorrelationResult correlation = correlationEngine.correlateFileEvent(
            filePath, eventTimestamp, uid, 
            appCtx.sprtDetector.getLastResetTimestamp()
        );

        // 2. SPRT Math (Sequential Risk Accumulation)
        long currentTime = System.currentTimeMillis();
        if (appCtx.lastEventTimestamp > 0) {
            double deltaSeconds = (currentTime - appCtx.lastEventTimestamp) / 1000.0;
            // Cap time delta to prevent huge jumps in idle periods
            appCtx.sprtDetector.recordTimePassed(Math.min(deltaSeconds, 5.0));
        }
        
        appCtx.sprtDetector.recordEvent(correlation.getCriContribution());
        appCtx.lastEventTimestamp = currentTime;

        return correlation;
    }

    /**
     * Resets scoring state for a confirmed safe process.
     */
    public void resetScoring(AppSecurityContextManager.AppDetectionContext appCtx, String packageName) {
        if (appCtx.sprtDetector.getCurrentState() == SPRTDetector.SPRTState.ACCEPT_H0) {
            Log.i(TAG, "SPRT Decision for " + packageName + ": Normal behavior confirmed, resetting scores");
            appCtx.sprtDetector.reset();
        }
    }
}
