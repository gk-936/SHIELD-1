package com.dearmoon.shield.detection;

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for EntropyAnalyzer multi-region sampling fix.
 * Tests verify that ransomware cannot bypass detection by encrypting only file footers.
 */
public class EntropyAnalyzerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private EntropyAnalyzer analyzer;

    @Before
    public void setUp() {
        analyzer = new EntropyAnalyzer();
    }

    /**
     * TEST 1: Verify multi-region sampling detects footer encryption
     * Ransomware bypass scenario: Encrypt only the last 8KB of file
     */
    @Test
    public void testFooterEncryptionDetected() throws Exception {
        // Create 20KB file: first 12KB plain text, last 8KB encrypted
        File testFile = tempFolder.newFile("footer_encrypted.dat");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            // First 12KB: plain text (low entropy ~4.5)
            byte[] plainText = "This is plain text content. ".getBytes();
            for (int i = 0; i < 12 * 1024 / plainText.length; i++) {
                fos.write(plainText);
            }
            
            // Last 8KB: random encrypted data (high entropy ~8.0)
            Random random = new Random(12345); // Fixed seed for reproducibility
            byte[] encrypted = new byte[8 * 1024];
            random.nextBytes(encrypted);
            fos.write(encrypted);
        }

        double entropy = analyzer.calculateEntropy(testFile);
        
        // Multi-region sampling should detect high entropy in footer
        assertTrue("Footer encryption should be detected (entropy > 7.0)", entropy > 7.0);
        System.out.println("✅ Footer encryption detected: entropy = " + entropy);
    }

    /**
     * TEST 2: Verify multi-region sampling detects middle encryption
     * Ransomware bypass scenario: Encrypt only the middle section
     */
    @Test
    public void testMiddleEncryptionDetected() throws Exception {
        // Create 30KB file: 8KB plain, 14KB encrypted, 8KB plain
        File testFile = tempFolder.newFile("middle_encrypted.dat");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            byte[] plainText = "Plain text header. ".getBytes();
            
            // First 8KB: plain text
            for (int i = 0; i < 8 * 1024 / plainText.length; i++) {
                fos.write(plainText);
            }
            
            // Middle 14KB: encrypted
            Random random = new Random(54321);
            byte[] encrypted = new byte[14 * 1024];
            random.nextBytes(encrypted);
            fos.write(encrypted);
            
            // Last 8KB: plain text
            for (int i = 0; i < 8 * 1024 / plainText.length; i++) {
                fos.write(plainText);
            }
        }

        double entropy = analyzer.calculateEntropy(testFile);
        
        assertTrue("Middle encryption should be detected (entropy > 7.0)", entropy > 7.0);
        System.out.println("✅ Middle encryption detected: entropy = " + entropy);
    }

    /**
     * TEST 3: Verify full-file analysis for small files (<10MB)
     */
    @Test
    public void testSmallFileFullAnalysis() throws Exception {
        // Create 5MB file with encryption at 4MB offset
        File testFile = tempFolder.newFile("small_encrypted.dat");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            // First 4MB: plain text
            byte[] plainText = "Plain text content. ".getBytes();
            for (int i = 0; i < 4 * 1024 * 1024 / plainText.length; i++) {
                fos.write(plainText);
            }
            
            // Last 1MB: encrypted
            Random random = new Random(99999);
            byte[] encrypted = new byte[1024 * 1024];
            random.nextBytes(encrypted);
            fos.write(encrypted);
        }

        double entropy = analyzer.calculateEntropy(testFile);
        
        // Full-file analysis should detect encryption anywhere
        assertTrue("Small file encryption should be detected", entropy > 6.5);
        System.out.println("✅ Small file encryption detected: entropy = " + entropy);
    }

    /**
     * TEST 4: Verify plain text files have low entropy
     */
    @Test
    public void testPlainTextLowEntropy() throws Exception {
        File testFile = tempFolder.newFile("plaintext.txt");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            String text = "This is a normal text file with regular English content. " +
                         "It should have low entropy because it's not encrypted. " +
                         "Repeating this text multiple times to create a larger file. ";
            for (int i = 0; i < 1000; i++) {
                fos.write(text.getBytes());
            }
        }

        double entropy = analyzer.calculateEntropy(testFile);
        
        assertTrue("Plain text should have low entropy (< 5.5)", entropy < 5.5);
        System.out.println("✅ Plain text low entropy: entropy = " + entropy);
    }

    /**
     * TEST 5: Verify encrypted files have high entropy
     */
    @Test
    public void testEncryptedFileHighEntropy() throws Exception {
        File testFile = tempFolder.newFile("encrypted.bin");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            Random random = new Random(11111);
            byte[] encrypted = new byte[50 * 1024]; // 50KB
            random.nextBytes(encrypted);
            fos.write(encrypted);
        }

        double entropy = analyzer.calculateEntropy(testFile);
        
        assertTrue("Encrypted file should have high entropy (> 7.5)", entropy > 7.5);
        assertTrue("Encrypted file entropy should be near maximum (< 8.1)", entropy < 8.1);
        System.out.println("✅ Encrypted file high entropy: entropy = " + entropy);
    }

    /**
     * TEST 6: Verify isHighEntropy threshold
     */
    @Test
    public void testHighEntropyThreshold() {
        assertTrue("7.6 should be high entropy", analyzer.isHighEntropy(7.6));
        assertTrue("8.0 should be high entropy", analyzer.isHighEntropy(8.0));
        assertFalse("7.4 should not be high entropy", analyzer.isHighEntropy(7.4));
        assertFalse("5.0 should not be high entropy", analyzer.isHighEntropy(5.0));
        System.out.println("✅ High entropy threshold correct");
    }

    /**
     * TEST 7: Verify empty file handling
     */
    @Test
    public void testEmptyFile() throws Exception {
        File testFile = tempFolder.newFile("empty.txt");
        double entropy = analyzer.calculateEntropy(testFile);
        
        assertEquals("Empty file should return 0.0 entropy", 0.0, entropy, 0.01);
        System.out.println("✅ Empty file handled correctly");
    }

    /**
     * TEST 8: Verify non-existent file handling
     */
    @Test
    public void testNonExistentFile() {
        File testFile = new File(tempFolder.getRoot(), "nonexistent.txt");
        double entropy = analyzer.calculateEntropy(testFile);
        
        assertEquals("Non-existent file should return 0.0 entropy", 0.0, entropy, 0.01);
        System.out.println("✅ Non-existent file handled correctly");
    }

    /**
     * TEST 9: Verify byte array entropy calculation
     */
    @Test
    public void testByteArrayEntropy() {
        // All zeros (minimum entropy)
        byte[] zeros = new byte[1000];
        double zeroEntropy = analyzer.calculateEntropy(zeros, zeros.length);
        assertEquals("All zeros should have 0.0 entropy", 0.0, zeroEntropy, 0.01);
        
        // Random bytes (maximum entropy)
        Random random = new Random(77777);
        byte[] randomBytes = new byte[1000];
        random.nextBytes(randomBytes);
        double randomEntropy = analyzer.calculateEntropy(randomBytes, randomBytes.length);
        assertTrue("Random bytes should have high entropy (> 7.5)", randomEntropy > 7.5);
        
        System.out.println("✅ Byte array entropy calculation correct");
    }

    /**
     * TEST 10: Performance test - large file analysis
     */
    @Test(timeout = 5000) // Should complete within 5 seconds
    public void testLargeFilePerformance() throws Exception {
        // Create 15MB file (triggers multi-region sampling, not full analysis)
        File testFile = tempFolder.newFile("large.dat");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            Random random = new Random(33333);
            byte[] chunk = new byte[1024 * 1024]; // 1MB chunks
            for (int i = 0; i < 15; i++) {
                random.nextBytes(chunk);
                fos.write(chunk);
            }
        }

        long startTime = System.currentTimeMillis();
        double entropy = analyzer.calculateEntropy(testFile);
        long duration = System.currentTimeMillis() - startTime;
        
        assertTrue("Large file should have high entropy", entropy > 7.5);
        assertTrue("Analysis should complete quickly (< 2s)", duration < 2000);
        System.out.println("✅ Large file analysis completed in " + duration + "ms");
    }
}
