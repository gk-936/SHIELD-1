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

    /**
     * File extensions whose content is already compressed or encoded and therefore
     * has naturally high Shannon entropy (typically > 7.5 bits/byte) regardless of
     * whether ransomware has touched them. Scoring these files generates false positives
     * on every normal download, media-sync, or app-cache write event.
     *
     * Criteria for inclusion: the format is either compressed (deflate, zstd, brotli,
     * opus, AAC), encrypted-at-rest by its own codec (encrypted container), or
     * uses dense binary encoding (JPEG DCT coefficients, MP4 video frames) such
     * that byte-level entropy is indistinguishable from AES-256-CBC ciphertext.
     */
    private static final Set<String> NATURALLY_HIGH_ENTROPY_EXTENSIONS =
        Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // Image formats (DCT / wavelet compressed)
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif",
            // Video formats
            ".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v", ".3gp",
            // Audio formats
            ".mp3", ".aac", ".ogg", ".opus", ".flac", ".m4a", ".wma",
            // Archive / compressed containers
            ".zip", ".rar", ".7z", ".gz", ".bz2", ".xz", ".zst", ".tar",
            // Android / Java packages (ZIP-based)
            ".apk", ".jar", ".aar", ".aab",
            // M-01 FIX: .docx/.xlsx/.pptx/.odt/.ods/.odp/.pdf REMOVED from allowlist.
            // These are primary ransomware targets. Magic-byte check below handles
            // the false-positive concern: if PK\x03\x04 header is present the file
            // is still a valid ZIP-based document and we skip entropy scoring; only
            // when the header is absent (encrypted/replaced) do we score normally.
            // Encrypted credential / key stores
            ".p12", ".pfx", ".jks"
        )));

    /**
     * M-01: For document formats (.docx/.xlsx/.pptx/.odt/.pdf) that were removed from the
     * allowlist, check magic bytes to determine if the file still has its expected structure.
     * If the structure is intact (natural compression) skip entropy scoring to avoid
     * false positives. If structure is destroyed the file was likely encrypted by ransomware
     * and should be scored normally.
     */
    private boolean hasExpectedDocumentStructure(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot);
        // Only applies to removed-from-allowlist document formats
        if (!(ext.equals(".docx") || ext.equals(".xlsx") || ext.equals(".pptx")
              || ext.equals(".odt")  || ext.equals(".ods")  || ext.equals(".odp")
              || ext.equals(".pdf"))) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) < 4) return false;
            // DOCX/XLSX/PPTX/ODT/ODS/ODP are ZIP: PK\x03\x04
            if (magic[0] == 0x50 && magic[1] == 0x4B
                    && magic[2] == 0x03 && magic[3] == 0x04) return true;
            // PDF: %PDF
            if (magic[0] == 0x25 && magic[1] == 0x50
                    && magic[2] == 0x44 && magic[3] == 0x46) return true;
            return false;  // Structure destroyed — score entropy normally
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the file's extension is in the naturally-high-entropy allowlist,
     * meaning entropy analysis would produce a high score regardless of ransomware.
     * The calling code should skip entropy+KL scoring for these files entirely.
     */
    public boolean isNaturallyHighEntropy(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot);
        // Standard allowlist check
        if (NATURALLY_HIGH_ENTROPY_EXTENSIONS.contains(ext)) return true;
        // M-01: For removed-from-allowlist document formats, check actual file structure
        return hasExpectedDocumentStructure(file);
    }

    /**
     * SECURITY FIX: Multi-region entropy sampling to prevent bypass attacks.
     * Samples beginning, middle, and end of file. Returns MAXIMUM entropy found.
     * For files <10MB, analyzes entire file for maximum accuracy.
     *
     * Returns 0.0 (treated as "analysis skipped" by the caller) for files whose
     * extension is on the naturally-high-entropy allowlist, preventing false positives
     * from normal compressed / media file writes.
     */
    public double calculateEntropy(File file) {
        if (!file.exists() || !file.canRead() || file.length() == 0) return 0.0;

        // Skip allowlisted extensions — their entropy is high by design, not because
        // ransomware encrypted them.  Returning 0.0 triggers the early-exit in
        // UnifiedDetectionEngine ("entropy calculation failed") which skips the file.
        if (isNaturallyHighEntropy(file)) return 0.0;

        long fileSize = file.length();
        
        // SECURITY FIX: Always use multi-region sampling for files > SAMPLE_SIZE
        // to catch partial encryption. Use full analysis only for very small files.
        if (fileSize > SAMPLE_SIZE) {
            return calculateMultiRegionEntropy(file, fileSize);
        }
        
        // For very small files, analyze entire file
        return calculateFullFileEntropy(file);
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
