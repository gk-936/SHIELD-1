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
 * Unit tests for {@link EntropyAnalyzer} — in addition to the existing
 * EntropyAnalyzerTest, this suite focuses on the extension allowlist
 * (naturally-high-entropy file bypass prevention) and the magic-byte
 * document structure check (M-01 fix).
 */
public class EntropyAnalyzerAllowlistTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private EntropyAnalyzer analyzer;

    @Before
    public void setUp() {
        analyzer = new EntropyAnalyzer();
    }

    // =========================================================================
    // 1. Extension allowlist — isNaturallyHighEntropy()
    // =========================================================================

    // --- Image formats ---
    @Test public void allowlist_jpg_isSkipped()  { assertAllowlisted(".jpg"); }
    @Test public void allowlist_jpeg_isSkipped() { assertAllowlisted(".jpeg"); }
    @Test public void allowlist_png_isSkipped()  { assertAllowlisted(".png"); }
    @Test public void allowlist_webp_isSkipped() { assertAllowlisted(".webp"); }
    @Test public void allowlist_heic_isSkipped() { assertAllowlisted(".heic"); }
    @Test public void allowlist_gif_isSkipped()  { assertAllowlisted(".gif"); }

    // --- Video formats ---
    @Test public void allowlist_mp4_isSkipped()  { assertAllowlisted(".mp4"); }
    @Test public void allowlist_mkv_isSkipped()  { assertAllowlisted(".mkv"); }
    @Test public void allowlist_mov_isSkipped()  { assertAllowlisted(".mov"); }
    @Test public void allowlist_avi_isSkipped()  { assertAllowlisted(".avi"); }

    // --- Audio formats ---
    @Test public void allowlist_mp3_isSkipped()  { assertAllowlisted(".mp3"); }
    @Test public void allowlist_aac_isSkipped()  { assertAllowlisted(".aac"); }
    @Test public void allowlist_ogg_isSkipped()  { assertAllowlisted(".ogg"); }
    @Test public void allowlist_opus_isSkipped() { assertAllowlisted(".opus"); }
    @Test public void allowlist_flac_isSkipped() { assertAllowlisted(".flac"); }

    // --- Archives ---
    @Test public void allowlist_zip_isSkipped()  { assertAllowlisted(".zip"); }
    @Test public void allowlist_rar_isSkipped()  { assertAllowlisted(".rar"); }
    @Test public void allowlist_7z_isSkipped()   { assertAllowlisted(".7z"); }
    @Test public void allowlist_gz_isSkipped()   { assertAllowlisted(".gz"); }

    // --- Android packages ---
    @Test public void allowlist_apk_isSkipped()  { assertAllowlisted(".apk"); }
    @Test public void allowlist_aar_isSkipped()  { assertAllowlisted(".aar"); }

    // --- Encrypted credentials ---
    @Test public void allowlist_p12_isSkipped()  { assertAllowlisted(".p12"); }
    @Test public void allowlist_pfx_isSkipped()  { assertAllowlisted(".pfx"); }

    private void assertAllowlisted(String ext) {
        File f = new File(tempFolder.getRoot(), "sample" + ext);
        assertTrue("Extension " + ext + " should be on the naturally-high-entropy allowlist",
                analyzer.isNaturallyHighEntropy(f));
    }

    // =========================================================================
    // 2. Non-allowlisted extensions — must NOT be skipped
    // =========================================================================

    @Test public void notAllowlisted_txt()  { assertNotAllowlisted(".txt"); }
    @Test public void notAllowlisted_doc()  { assertNotAllowlisted(".doc"); }
    @Test public void notAllowlisted_csv()  { assertNotAllowlisted(".csv"); }
    @Test public void notAllowlisted_xml()  { assertNotAllowlisted(".xml"); }
    @Test public void notAllowlisted_json() { assertNotAllowlisted(".json"); }
    @Test public void notAllowlisted_db()   { assertNotAllowlisted(".db"); }
    @Test public void notAllowlisted_dat()  { assertNotAllowlisted(".dat"); }
    @Test public void notAllowlisted_bin()  { assertNotAllowlisted(".bin"); }

    private void assertNotAllowlisted(String ext) {
        File f = new File(tempFolder.getRoot(), "sample" + ext);
        assertFalse("Extension " + ext + " should NOT be on the allowlist",
                analyzer.isNaturallyHighEntropy(f));
    }

    // =========================================================================
    // 3. Case-insensitivity of extension matching
    // =========================================================================

    @Test
    public void allowlist_extensionCheck_isCaseInsensitive() {
        // Ransomware could rename files with uppercase extensions to bypass detection
        for (String name : new String[]{"photo.JPG", "video.MP4", "archive.ZIP", "app.APK"}) {
            File f = new File(tempFolder.getRoot(), name);
            assertTrue("Uppercase extension in '" + name + "' should still be allowlisted",
                    analyzer.isNaturallyHighEntropy(f));
        }
    }

    @Test
    public void allowlist_mixedCase_stillMatches() {
        File f = new File(tempFolder.getRoot(), "Photo.Jpeg");
        assertTrue(".Jpeg (mixed case) should be allowlisted", analyzer.isNaturallyHighEntropy(f));
    }

    // =========================================================================
    // 4. Document files NOT on allowlist (M-01 fix)
    //    .docx / .xlsx / .pptx / .pdf — removed from allowlist, checked by magic bytes
    // =========================================================================

    @Test
    public void docx_withValidZipMagic_isAllowlisted() throws Exception {
        // PK\x03\x04 = valid DOCX/ZIP header → skip entropy (not encrypted)
        File f = tempFolder.newFile("report.docx");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{0x50, 0x4B, 0x03, 0x04}); // PK magic
            for (int i = 0; i < 1000; i++) out.write("content".getBytes());
        }
        assertTrue("Valid .docx (PK magic) should be treated as allowlisted",
                analyzer.isNaturallyHighEntropy(f));
    }

    @Test
    public void xlsx_withValidZipMagic_isAllowlisted() throws Exception {
        File f = tempFolder.newFile("spreadsheet.xlsx");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{0x50, 0x4B, 0x03, 0x04}); // PK magic
            for (int i = 0; i < 1000; i++) out.write("data".getBytes());
        }
        assertTrue("Valid .xlsx (PK magic) should be treated as allowlisted",
                analyzer.isNaturallyHighEntropy(f));
    }

    @Test
    public void pdf_withValidMagic_isAllowlisted() throws Exception {
        // %PDF = 0x25 0x50 0x44 0x46
        File f = tempFolder.newFile("document.pdf");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF magic
            for (int i = 0; i < 1000; i++) out.write("pdf content".getBytes());
        }
        assertTrue("Valid .pdf (%PDF magic) should be treated as allowlisted",
                analyzer.isNaturallyHighEntropy(f));
    }

    @Test
    public void docx_withDestroyedStructure_isNotAllowlisted() throws Exception {
        // Ransomware encrypted the .docx — magic bytes are gone → score entropy normally
        File f = tempFolder.newFile("encrypted_report.docx");
        try (FileOutputStream out = new FileOutputStream(f)) {
            // Write random bytes — no PK magic
            Random rng = new Random(42L);
            byte[] random = new byte[10 * 1024];
            rng.nextBytes(random);
            out.write(random);
        }
        assertFalse("Encrypted .docx (no PK magic) should NOT be allowlisted — score it",
                analyzer.isNaturallyHighEntropy(f));
    }

    @Test
    public void pdf_withDestroyedStructure_isNotAllowlisted() throws Exception {
        File f = tempFolder.newFile("encrypted.pdf");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Random rng = new Random(99L);
            byte[] random = new byte[10 * 1024];
            rng.nextBytes(random);
            out.write(random);
        }
        assertFalse("Encrypted .pdf (no %PDF magic) should NOT be allowlisted — score it",
                analyzer.isNaturallyHighEntropy(f));
    }

    // =========================================================================
    // 5. calculateEntropy() skips allowlisted files (returns 0.0)
    //    — this is what prevents false positives in UnifiedDetectionEngine
    // =========================================================================

    @Test
    public void calculateEntropy_allowlistedFile_returnsZero() throws Exception {
        // A .jpg filled with random bytes would normally score ~8.0,
        // but should return 0.0 because it is on the allowlist
        File f = tempFolder.newFile("photo.jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Random rng = new Random(1L);
            byte[] random = new byte[50 * 1024];
            rng.nextBytes(random);
            out.write(random);
        }

        double entropy = analyzer.calculateEntropy(f);
        assertEquals(
                "calculateEntropy() must return 0.0 for allowlisted .jpg (false-positive prevention)",
                0.0, entropy, 0.0001);
    }

    @Test
    public void calculateEntropy_allowlistedMp4_returnsZero() throws Exception {
        File f = tempFolder.newFile("video.mp4");
        try (FileOutputStream out = new FileOutputStream(f)) {
            Random rng = new Random(2L);
            byte[] random = new byte[50 * 1024];
            rng.nextBytes(random);
            out.write(random);
        }
        assertEquals("calculateEntropy() must return 0.0 for allowlisted .mp4",
                0.0, analyzer.calculateEntropy(f), 0.0001);
    }

    @Test
    public void calculateEntropy_normalTextFile_returnsMeaningfulValue() throws Exception {
        File f = tempFolder.newFile("notes.txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            String text = "Ordinary text file. Not ransomware. ";
            byte[] bytes = text.getBytes();
            for (int i = 0; i < 500; i++) out.write(bytes);
        }
        double entropy = analyzer.calculateEntropy(f);
        assertTrue(".txt file should produce a real entropy value > 0", entropy > 0.0);
        assertTrue(".txt file entropy should be < 7.0 (plain text)", entropy < 7.0);
    }

    // =========================================================================
    // 6. Edge cases — no extension, hidden files, empty name
    // =========================================================================

    @Test
    public void noExtension_isNotAllowlisted() {
        File f = new File(tempFolder.getRoot(), "RANSOM_NOTE");
        assertFalse("File without extension should not be allowlisted",
                analyzer.isNaturallyHighEntropy(f));
    }

    @Test
    public void hiddenFile_withAllowlistedExtension_isStillAllowlisted() {
        // e.g. .thumbnail.jpg — hidden file but still a jpeg
        File f = new File(tempFolder.getRoot(), ".thumbnail.jpg");
        assertTrue("Hidden file with .jpg extension should be allowlisted",
                analyzer.isNaturallyHighEntropy(f));
    }

    @Test
    public void doubleExtension_innerExtensionMatters() {
        // backup.docx.enc — outer extension is .enc (not on list) → should score normally
        File f = new File(tempFolder.getRoot(), "backup.docx.enc");
        // The analyzer extracts the LAST dot, so ext = ".enc" — not allowlisted
        assertFalse("backup.docx.enc should not be allowlisted (last ext is .enc)",
                analyzer.isNaturallyHighEntropy(f));
    }
}
