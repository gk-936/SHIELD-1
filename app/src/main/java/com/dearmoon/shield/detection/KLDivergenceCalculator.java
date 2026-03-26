package com.dearmoon.shield.detection;

import java.io.File;
import java.io.FileInputStream;

public class KLDivergenceCalculator {
    private static final int SAMPLE_SIZE = 8192;
    private static final double UNIFORM_PROB = 1.0 / 256.0;

    // Multi-region KL-divergence sampling
    public double calculateDivergence(File file) {
        if (!file.exists() || !file.canRead() || file.length() == 0) return 1.0;

        long fileSize = file.length();

        // Full file analysis
        if (fileSize <= SAMPLE_SIZE) {
            return calculateFullFileDivergence(file);
        }

        return calculateMultiRegionDivergence(file, fileSize);
    }

    private double calculateFullFileDivergence(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) return 1.0;
            return calculateDivergence(buffer, bytesRead);
        } catch (Exception e) {
            return 1.0;
        }
    }

    private double calculateMultiRegionDivergence(File file, long fileSize) {
        double minDivergence = 10.0; // Large initial value

        try (FileInputStream fis = new FileInputStream(file)) {
            // Beginning region
            byte[] buffer = new byte[SAMPLE_SIZE];
            int bytesRead = fis.read(buffer);
            if (bytesRead > 0) {
                minDivergence = Math.min(minDivergence, calculateDivergence(buffer, bytesRead));
            }

            // Middle region
            long middleOffset = (fileSize / 2) - (SAMPLE_SIZE / 2);
            if (middleOffset > SAMPLE_SIZE && fis.skip(middleOffset - SAMPLE_SIZE) > 0) {
                bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    minDivergence = Math.min(minDivergence, calculateDivergence(buffer, bytesRead));
                }
            }
        } catch (Exception e) { }

        try (FileInputStream fis = new FileInputStream(file)) {
            // End region
            long endOffset = Math.max(0, fileSize - SAMPLE_SIZE);
            if (endOffset > SAMPLE_SIZE && fis.skip(endOffset) > 0) {
                byte[] buffer = new byte[SAMPLE_SIZE];
                int bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    minDivergence = Math.min(minDivergence, calculateDivergence(buffer, bytesRead));
                }
            }
        } catch (Exception e) { }

        return minDivergence;
    }

    public double calculateDivergence(byte[] data, int length) {
        if (length == 0) return 0.0;

        // Count byte frequencies
        int[] freq = new int[256];
        for (int i = 0; i < length; i++) {
            freq[data[i] & 0xFF]++;
        }

        // Calculate KL-divergence
        double divergence = 0.0;
        for (int count : freq) {
            if (count > 0) {
                double p = (double) count / length;
                divergence += p * (Math.log(p / UNIFORM_PROB) / Math.log(2));
            }
        }

        return divergence;
    }

    public boolean isUniformDistribution(double divergence) {
        // KL divergence threshold
        return divergence < 0.1;
    }
}
