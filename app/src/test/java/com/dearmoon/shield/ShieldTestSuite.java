package com.dearmoon.shield;

import com.dearmoon.shield.analysis.IncidentFeatureTest;
import com.dearmoon.shield.detection.DetectionResultTest;
import com.dearmoon.shield.detection.EntropyAnalyzerAllowlistTest;
import com.dearmoon.shield.detection.EntropyAnalyzerTest;
import com.dearmoon.shield.detection.KLDivergenceCalculatorTest;
import com.dearmoon.shield.detection.ProcessAttributionTest;
import com.dearmoon.shield.detection.SPRTDetectorTest;
import com.dearmoon.shield.snapshot.SnapshotIntegrityTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Master test suite — runs all SHIELD unit tests in one pass.
 *
 * Run from project root:
 *   ./gradlew test --tests "com.dearmoon.shield.ShieldTestSuite"
 *
 * Or run individual test classes:
 *   ./gradlew test --tests "com.dearmoon.shield.detection.SPRTDetectorTest"
 *
 * All tests in this suite are pure-JVM (no Android device or emulator needed)
 * except IncidentFeatureTest which uses Robolectric.
 *
 * ┌────────────────────────────────────────────────────────┬──────────────────────────────────┐
 * │ Test class                                             │ What it covers                   │
 * ├────────────────────────────────────────────────────────┼──────────────────────────────────┤
 * │ EntropyAnalyzerTest          (existing)                │ Multi-region sampling, bypass    │
 * │ EntropyAnalyzerAllowlistTest (new)                     │ Extension allowlist, M-01 magic  │
 * │ KLDivergenceCalculatorTest   (new)                     │ KL math, uniformity threshold    │
 * │ SPRTDetectorTest             (new)                     │ Min-sample guard, H0/H1, epoch   │
 * │ DetectionResultTest          (new)                     │ Score table, isHighRisk, JSON    │
 * │ ProcessAttributionTest       (new)                     │ Path-regex attribution strategies│
 * │ SnapshotIntegrityTest        (new)                     │ Hash-chain, chain-linking math   │
 * │ IncidentFeatureTest          (existing)                │ DNA report, toCertInText, enums  │
 * └────────────────────────────────────────────────────────┴──────────────────────────────────┘
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        // --- Detection engine ---
        EntropyAnalyzerTest.class,
        EntropyAnalyzerAllowlistTest.class,
        KLDivergenceCalculatorTest.class,
        SPRTDetectorTest.class,
        DetectionResultTest.class,
        ProcessAttributionTest.class,

        // --- Snapshot / integrity ---
        SnapshotIntegrityTest.class,

        // --- Incident analysis (Robolectric) ---
        IncidentFeatureTest.class,
})
public class ShieldTestSuite {
    // Suite runner — no additional code needed
}
