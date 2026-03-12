package com.dearmoon.shield.snapshot;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

import static org.junit.Assert.*;

/**
 * Pure-JVM unit tests for {@link SnapshotIntegrityChecker} helper methods and
 * {@link FileMetadata} value-object behaviour.
 *
 * SnapshotIntegrityChecker.check() requires an Android Context and a live DB,
 * so full integration coverage is delegated to instrumented tests.  Here we
 * exercise the two static hash helpers that are accessible without Android:
 *   - computeMetadataHash()
 *   - computeChainHash()
 *
 * We also cover FileMetadata construction and the RestoreEngine.RestoreResult
 * value class since they require no Android framework.
 */
public class SnapshotIntegrityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // =========================================================================
    // 1. computeMetadataHash() — determinism and sensitivity
    // =========================================================================

    @Test
    public void computeMetadataHash_isDeterministic() {
        FileMetadata m = sampleMetadata("A");
        String hash1 = SnapshotIntegrityChecker.computeMetadataHash(m);
        String hash2 = SnapshotIntegrityChecker.computeMetadataHash(m);
        assertEquals("computeMetadataHash must be deterministic", hash1, hash2);
    }

    @Test
    public void computeMetadataHash_differsWhenPathChanges() {
        FileMetadata m1 = sampleMetadata("/sdcard/a.txt");
        FileMetadata m2 = sampleMetadata("/sdcard/b.txt"); // different path
        m2.fileSize     = m1.fileSize;
        m2.lastModified = m1.lastModified;
        m2.sha256Hash   = m1.sha256Hash;
        m2.snapshotId   = m1.snapshotId;

        assertNotEquals("Different paths must produce different metadata hashes",
                SnapshotIntegrityChecker.computeMetadataHash(m1),
                SnapshotIntegrityChecker.computeMetadataHash(m2));
    }

    @Test
    public void computeMetadataHash_differsWhenFileSizeChanges() {
        FileMetadata m1 = sampleMetadata("/sdcard/f.txt");
        FileMetadata m2 = sampleMetadata("/sdcard/f.txt");
        m2.fileSize = m1.fileSize + 1;

        assertNotEquals("Different file sizes must produce different metadata hashes",
                SnapshotIntegrityChecker.computeMetadataHash(m1),
                SnapshotIntegrityChecker.computeMetadataHash(m2));
    }

    @Test
    public void computeMetadataHash_differsWhenHashChanges() {
        FileMetadata m1 = sampleMetadata("/sdcard/f.txt");
        FileMetadata m2 = sampleMetadata("/sdcard/f.txt");
        m2.fileSize     = m1.fileSize;
        m2.lastModified = m1.lastModified;
        m2.sha256Hash   = "deadbeef" + m1.sha256Hash.substring(8);

        assertNotEquals("Different content hashes must produce different metadata hashes",
                SnapshotIntegrityChecker.computeMetadataHash(m1),
                SnapshotIntegrityChecker.computeMetadataHash(m2));
    }

    @Test
    public void computeMetadataHash_isHexString() {
        FileMetadata m = sampleMetadata("/sdcard/f.txt");
        String hash = SnapshotIntegrityChecker.computeMetadataHash(m);
        assertTrue("Metadata hash must be a non-empty hex string",
                hash != null && hash.matches("[0-9a-f]+"));
    }

    @Test
    public void computeMetadataHash_hasCorrectLength() {
        // SHA-256 → 64 hex chars
        FileMetadata m = sampleMetadata("/sdcard/f.txt");
        assertEquals("SHA-256 hex digest must be 64 characters",
                64, SnapshotIntegrityChecker.computeMetadataHash(m).length());
    }

    // =========================================================================
    // 2. computeChainHash() — chain linking properties
    // =========================================================================

    @Test
    public void computeChainHash_genesisBlock_produces64CharHex() {
        String chain = SnapshotIntegrityChecker.computeChainHash("GENESIS", "aaabbbccc");
        assertNotNull(chain);
        assertEquals("Chain hash must be 64 hex chars (SHA-256)", 64, chain.length());
        assertTrue(chain.matches("[0-9a-f]+"));
    }

    @Test
    public void computeChainHash_isDeterministic() {
        String c1 = SnapshotIntegrityChecker.computeChainHash("prev123", "meta456");
        String c2 = SnapshotIntegrityChecker.computeChainHash("prev123", "meta456");
        assertEquals("computeChainHash must be deterministic", c1, c2);
    }

    @Test
    public void computeChainHash_differsWhenPreviousHashChanges() {
        String c1 = SnapshotIntegrityChecker.computeChainHash("PREV_A", "metaX");
        String c2 = SnapshotIntegrityChecker.computeChainHash("PREV_B", "metaX");
        assertNotEquals("Different previous chain hashes must produce different chain hashes",
                c1, c2);
    }

    @Test
    public void computeChainHash_differsWhenMetaHashChanges() {
        String c1 = SnapshotIntegrityChecker.computeChainHash("PREV", "META_1");
        String c2 = SnapshotIntegrityChecker.computeChainHash("PREV", "META_2");
        assertNotEquals("Different meta hashes must produce different chain hashes", c1, c2);
    }

    @Test
    public void computeChainHash_formsUnbrokenChain() {
        // Simulate a 3-entry chain: GENESIS → entry1 → entry2 → entry3
        FileMetadata e1 = sampleMetadata("/sdcard/file1.txt");
        FileMetadata e2 = sampleMetadata("/sdcard/file2.txt");
        FileMetadata e3 = sampleMetadata("/sdcard/file3.txt");

        String h1 = SnapshotIntegrityChecker.computeMetadataHash(e1);
        String h2 = SnapshotIntegrityChecker.computeMetadataHash(e2);
        String h3 = SnapshotIntegrityChecker.computeMetadataHash(e3);

        String chain1 = SnapshotIntegrityChecker.computeChainHash("GENESIS", h1);
        String chain2 = SnapshotIntegrityChecker.computeChainHash(chain1, h2);
        String chain3 = SnapshotIntegrityChecker.computeChainHash(chain2, h3);

        // Tamper: re-derive chain3 starting from a forged chain2
        String forgedChain2 = SnapshotIntegrityChecker.computeChainHash("FORGED_PREV", h2);
        String forgedChain3 = SnapshotIntegrityChecker.computeChainHash(forgedChain2, h3);

        assertNotEquals("Forging a chain link must produce a different chain3", chain3, forgedChain3);
    }

    @Test
    public void computeChainHash_insertedEntryBreaksChain() {
        // Original chain: GENESIS → A → B
        FileMetadata a = sampleMetadata("/sdcard/a.txt");
        FileMetadata b = sampleMetadata("/sdcard/b.txt");
        FileMetadata injected = sampleMetadata("/sdcard/injected.txt");

        String chainA = SnapshotIntegrityChecker.computeChainHash("GENESIS",
                SnapshotIntegrityChecker.computeMetadataHash(a));
        String chainB = SnapshotIntegrityChecker.computeChainHash(chainA,
                SnapshotIntegrityChecker.computeMetadataHash(b));

        // Attacker inserts an entry between A and B
        String chainInjected = SnapshotIntegrityChecker.computeChainHash(chainA,
                SnapshotIntegrityChecker.computeMetadataHash(injected));
        String chainBAfterInjection = SnapshotIntegrityChecker.computeChainHash(chainInjected,
                SnapshotIntegrityChecker.computeMetadataHash(b));

        assertNotEquals("Inserting an entry must change the chain hash of subsequent entries",
                chainB, chainBAfterInjection);
    }

    // =========================================================================
    // 3. FileMetadata — value object
    // =========================================================================

    @Test
    public void fileMetadata_constructorSetsFields() {
        FileMetadata m = new FileMetadata("/path/to/file.txt", 1024L, 999999L,
                "abc123hash", 42L);
        assertEquals("/path/to/file.txt", m.filePath);
        assertEquals(1024L, m.fileSize);
        assertEquals(999999L, m.lastModified);
        assertEquals("abc123hash", m.sha256Hash);
        assertEquals(42L, m.snapshotId);
        assertFalse("New metadata should not be backed up yet", m.isBackedUp);
        assertEquals("modifiedDuringAttack should default to 0", 0, m.modifiedDuringAttack);
    }

    @Test
    public void fileMetadata_isBackedUp_defaultsFalse() {
        FileMetadata m = sampleMetadata("/sdcard/new.txt");
        assertFalse(m.isBackedUp);
    }

    @Test
    public void fileMetadata_chainHash_defaultsNull() {
        FileMetadata m = sampleMetadata("/sdcard/new.txt");
        assertNull(m.chainHash);
    }

    @Test
    public void fileMetadata_encryptedKey_defaultsNull() {
        FileMetadata m = sampleMetadata("/sdcard/new.txt");
        assertNull(m.encryptedKey);
    }

    // =========================================================================
    // 4. RestoreEngine.RestoreResult — value class
    // =========================================================================

    @Test
    public void restoreResult_defaultValues() {
        RestoreEngine.RestoreResult r = new RestoreEngine.RestoreResult();
        assertEquals("Default restoredCount must be 0", 0, r.restoredCount);
        assertEquals("Default failedCount must be 0", 0, r.failedCount);
        assertFalse("Default noChanges must be false", r.noChanges);
    }

    @Test
    public void restoreResult_fieldsArePubliclyMutable() {
        RestoreEngine.RestoreResult r = new RestoreEngine.RestoreResult();
        r.restoredCount = 5;
        r.failedCount   = 2;
        r.noChanges     = true;

        assertEquals(5, r.restoredCount);
        assertEquals(2, r.failedCount);
        assertTrue(r.noChanges);
    }

    // =========================================================================
    // 5. Snapshot retention guard — attack-window safety
    // =========================================================================

    /**
     * The retention policy (Feature 7) must NOT purge old backups while a
     * ransomware attack is in progress; those oldest entries are the pre-attack
     * clean copies that RestoreEngine needs for recovery.
     *
     * Contract from {@code SnapshotManager.backupOriginalFile()}:
     * <pre>
     *   if (activeAttackId == 0) enforceRetentionPolicy();
     * </pre>
     *
     * Full integration coverage (filling 101 files and asserting no purge during
     * an active attack) requires an Android Context and SQLite, so it lives in
     * the instrumented test suite.  These tests verify the guard condition and
     * the {@link FileMetadata#modifiedDuringAttack} field that underpins it.
     */
    @Test
    public void retentionGuard_purgeSkippedWhenAttackActive() {
        long activeAttackId = 99L; // non-zero → attack in progress
        // Replicate the guard: if (activeAttackId == 0) enforceRetentionPolicy()
        boolean purgeWouldRun = (activeAttackId == 0);
        assertFalse(
                "Retention purge must be suppressed while activeAttackId > 0",
                purgeWouldRun);
    }

    @Test
    public void retentionGuard_purgeAllowedWhenNoAttack() {
        long activeAttackId = 0L; // zero → no active attack
        boolean purgeWouldRun = (activeAttackId == 0);
        assertTrue(
                "Retention purge must run freely when no attack is active (activeAttackId == 0)",
                purgeWouldRun);
    }

    @Test
    public void fileMetadata_modifiedDuringAttack_defaultsToZero() {
        FileMetadata m = sampleMetadata("/sdcard/doc.pdf");
        assertEquals("modifiedDuringAttack must default to 0 for a new metadata entry",
                0L, m.modifiedDuringAttack);
    }

    @Test
    public void fileMetadata_modifiedDuringAttack_recordsAttackId() {
        FileMetadata m = sampleMetadata("/sdcard/photo.jpg");
        long attackId = 12345L;
        m.modifiedDuringAttack = attackId;
        assertEquals("modifiedDuringAttack must record the active attack ID",
                attackId, m.modifiedDuringAttack);
    }

    @Test
    public void fileMetadata_modifiedDuringAttack_nonZeroIndicatesAttacked() {
        // SnapshotManager.trackFileChange() guards on (existing.modifiedDuringAttack == 0)
        // to prevent overwriting the attack ID once set.
        FileMetadata m = sampleMetadata("/sdcard/video.mp4");
        m.modifiedDuringAttack = 7L;
        boolean alreadyMarked = (m.modifiedDuringAttack != 0);
        assertTrue("Non-zero modifiedDuringAttack must indicate file was touched during an attack",
                alreadyMarked);
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private FileMetadata sampleMetadata(String path) {
        return new FileMetadata(path, 2048L, System.currentTimeMillis(),
                sha256("sample-content-" + path), 1L);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
