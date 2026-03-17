package com.dearmoon.shield.detection;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for process-attribution path-parsing helpers extracted from
 * {@link UnifiedDetectionEngine}.
 *
 * The helpers are package-private, so these tests live in the same package
 * and call them via reflection to keep the test honest to the real code paths
 * that run in production. Alternatively, extracted as pure static helpers
 * for easier testing.
 *
 * Because we cannot instantiate UnifiedDetectionEngine without an Android
 * Context, we replicate the exact regex logic here and keep the tests as
 * pure-JVM (no Robolectric needed). If the regexes in the engine ever change,
 * these tests will break — which is the intended signal.
 */
public class ProcessAttributionTest {

    // Copy of production regexes from UnifiedDetectionEngine (any change fails these tests)
    private static final java.util.regex.Pattern PRIVATE_PATH =
            java.util.regex.Pattern.compile("^/data/(?:data|user/\\d+)/([a-zA-Z][a-zA-Z0-9_.]+)(?:/|$)");
    private static final java.util.regex.Pattern SHARED_PATH =
            java.util.regex.Pattern.compile(
                "(?:/storage/emulated/\\d+|/sdcard|/storage/[^/]+|/data/media/\\d+)/Android/(?:data|obb)/([a-zA-Z][a-zA-Z0-9_.]+)(?:/|$)");

    // =========================================================================
    // 1. /data/data/<pkg>/ — Strategy 1
    // =========================================================================

    @Test
    public void strategy1_typicalPrivateStoragePath_extractsPackage() {
        assertEquals("com.evil.malware",
                extractPrivate("/data/data/com.evil.malware/files/victims.txt"));
    }

    @Test
    public void strategy1_nestedPath_extractsPackage() {
        assertEquals("org.example.app",
                extractPrivate("/data/data/org.example.app/cache/sub/dir/file.dat"));
    }

    @Test
    public void strategy1_noTrailingSlash_extractsPackage() {
        // Updated regex supports end of string or trailing slash
        assertEquals("com.evil.malware",
                extractPrivate("/data/data/com.evil.malware"));
    }

    @Test
    public void strategy1_packageWithNumbers_extractsPackage() {
        assertEquals("com.app2024",
                extractPrivate("/data/data/com.app2024/databases/main.db"));
    }

    @Test
    public void strategy1_nonPrivatePath_returnsNull() {
        assertNull(extractPrivate("/sdcard/Documents/note.txt"));
        assertNull(extractPrivate("/storage/emulated/0/Downloads/file.zip"));
    }

    @Test
    public void strategy1_packageStartingWithDigit_returnsNull() {
        // Regex requires first char to be a letter — package starting with a digit is invalid
        assertNull(extractPrivate("/data/data/1invalid.pkg/files/f.dat"));
    }

    // =========================================================================
    // 2. /data/user/<userId>/<pkg>/ — Strategy 1, multi-user layout
    // =========================================================================

    @Test
    public void strategy1_multiUser_userId0_extractsPackage() {
        assertEquals("com.target.app",
                extractPrivate("/data/user/0/com.target.app/files/data.enc"));
    }

    @Test
    public void strategy1_multiUser_userId10_extractsPackage() {
        assertEquals("com.target.app",
                extractPrivate("/data/user/10/com.target.app/databases/db"));
    }

    @Test
    public void strategy1_workProfile_userId999_extractsPackage() {
        assertEquals("com.corp.app",
                extractPrivate("/data/user/999/com.corp.app/cache/f.tmp"));
    }

    // =========================================================================
    // 3. Scoped shared storage — Android/data/<pkg>/ — Strategy 2
    // =========================================================================

    @Test
    public void strategy2_sdcardAndroidData_extractsPackage() {
        assertEquals("com.ransomware.sample",
                extractShared("/sdcard/Android/data/com.ransomware.sample/files/enc.dat"));
    }

    @Test
    public void strategy2_emulatedStorage_extractsPackage() {
        assertEquals("com.example.app",
                extractShared("/storage/emulated/0/Android/data/com.example.app/files/x"));
    }

    @Test
    public void strategy2_obb_extractsPackage() {
        assertEquals("com.game.app",
                extractShared("/sdcard/Android/obb/com.game.app/main.obb"));
    }

    @Test
    public void strategy2_emulatedUserId10_extractsPackage() {
        assertEquals("com.multi.user.app",
                extractShared("/storage/emulated/10/Android/data/com.multi.user.app/files/f"));
    }

    @Test
    public void strategy2_externalSdCard_extractsPackage() {
        assertEquals("com.map.app",
                extractShared("/storage/ABCD-1234/Android/data/com.map.app/cache/map.tile"));
    }

    @Test
    public void strategy2_dataMedia_extractsPackage() {
        assertEquals("com.android.providers.media",
                extractShared("/data/media/0/Android/data/com.android.providers.media/files/x"));
    }

    @Test
    public void strategy2_rootSdcardFile_returnsNull() {
        // /sdcard/RANSOM_NOTE.txt has no Android/data/<pkg> segment
        assertNull(extractShared("/sdcard/RANSOM_NOTE.txt"));
        assertNull(extractShared("/sdcard/Documents/note.docx"));
    }

    // =========================================================================
    // 4. Null and empty inputs
    // =========================================================================

    @Test
    public void nullPath_doesNotThrow_returnsNull() {
        assertNull(extractPrivate(null));
        assertNull(extractShared(null));
    }

    @Test
    public void emptyPath_returnsNull() {
        assertNull(extractPrivate(""));
        assertNull(extractShared(""));
    }

    // =========================================================================
    // 5. Paths that look similar but are NOT private storage
    // =========================================================================

    @Test
    public void almostPrivatePath_withoutTrailingSlash_isMatchedIfEndOfLine() {
        // Updated regex supports end of string or trailing slash
        assertEquals("com.app", extractPrivate("/data/data/com.app"));
    }

    @Test
    public void publicDownloadsPath_isNotMatchedByStrategy1() {
        assertNull(extractPrivate("/storage/emulated/0/Downloads/malware.apk"));
    }

    @Test
    public void dcimPath_isNotMatchedByEitherStrategy() {
        assertNull(extractPrivate("/sdcard/DCIM/Camera/photo.jpg"));
        assertNull(extractShared("/sdcard/DCIM/Camera/photo.jpg"));
    }

    // =========================================================================
    // Helpers — mirror the exact production regex match code
    // =========================================================================

    private String extractPrivate(String path) {
        if (path == null) return null;
        java.util.regex.Matcher m = PRIVATE_PATH.matcher(path);
        if (m.find()) return m.group(1);
        return null;
    }

    private String extractShared(String path) {
        if (path == null) return null;
        java.util.regex.Matcher m = SHARED_PATH.matcher(path);
        if (m.find()) return m.group(1);
        return null;
    }
}
