package com.dearmoon.shield.detection;

import java.io.File;
import java.io.FileInputStream;

public class EntropyAnalyzer {
    private static final int SAMPLE_SIZE = 8192; // 8KB sample per region
    private static final long FULL_ANALYSIS_THRESHOLD = 10 * 1024 * 1024; // 10MB

    /**
     * SECURITY FIX: Multi-region entropy sampling to prevent bypass attacks.
     * Samples beginning, middle, and end of file. Returns MAXIMUM entropy found.
     * For files <10MB, analyzes entire file for maximum accuracy.
     */
    public double calculateEntropy(File file) {
        if (!file.exists() || !file.canRead() || file.length() == 0) return 0.0;

        long fileSize = file.length();
        
        // For small files (<10MB), analyze entire file
        if (fileSize <= FULL_ANALYSIS_THRESHOLD) {
            return calculateFullFileEntropy(file);
        }
        
        // For large files, sample 3 regions: beginning, middle, end
        return calculateMultiRegionEntropy(file, fileSize);
    }

    /**
     * Analyzes entire file for small files (<10MB)
     */
    private double calculateFullFileEntropy(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) return 0.0;
            return calculateEntropy(buffer, bytesRead);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Samples 3 regions of large files: beginning, middle, end.
     * Returns MAXIMUM entropy to catch encryption in any region.
     */
    private double calculateMultiRegionEntropy(File file, long fileSize) {
        double maxEntropy = 0.0;
        
        try (FileInputStream fis = new FileInputStream(file)) {
            // Region 1: Beginning (0 to 8KB)
            byte[] buffer = new byte[SAMPLE_SIZE];
            int bytesRead = fis.read(buffer);
            if (bytesRead > 0) {
                maxEntropy = Math.max(maxEntropy, calculateEntropy(buffer, bytesRead));
            }
            
            // Region 2: Middle (skip to middle - 4KB)
            long middleOffset = (fileSize / 2) - (SAMPLE_SIZE / 2);
            if (middleOffset > SAMPLE_SIZE && fis.skip(middleOffset - SAMPLE_SIZE) > 0) {
                bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    maxEntropy = Math.max(maxEntropy, calculateEntropy(buffer, bytesRead));
                }
            }
        } catch (Exception e) {
            // Fall through to end region
        }
        
        // Region 3: End (last 8KB) - use separate stream to avoid seek issues
        try (FileInputStream fis = new FileInputStream(file)) {
            long endOffset = Math.max(0, fileSize - SAMPLE_SIZE);
            if (endOffset > SAMPLE_SIZE && fis.skip(endOffset) > 0) {
                byte[] buffer = new byte[SAMPLE_SIZE];
                int bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    maxEntropy = Math.max(maxEntropy, calculateEntropy(buffer, bytesRead));
                }
            }
        } catch (Exception e) {
            // Return best entropy found so far
        }
        
        return maxEntropy;
    }

    public double calculateEntropy(byte[] data, int length) {
        if (length == 0) return 0.0;

        // Count byte frequencies
        int[] freq = new int[256];
        for (int i = 0; i < length; i++) {
            freq[data[i] & 0xFF]++;
        }

        // Calculate Shannon entropy: H = -Σ p(x) log₂ p(x)
        double entropy = 0.0;
        for (int count : freq) {
            if (count > 0) {
                double p = (double) count / length;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }

        return entropy;
    }

    public boolean isHighEntropy(double entropy) {
        // Encrypted/compressed data typically has entropy > 7.5
        // Plain text typically has entropy < 5.0
        return entropy > 7.5;
    }
}
