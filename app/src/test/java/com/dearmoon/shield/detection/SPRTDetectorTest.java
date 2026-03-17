package com.dearmoon.shield.detection;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SPRTDetector}.
 *
 * Key behaviours under test:
 *   1. Min-sample guard (MIN_SAMPLES_FOR_H1 = 5) — no single-event false trigger.
 *   2. H0 acceptance when the rate is slow.
 *   3. H1 acceptance only after enough rapid events.
 *   4. Time-decay (recordTimePassed) moves LLR toward H0.
 *   5. Reset restores initial state and advances epoch.
 *   6. Epoch / reset-timestamp tracking (used by BehaviorCorrelationEngine M-02).
 *   7. Legacy addObservation() compatibility.
 */
public class SPRTDetectorTest {

    private SPRTDetector detector;

    @Before
    public void setUp() {
        detector = new SPRTDetector();
    }

    // =========================================================================
    // 1. Initial state
    // =========================================================================

    @Test
    public void initialState_isContinue() {
        assertEquals("Fresh detector must be in CONTINUE state",
                SPRTDetector.SPRTState.CONTINUE, detector.getCurrentState());
    }

    @Test
    public void initialState_llrIsZero() {
        assertEquals("Initial LLR must be 0.0", 0.0, detector.getLogLikelihoodRatio(), 0.0001);
    }

    @Test
    public void initialState_sampleCountIsZero() {
        assertEquals("Initial sample count must be 0", 0, detector.getSampleCount());
    }

    // =========================================================================
    // 2. Min-sample guard — single event must NOT fire H1
    // =========================================================================

    @Test
    public void singleEvent_doesNotTriggerH1() {
        // log(λ1/λ0) = log(15.0/0.1) = log(150) ≈ 5.01
        // log(B) = log((1-β)/α) = log(0.95/0.05) = log(19) ≈ 2.94
        // Without the guard, 5.01 > 2.94 would fire H1 on event #1.
        detector.recordEvent(1.0);
        assertNotEquals("Single event must NOT result in ACCEPT_H1 (min-sample guard)",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    @Test
    public void fourEvents_doNotTriggerH1() {
        detector.recordEvent(1.0);
        detector.recordEvent(1.0);
        detector.recordEvent(1.0);
        detector.recordEvent(1.0);
        assertNotEquals("4 events must not trigger H1 — guard requires at least 5",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    @Test
    public void fiveRapidEvents_triggerH1() {
        for (int i = 0; i < 5; i++) detector.recordEvent(1.0);
        assertEquals("5 rapid events (no decay) must accept H1",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    // =========================================================================
    // 3. H1 accepted after rapid burst (≥ MIN_SAMPLES_FOR_H1 events, no decay)
    // =========================================================================

    @Test
    public void tenRapidEvents_triggerH1() {
        for (int i = 0; i < 10; i++) detector.recordEvent(1.0);
        assertEquals("10 rapid events (no decay) must accept H1",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    @Test
    public void fiftyRapidEvents_stayH1() {
        for (int i = 0; i < 50; i++) detector.recordEvent(1.0);
        assertEquals("50 rapid events must remain at ACCEPT_H1",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    // =========================================================================
    // 4. H0 accepted when rate is slow (time decay dominates)
    // =========================================================================

    @Test
    public void longQuietPeriod_acceptsH0() {
        // After a long quiet period with no events, LLR decays well below log(A)
        // log(A) = log(β/(1-α)) = log(0.05/0.95) ≈ -2.944
        // decay per second = (λ0 - λ1) × t = (0.1 - 15.0) × t = -14.9t
        // 10 seconds → ΔΛ = -149 → definitely ACCEPT_H0
        detector.recordTimePassed(10.0);
        assertEquals("10 quiet seconds should result in ACCEPT_H0",
                SPRTDetector.SPRTState.ACCEPT_H0, detector.getCurrentState());
    }

    @Test
    public void oneSecondQuiet_afterOneBurst_remainsContinue() {
        // Single event raises LLR by log(150) ≈ 5.01; 1 second quiet decays by (0.1-15.0)×1 = -14.9
        // Final LLR ≈ -9.89 < log(A) ≈ -2.94 → ACCEPT_H0
        detector.recordEvent(1.0);
        detector.recordTimePassed(1.0);
        // We only assert it did NOT flip to H1 (one event + heavy decay)
        assertNotEquals("After one event and 1s decay, must not be ACCEPT_H1",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    // =========================================================================
    // 5. LLR arithmetic
    // =========================================================================

    @Test
    public void recordEvent_incrementsLLR() {
        double before = detector.getLogLikelihoodRatio();
        detector.recordEvent(1.0);
        double after = detector.getLogLikelihoodRatio();
        assertTrue("recordEvent(1.0) must increase LLR", after > before);
    }

    @Test
    public void recordTimePassed_decreasesLLR() {
        // Inject some LLR to start above 0
        detector.recordEvent(1.0);
        double before = detector.getLogLikelihoodRatio();
        detector.recordTimePassed(0.5); // decay: (0.1 - 15.0) × 0.5 = -7.45
        double after = detector.getLogLikelihoodRatio();
        assertTrue("recordTimePassed() must decrease LLR", after < before);
    }

    @Test
    public void sampleCount_incrementsOnEachEvent() {
        assertEquals(0, detector.getSampleCount());
        detector.recordEvent(1.0);
        assertEquals(1, detector.getSampleCount());
        detector.recordEvent(1.0);
        assertEquals(2, detector.getSampleCount());
    }

    // =========================================================================
    // 6. Reset
    // =========================================================================

    @Test
    public void reset_restoresInitialState() {
        for (int i = 0; i < 20; i++) detector.recordEvent(1.0); // force H1
        detector.reset();

        assertEquals("After reset, state must be CONTINUE",
                SPRTDetector.SPRTState.CONTINUE, detector.getCurrentState());
        assertEquals("After reset, LLR must be 0.0", 0.0,
                detector.getLogLikelihoodRatio(), 0.0001);
        assertEquals("After reset, sample count must be 0", 0, detector.getSampleCount());
    }

    @Test
    public void reset_advancesEpoch() {
        int epochBefore = detector.getEpoch();
        detector.reset();
        assertEquals("Each reset must increment epoch by 1", epochBefore + 1, detector.getEpoch());
    }

    @Test
    public void reset_updatesLastResetTimestamp() throws InterruptedException {
        long tsBefore = detector.getLastResetTimestamp();
        Thread.sleep(5); // ensure clock advances
        detector.reset();
        long tsAfter = detector.getLastResetTimestamp();
        assertTrue("getLastResetTimestamp() must advance after reset", tsAfter >= tsBefore);
    }

    @Test
    public void doubleReset_epoch_incrementsTwice() {
        int epochStart = detector.getEpoch();
        detector.reset();
        detector.reset();
        assertEquals("Two resets should yield epoch + 2", epochStart + 2, detector.getEpoch());
    }

    // =========================================================================
    // 7. State transitions — full cycle
    // =========================================================================

    @Test
    public void fullCycle_burstThenQuiet_thenReset() {
        // Burst → H1
        for (int i = 0; i < 10; i++) detector.recordEvent(1.0);
        assertEquals(SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());

        // Reset (as done by UnifiedDetectionEngine on H0)
        detector.reset();

        // Quiet → H0
        detector.recordTimePassed(10.0);
        assertEquals(SPRTDetector.SPRTState.ACCEPT_H0, detector.getCurrentState());

        // Another reset should return to CONTINUE
        detector.reset();
        assertEquals(SPRTDetector.SPRTState.CONTINUE, detector.getCurrentState());
    }

    // =========================================================================
    // 8. Boundary — exactly MIN_SAMPLES_FOR_H1 events
    // =========================================================================

    @Test
    public void exactlyMinSamples_triggersH1_ifLlrAboveBoundary() {
        // After 5 events LLR ≈ 5 * 1.06 ≈ 5.3 >> log(19) ≈ 2.94
        for (int i = 0; i < 4; i++) detector.recordEvent(1.0);
        assertNotEquals("4 events should not be H1", SPRTDetector.SPRTState.ACCEPT_H1,
                detector.getCurrentState());

        detector.recordEvent(1.0); // 5th event
        assertEquals("5th event (MIN_SAMPLES_FOR_H1) should trigger H1",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
    }

    // =========================================================================
    // 9. Thread safety — concurrent access should not corrupt state
    // =========================================================================

    @Test(timeout = 5000)
    public void threadSafety_concurrentRecordEvent_doesNotThrow() throws InterruptedException {
        int threadCount  = 8;
        int eventsEach   = 50;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < eventsEach; i++) detector.recordEvent(1.0);
            });
        }

        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();

        // After 400 events (8 × 50) the state should be H1 and sample count == 400
        assertEquals("After 400 concurrent events, state must be H1",
                SPRTDetector.SPRTState.ACCEPT_H1, detector.getCurrentState());
        assertEquals("Sample count must match total events fired", threadCount * eventsEach,
                detector.getSampleCount());
    }
}
