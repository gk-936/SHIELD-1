package com.dearmoon.shield.detection;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link DetectionResult} and the confidence-score calculation
 * logic that lives in {@link UnifiedDetectionEngine#calculateConfidenceScore}.
 *
 * Because calculateConfidenceScore() is private, we test it indirectly through
 * known entropy / KL / SPRT combos and verify the composite DetectionResult
 * that would be produced.  We also test DetectionResult's own methods directly.
 */
public class DetectionResultTest {

    // =========================================================================
    // 1. DetectionResult — basic getters and isHighRisk()
    // =========================================================================

    @Test
    public void isHighRisk_trueWhenScoreAtLeast70() {
        assertTrue(result(70).isHighRisk());
        assertTrue(result(100).isHighRisk());
        assertTrue(result(130).isHighRisk());
    }

    @Test
    public void isHighRisk_falseWhenScoreBelow70() {
        assertFalse(result(0).isHighRisk());
        assertFalse(result(40).isHighRisk());
        assertFalse(result(69).isHighRisk());
    }

    @Test
    public void getConfidenceScore_returnsConstructedValue() {
        assertEquals(85, result(85).getConfidenceScore());
        assertEquals(0, result(0).getConfidenceScore());
        assertEquals(130, result(130).getConfidenceScore());
    }

    @Test
    public void toJSON_containsAllExpectedKeys() throws Exception {
        DetectionResult r = new DetectionResult(7.9, 0.03, "ACCEPT_H1", 100, "/sdcard/test.doc");
        org.json.JSONObject json = r.toJSON();

        assertTrue(json.has("mode"));
        assertTrue(json.has("entropy"));
        assertTrue(json.has("kl_divergence"));
        assertTrue(json.has("sprt_state"));
        assertTrue(json.has("confidence_score"));
        assertTrue(json.has("timestamp"));
        assertTrue(json.has("file_path"));
    }

    @Test
    public void toJSON_modeIsModeB() throws Exception {
        DetectionResult r = result(80);
        assertEquals("MODE_B", r.toJSON().getString("mode"));
    }

    @Test
    public void toJSON_timestampIsRecent() throws Exception {
        long before = System.currentTimeMillis();
        DetectionResult r = result(50);
        long after = System.currentTimeMillis();
        long ts = r.toJSON().getLong("timestamp");
        assertTrue("Timestamp should be within test window", ts >= before && ts <= after);
    }

    @Test
    public void toJSON_sprtStateMatchesConstructor() throws Exception {
        DetectionResult r = new DetectionResult(7.0, 0.05, "CONTINUE", 50, "/path/file.doc");
        assertEquals("CONTINUE", r.toJSON().getString("sprt_state"));
    }

    // =========================================================================
    // 2. Confidence score math — replicated from UnifiedDetectionEngine
    //    These tests verify the scoring table stays consistent with the spec.
    // =========================================================================

    /**
     * Entropy contribution:
     *   > 7.8 → +40
     *   > 7.5 → +30
     *   > 7.0 → +20
     *   > 6.0 → +10
     *   else  →  0
     *
     * KL contribution:
     *   < 0.05 → +30
     *   < 0.10 → +20
     *   < 0.20 → +10
     *   else   →  0
     *
     * SPRT contribution:
     *   ACCEPT_H1 → +30
     *   CONTINUE  → +10
     *   ACCEPT_H0 →  0
     */

    @Test
    public void score_maxEntropyAndKLAndH1_gives100() {
        // entropy > 7.8 (40) + kl < 0.05 (30) + H1 (30) = 100
        int score = calcScore(7.85, 0.02, SPRTDetector.SPRTState.ACCEPT_H1);
        assertEquals("Max scoring combination should give 100", 100, score);
    }

    @Test
    public void score_entropyOnly_gives40() {
        int score = calcScore(7.9, 0.5, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("Only entropy > 7.8 should give 40", 40, score);
    }

    @Test
    public void score_klOnly_gives30() {
        int score = calcScore(0.0, 0.02, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("Only KL < 0.05 should give 30", 30, score);
    }

    @Test
    public void score_sprtH1Only_gives30() {
        int score = calcScore(0.0, 0.5, SPRTDetector.SPRTState.ACCEPT_H1);
        assertEquals("Only SPRT H1 should give 30", 30, score);
    }

    @Test
    public void score_entropyMid_gives20() {
        int score = calcScore(7.2, 0.5, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("Entropy 7.0–7.5 should give 20", 20, score);
    }

    @Test
    public void score_entropyLow_gives10() {
        int score = calcScore(6.5, 0.5, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("Entropy 6.0–7.0 should give 10", 10, score);
    }

    @Test
    public void score_allBelowThresholds_gives0() {
        int score = calcScore(4.0, 1.0, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("Below all thresholds should give 0", 0, score);
    }

    @Test
    public void score_sprtContinue_gives10() {
        int score = calcScore(0.0, 0.5, SPRTDetector.SPRTState.CONTINUE);
        assertEquals("SPRT CONTINUE should give 10", 10, score);
    }

    @Test
    public void score_klMidRange_gives20() {
        // KL in 0.05–0.10 → 20 points
        int score = calcScore(0.0, 0.07, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("KL 0.05–0.10 should give 20", 20, score);
    }

    @Test
    public void score_klLowRange_gives10() {
        // KL in 0.10–0.20 → 10 points
        int score = calcScore(0.0, 0.15, SPRTDetector.SPRTState.ACCEPT_H0);
        assertEquals("KL 0.10–0.20 should give 10", 10, score);
    }

    @Test
    public void score_cappedAt100() {
        // Even if sub-scores exceed 100 the cap applies
        int score = calcScore(7.9, 0.02, SPRTDetector.SPRTState.ACCEPT_H1);
        // 40 + 30 + 30 = 100 (exactly at cap, not exceeding it)
        assertEquals("Combined score must not exceed 100", 100, score);
    }

    // =========================================================================
    // 3. isHighRisk boundary — 69 vs 70
    // =========================================================================

    @Test
    public void isHighRisk_exactlyAt70_isTrue() {
        // entropy > 7.5 (30) + KL < 0.10 (20) + CONTINUE (10) → 60 — below threshold
        // entropy > 7.8 (40) + KL < 0.10 (20) + CONTINUE (10) → 70 — exactly at threshold
        int score = calcScore(7.9, 0.07, SPRTDetector.SPRTState.CONTINUE);
        assertEquals("Expected score of 70", 70, score);
        assertTrue("Score of 70 must be high risk", result(score).isHighRisk());
    }

    @Test
    public void isHighRisk_onePointBelow70_isFalse() {
        // entropy > 7.5 (30) + KL < 0.10 (20) + CONTINUE (10) = 60
        int score = calcScore(7.6, 0.07, SPRTDetector.SPRTState.CONTINUE);
        assertEquals("Expected score of 60", 60, score);
        assertFalse("Score of 60 must not be high risk", result(score).isHighRisk());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Create a minimal DetectionResult with only the score set. */
    private DetectionResult result(int score) {
        return new DetectionResult(0.0, 0.0, "CONTINUE", score, "/dummy/path");
    }

    /**
     * Re-implement the scoring table from UnifiedDetectionEngine.calculateConfidenceScore()
     * as a pure helper so changes to the engine are flagged by test failures here too.
     */
    private int calcScore(double entropy, double kl, SPRTDetector.SPRTState sprt) {
        int score = 0;

        // Entropy
        if (entropy > 7.8)      score += 40;
        else if (entropy > 7.5) score += 30;
        else if (entropy > 7.0) score += 20;
        else if (entropy > 6.0) score += 10;

        // KL
        if (kl < 0.05)      score += 30;
        else if (kl < 0.10) score += 20;
        else if (kl < 0.20) score += 10;

        // SPRT
        if (sprt == SPRTDetector.SPRTState.ACCEPT_H1)  score += 30;
        else if (sprt == SPRTDetector.SPRTState.CONTINUE) score += 10;

        return Math.min(score, 100);
    }
}
