package com.dearmoon.shield.detection;

import java.io.File;
import java.io.FileInputStream;

public class EntropyAnalyzer {
    private static final int SAMPLE_SIZE = 8192; // 8KB sample

    public double calculateEntropy(File file) {
        if (!file.exists() || !file.canRead() || file.length() == 0) return 0.0;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[SAMPLE_SIZE];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) return 0.0;

            return calculateEntropy(buffer, bytesRead);
        } catch (Exception e) {
            return 0.0;
        }
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
