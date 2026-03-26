package com.dearmoon.shield.detection;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class EntropyAnalyzer {
    private static final int SAMPLE_SIZE = 8192; // 8KB sample per region
    private static final long FULL_ANALYSIS_THRESHOLD = 10 * 1024 * 1024; // 10MB

    // Compressed file extensions
    // High natural entropy
    private static final Set<String> NATURALLY_HIGH_ENTROPY_EXTENSIONS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // Image formats
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif",
            // Video formats
            ".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v", ".3gp",
            // Audio formats
            ".mp3", ".aac", ".ogg", ".opus", ".flac", ".m4a", ".wma",
            // Archive formats
            ".zip", ".rar", ".7z", ".gz", ".bz2", ".xz", ".zst", ".tar",
            // Android packages
            ".apk", ".jar", ".aar", ".aab",
            // Document structure check
            // Credential stores
            ".p12", ".pfx", ".jks"
        )));

    // Check document structure
    private boolean hasExpectedDocumentStructure(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot);
        // Specific document formats
        if (!(ext.equals(".docx") || ext.equals(".xlsx") || ext.equals(".pptx")
              || ext.equals(".odt")  || ext.equals(".ods")  || ext.equals(".odp")
              || ext.equals(".pdf"))) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) < 4) return false;
            // ZIP magic bytes
            if (magic[0] == 0x50 && magic[1] == 0x4B
                    && magic[2] == 0x03 && magic[3] == 0x04) return true;
            // PDF magic bytes
            if (magic[0] == 0x25 && magic[1] == 0x50
                    && magic[2] == 0x44 && magic[3] == 0x46) return true;
            return false;  // Structure destroyed
        } catch (Exception e) {
            return false;
        }
    }

    // Natural entropy check
    public boolean isNaturallyHighEntropy(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot);
        // Allowlist check
        if (NATURALLY_HIGH_ENTROPY_EXTENSIONS.contains(ext)) return true;
        // Check file structure
        return hasExpectedDocumentStructure(file);
    }

    // Multi-region entropy sampling
    // Sample file regions
    // Accurate full analysis
    public double calculateEntropy(File file) {
        if (!file.exists() || !file.canRead() || file.length() == 0) return 0.0;

        // Skip allowlisted extensions
        if (isNaturallyHighEntropy(file)) return 0.0;

        long fileSize = file.length();
        
        // Multi-region sampling
        if (fileSize > SAMPLE_SIZE) {
            return calculateMultiRegionEntropy(file, fileSize);
        }
        
        // Full file analysis
        return calculateFullFileEntropy(file);
    }

    // Full file analysis
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

    // Multi-region sampling
    private double calculateMultiRegionEntropy(File file, long fileSize) {
        double maxEntropy = 0.0;
        
        try (FileInputStream fis = new FileInputStream(file)) {
            // Beginning region
            byte[] buffer = new byte[SAMPLE_SIZE];
            int bytesRead = fis.read(buffer);
            if (bytesRead > 0) {
                maxEntropy = Math.max(maxEntropy, calculateEntropy(buffer, bytesRead));
            }
            
            // Middle region
            long middleOffset = (fileSize / 2) - (SAMPLE_SIZE / 2);
            if (middleOffset > SAMPLE_SIZE && fis.skip(middleOffset - SAMPLE_SIZE) > 0) {
                bytesRead = fis.read(buffer);
                if (bytesRead > 0) {
                    maxEntropy = Math.max(maxEntropy, calculateEntropy(buffer, bytesRead));
                }
            }
        } catch (Exception e) {
            // Fallback to end
        }
        
        // End region
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
            // Best entropy result
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

        // Calculate Shannon entropy
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
        // Entropy threshold
        return entropy > 7.5;
    }
}
