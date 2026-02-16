package com.dearmoon.shield.detection;

import java.io.File;
import java.io.FileInputStream;

public class KLDivergenceCalculator {
    private static final int SAMPLE_SIZE = 8192;
    private static final double UNIFORM_PROB = 1.0 / 256.0;

    public double calculateDivergence(File file) {
        if (!file.exists() || !file.canRead()) return 0.0;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[SAMPLE_SIZE];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) return 0.0;

            return calculateDivergence(buffer, bytesRead);
        } catch (Exception e) {
            return 0.0;
        }
    }

    public double calculateDivergence(byte[] data, int length) {
        if (length == 0) return 0.0;

        // Count byte frequencies
        int[] freq = new int[256];
        for (int i = 0; i < length; i++) {
            freq[data[i] & 0xFF]++;
        }

        // Calculate KL-divergence: D_KL(P || U) = Σ P(x) log₂(P(x) / U(x))
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
        // Low divergence (< 0.1) indicates uniform distribution (encrypted)
        // High divergence indicates structured data (compressed/plain)
        return divergence < 0.1;
    }
}
