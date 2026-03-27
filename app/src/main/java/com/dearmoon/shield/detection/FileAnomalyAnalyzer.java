package com.dearmoon.shield.detection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Specialized analyzer for file-level anomalies (Entropy and KL Divergence).
 */
public class FileAnomalyAnalyzer {
    private static final String TAG = "FileAnomalyAnalyzer";
    private final EntropyAnalyzer entropyAnalyzer = new EntropyAnalyzer();
    private final KLDivergenceCalculator klCalculator = new KLDivergenceCalculator();

    public static class AnomalyResult {
        public final double entropy;
        public final double klDivergence;

        public AnomalyResult(double entropy, double klDivergence) {
            this.entropy = entropy;
            this.klDivergence = klDivergence;
        }
    }

    /**
     * Analyzes a file for structural anomalies.
     * Returns 0.0 for both values if the file is too small or inaccessible.
     */
    public AnomalyResult analyze(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || file.length() < 100) {
            return new AnomalyResult(0.0, 0.0);
        }

        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead = fis.read(buffer);
            if (bytesRead > 0) {
                byte[] data = new byte[bytesRead];
                System.arraycopy(buffer, 0, data, 0, bytesRead);
                double entropy = entropyAnalyzer.calculateEntropy(data, data.length);
                double klDiv = klCalculator.calculateDivergence(data, data.length);
                return new AnomalyResult(entropy, klDiv);
            }
        } catch (IOException ignored) {}

        return new AnomalyResult(0.0, 0.0);
    }
}
