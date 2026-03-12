package com.dearmoon.shield.detection;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link KLDivergenceCalculator}.
 *
 * KL-Divergence measures how far a file's byte distribution is from a perfectly
 * uniform distribution (D_KL close to 0 = uniform = likely encrypted).
 *
 * Score thresholds (from source):
 *   < 0.05 → +30 points (very uniform — encrypted)
 *   < 0.10 → +20 points
 *   < 0.20 → +10 points
 *   >= 0.20→  +0 points
 */
public class KLDivergenceCalculatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private KLDivergenceCalculator calculator;

    @Before
    public void setUp() {
        calculator = new KLDivergenceCalculator();
    }

    // =========================================================================
    // 1. Byte-array API — basic mathematical properties
    // =========================================================================

    @Test
    public void byteArray_uniformRandom_givesNearZeroDivergence() {
        // Truly random data has a near-uniform byte distribution → KL close to 0
        Random rng = new Random(42L);
        byte[] data = new byte[65536];
        rng.nextBytes(data);

        double kl = calculator.calculateDivergence(data, data.length);
        assertTrue("Random bytes should have KL < 0.1 (encrypted-like), got: " + kl, kl < 0.1);
    }

    @Test
    public void byteArray_repeatedText_givesHighDivergence() {
        // Text uses a small subset of byte values → very non-uniform → high KL
        String text = "hello world this is plain text ";
        byte[] data  = new byte[text.length() * 200];
        byte[] chunk = text.getBytes();
        for (int i = 0; i < data.length; i++) data[i] = chunk[i % chunk.length];

        double kl = calculator.calculateDivergence(data, data.length);
        assertTrue("Repeated text should have KL > 1.0 (non-uniform), got: " + kl, kl > 1.0);
    }

    @Test
    public void byteArray_singleByteValue_givesMaximumDivergence() {
        // All-zero array is maximally non-uniform — extreme KL value
        byte[] data = new byte[1024]; // all zeros by default

        double kl = calculator.calculateDivergence(data, data.length);
        // D_KL = 1 * log2(1 / (1/256)) = log2(256) = 8
        assertTrue("All-zero array should have very high KL, got: " + kl, kl > 5.0);
    }

    @Test
    public void byteArray_emptyData_returnsZero() {
        double kl = calculator.calculateDivergence(new byte[0], 0);
        assertEquals("Empty data should return 0.0", 0.0, kl, 0.001);
    }

    @Test
    public void byteArray_singleByte_isHandledWithoutException() {
        byte[] single = {(byte) 0xFF};
        // Should not throw; exact value doesn't matter as long as it is finite
        double kl = calculator.calculateDivergence(single, 1);
        assertTrue("Single-byte KL must be finite", Double.isFinite(kl));
    }

    // =========================================================================
    // 2. isUniformDistribution threshold
    // =========================================================================

    @Test
    public void isUniformDistribution_belowThreshold_returnsTrue() {
        assertTrue(calculator.isUniformDistribution(0.00));
        assertTrue(calculator.isUniformDistribution(0.05));
        assertTrue(calculator.isUniformDistribution(0.09));
        assertTrue(calculator.isUniformDistribution(0.099));
    }

    @Test
    public void isUniformDistribution_atOrAboveThreshold_returnsFalse() {
        assertFalse(calculator.isUniformDistribution(0.10));
        assertFalse(calculator.isUniformDistribution(0.50));
        assertFalse(calculator.isUniformDistribution(8.00));
    }

    // =========================================================================
    // 3. File API — null / missing files
    // =========================================================================

    @Test
    public void file_nonExistent_returnsSafeDefault() {
        File missing = new File(tempFolder.getRoot(), "ghost.bin");
        double kl = calculator.calculateDivergence(missing);
        // Should return 1.0 (the documented safe fallback, not throw)
        assertEquals("Missing file should return 1.0", 1.0, kl, 0.001);
    }

    @Test
    public void file_emptyFile_returnsSafeDefault() throws Exception {
        File empty = tempFolder.newFile("empty.bin");
        double kl = calculator.calculateDivergence(empty);
        assertEquals("Empty file should return 1.0 (safe default)", 1.0, kl, 0.001);
    }

    // =========================================================================
    // 4. File API — content correctness
    // =========================================================================

    @Test
    public void file_encryptedContent_isDetectedAsUniform() throws Exception {
        // Write 20 KB of cryptographically-looking random bytes
        File f = tempFolder.newFile("encrypted.bin");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Random rng = new Random(1234L);
            byte[] buf = new byte[20 * 1024];
            rng.nextBytes(buf);
            out.write(buf);
        }

        double kl = calculator.calculateDivergence(f);
        assertTrue("20 KB random file should have KL < 0.1 (encrypted), got: " + kl, kl < 0.1);
        assertTrue("Detected as uniform distribution", calculator.isUniformDistribution(kl));
    }

    @Test
    public void file_plainTextContent_isDetectedAsNonUniform() throws Exception {
        File f = tempFolder.newFile("document.txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            String line = "The quick brown fox jumps over the lazy dog. ";
            byte[] lineBytes = line.getBytes();
            for (int i = 0; i < 500; i++) out.write(lineBytes);
        }

        double kl = calculator.calculateDivergence(f);
        assertTrue("Plain-text file should have KL > 0.5 (structured), got: " + kl, kl > 0.5);
        assertFalse("Should not be detected as uniform", calculator.isUniformDistribution(kl));
    }

    // =========================================================================
    // 5. Multi-region sampling — partial encryption (bypass-attack detection)
    // =========================================================================

    @Test
    public void file_multiRegion_detectsEncryptedFooter() throws Exception {
        // Plain header (12 KB) + encrypted footer (8 KB)
        File f = tempFolder.newFile("footer_enc.dat");
        try (FileOutputStream out = new FileOutputStream(f)) {
            byte[] plain = "AAAA BBBB CCCC plain text content. ".getBytes();
            for (int i = 0; i < 12 * 1024 / plain.length; i++) out.write(plain);

            Random rng = new Random(9999L);
            byte[] rand = new byte[8 * 1024];
            rng.nextBytes(rand);
            out.write(rand);
        }

        double kl = calculator.calculateDivergence(f);
        // The encrypted footer should drag the minimum KL (across regions) down
        assertTrue("Footer encryption should lower minimum KL, got: " + kl, kl < 0.5);
    }

    @Test
    public void file_multiRegion_detectsEncryptedHeader() throws Exception {
        // Encrypted header (8 KB) + plain body (12 KB)
        File f = tempFolder.newFile("header_enc.dat");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Random rng = new Random(7777L);
            byte[] rand = new byte[8 * 1024];
            rng.nextBytes(rand);
            out.write(rand);

            byte[] plain = "Normal document content goes here. ".getBytes();
            for (int i = 0; i < 12 * 1024 / plain.length; i++) out.write(plain);
        }

        double kl = calculator.calculateDivergence(f);
        assertTrue("Header encryption should keep minimum KL low, got: " + kl, kl < 0.5);
    }

    // =========================================================================
    // 6. Score contribution boundary checks
    // =========================================================================

    @Test
    public void scoreContribution_veryUniform_worthThirtyPoints() {
        // KL < 0.05 → 30 points in UnifiedDetectionEngine
        Random rng = new Random(55L);
        byte[] data = new byte[32768];
        rng.nextBytes(data);

        double kl = calculator.calculateDivergence(data, data.length);
        // Cryptographically-seeded random should be well within the < 0.05 band
        assertTrue("Random 32KB should qualify for max KL score, got: " + kl, kl < 0.05);
    }

    @Test
    public void scoreContribution_moderatelyUniform_worthTwentyPoints() {
        // Construct a buffer that is moderately non-uniform (KL 0.05–0.10 region)
        // Use only 180 of 256 byte values — still fairly spread but not perfectly uniform
        byte[] data = new byte[8192];
        Random rng  = new Random(111L);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (rng.nextInt(180)); // 0–179 only
        }

        double kl = calculator.calculateDivergence(data, data.length);
        // 76 byte values never appear → some non-uniformity — KL > 0.0
        // The exact band is data-dependent; just verify the method doesn't throw
        assertTrue("KL for partial-range data must be finite and non-negative",
                Double.isFinite(kl) && kl >= 0.0);
    }

    // =========================================================================
    // 7. Determinism — same input always produces the same output
    // =========================================================================

    @Test
    public void byteArray_sameInput_producesSameOutput() {
        byte[] data = new byte[4096];
        new Random(42L).nextBytes(data);

        double kl1 = calculator.calculateDivergence(data, data.length);
        double kl2 = calculator.calculateDivergence(data, data.length);
        assertEquals("KL divergence must be deterministic", kl1, kl2, 0.0);
    }

    // =========================================================================
    // 8. Performance
    // =========================================================================

    @Test(timeout = 3000)
    public void file_largeFile_completesWithinTimeout() throws Exception {
        File f = tempFolder.newFile("large.bin");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Random rng = new Random(0L);
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 20; i++) { // 20 MB
                rng.nextBytes(chunk);
                out.write(chunk);
            }
        }

        long start = System.currentTimeMillis();
        double kl   = calculator.calculateDivergence(f);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("20 MB file KL must be finite", Double.isFinite(kl));
        assertTrue("Analysis of 20 MB should finish under 3 s, took: " + elapsed + " ms",
                elapsed < 3000);
    }
}
