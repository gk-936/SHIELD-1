package com.dearmoon.shield.analysis

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ─────────────────────────────────────────────────────────────────────────────
// Unit tests for the new Incident feature analysis layer.
// Runs on the JVM via Robolectric so Android APIs (Color.parseColor) work
// without a device or emulator.
// ─────────────────────────────────────────────────────────────────────────────
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncidentFeatureTest {

    // ─── Shared test fixture ──────────────────────────────────────────────────

    private lateinit var baseProfile: RansomwareDnaProfile

    @Before
    fun setUp() {
        baseProfile = makeProfile(
            compositeScore     = 85,
            entropyScore       = 35,
            filesAtRisk        = 20,
            filesRestored      = 18,
            filesEncrypted     = 19,
            c2Detected         = true,
            c2Blocked          = 3,
            torDetected        = false,
            honeyfileTriggered = true,
            suspectPkg         = "com.evil.ransomware",
            suspectApp         = "EvilApp"
        )
    }

    // =========================================================================
    // AttackFamily enum
    // =========================================================================

    @Test
    fun attackFamily_allValuesExist() {
        val families = AttackFamily.values()
        assertEquals(5, families.size)
        val names = families.map { it.name }.toSet()
        assertTrue(names.contains("CRYPTO_RANSOMWARE"))
        assertTrue(names.contains("LOCKER_RANSOMWARE"))
        assertTrue(names.contains("HYBRID"))
        assertTrue(names.contains("RECONNAISSANCE"))
        assertTrue(names.contains("UNKNOWN"))
    }

    @Test
    fun attackFamily_displayNames_areNonEmpty() {
        AttackFamily.values().forEach { family ->
            assertTrue(
                "displayName must not be blank for $family",
                family.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun attackFamily_certInCategory_containsRansomwareOrMaliciousCode() {
        AttackFamily.values().forEach { family ->
            assertTrue(
                "certInCategory for $family should be CERT-In aligned",
                family.certInCategory.contains("Malicious Code")
            )
        }
    }

    @Test
    fun attackFamily_severityColors_areNonZero() {
        // Color.parseColor returns an ARGB int. 0 would mean transparent black,
        // which none of our defined colours should produce.
        AttackFamily.values().forEach { family ->
            assertNotEquals(
                "severityColor must not be 0 for $family",
                0,
                family.severityColor
            )
        }
    }

    // =========================================================================
    // RansomwareDnaProfile — getConfidenceLevel()
    // =========================================================================

    @Test
    fun getConfidenceLevel_returnsHigh_whenScoreAtLeast70() {
        assertEquals("HIGH",   makeProfile(compositeScore = 70).getConfidenceLevel())
        assertEquals("HIGH",   makeProfile(compositeScore = 90).getConfidenceLevel())
        assertEquals("HIGH",   makeProfile(compositeScore = 130).getConfidenceLevel())
    }

    @Test
    fun getConfidenceLevel_returnsMedium_whenScoreBetween40And69() {
        assertEquals("MEDIUM", makeProfile(compositeScore = 40).getConfidenceLevel())
        assertEquals("MEDIUM", makeProfile(compositeScore = 55).getConfidenceLevel())
        assertEquals("MEDIUM", makeProfile(compositeScore = 69).getConfidenceLevel())
    }

    @Test
    fun getConfidenceLevel_returnsLow_whenScoreBelow40() {
        assertEquals("LOW",    makeProfile(compositeScore = 0).getConfidenceLevel())
        assertEquals("LOW",    makeProfile(compositeScore = 20).getConfidenceLevel())
        assertEquals("LOW",    makeProfile(compositeScore = 39).getConfidenceLevel())
    }

    // =========================================================================
    // RansomwareDnaProfile — getRiskSeverityLabel()
    // =========================================================================

    @Test
    fun getRiskSeverityLabel_critical_whenScoreAtLeast90() {
        assertEquals("CRITICAL", makeProfile(compositeScore = 90).getRiskSeverityLabel())
        assertEquals("CRITICAL", makeProfile(compositeScore = 110).getRiskSeverityLabel())
        assertEquals("CRITICAL", makeProfile(compositeScore = 130).getRiskSeverityLabel())
    }

    @Test
    fun getRiskSeverityLabel_high_whenScoreBetween70And89() {
        assertEquals("HIGH",     makeProfile(compositeScore = 70).getRiskSeverityLabel())
        assertEquals("HIGH",     makeProfile(compositeScore = 80).getRiskSeverityLabel())
        assertEquals("HIGH",     makeProfile(compositeScore = 89).getRiskSeverityLabel())
    }

    @Test
    fun getRiskSeverityLabel_medium_whenScoreBetween40And69() {
        assertEquals("MEDIUM",   makeProfile(compositeScore = 40).getRiskSeverityLabel())
        assertEquals("MEDIUM",   makeProfile(compositeScore = 55).getRiskSeverityLabel())
        assertEquals("MEDIUM",   makeProfile(compositeScore = 69).getRiskSeverityLabel())
    }

    @Test
    fun getRiskSeverityLabel_low_whenScoreBelow40() {
        assertEquals("LOW",      makeProfile(compositeScore = 0).getRiskSeverityLabel())
        assertEquals("LOW",      makeProfile(compositeScore = 39).getRiskSeverityLabel())
    }

    // =========================================================================
    // RansomwareDnaProfile — dataLossLabel inside toCertInText()
    // =========================================================================

    @Test
    fun toCertInText_dataLoss_isNone_whenNoLoss() {
        val p = makeProfile(filesEncrypted = 5, filesRestored = 5, dataLoss = false)
        assertTrue(p.toCertInText().contains("Data Loss         : NONE"))
    }

    @Test
    fun toCertInText_dataLoss_isNone_whenRestoredEqualsEncrypted() {
        val p = makeProfile(filesEncrypted = 10, filesRestored = 10, dataLoss = true)
        assertTrue(p.toCertInText().contains("Data Loss         : NONE"))
    }

    @Test
    fun toCertInText_dataLoss_isPartial_whenSomeRestored() {
        val p = makeProfile(filesEncrypted = 10, filesRestored = 5, dataLoss = true)
        assertTrue(p.toCertInText().contains("Data Loss         : PARTIAL"))
    }

    @Test
    fun toCertInText_dataLoss_isSignificant_whenNoneRestored() {
        val p = makeProfile(filesEncrypted = 10, filesRestored = 0, dataLoss = true)
        assertTrue(p.toCertInText().contains("Data Loss         : SIGNIFICANT"))
    }

    // =========================================================================
    // RansomwareDnaProfile — ransom formatting inside toCertInText()
    // =========================================================================

    @Test
    fun toCertInText_ransomFormat_usesLakhSuffix_whenAbove100000() {
        // 150 000 → "Rs.1.5L"
        val p = makeProfile(ransomRupees = 150_000L)
        val text = p.toCertInText()
        assertTrue("Expected Rs.*L suffix for 150000", text.contains("Rs.1.5L"))
    }

    @Test
    fun toCertInText_ransomFormat_usesPlainRupees_whenBelow100000() {
        val p = makeProfile(ransomRupees = 75_000L)
        val text = p.toCertInText()
        assertTrue("Expected plain Rs.75000", text.contains("Rs.75000"))
    }

    @Test
    fun toCertInText_ransomFormat_exactBoundary_100000() {
        val p = makeProfile(ransomRupees = 100_000L)
        val text = p.toCertInText()
        // 100_000 / 100_000 = 1, (100_000 % 100_000) / 10_000 = 0 → "Rs.1.0L"
        assertTrue("Expected Rs.1.0L at boundary", text.contains("Rs.1.0L"))
    }

    // =========================================================================
    // RansomwareDnaProfile — toCertInText() structural checks
    // =========================================================================

    @Test
    fun toCertInText_alwaysContainsCertInBlock() {
        val text = baseProfile.toCertInText()
        assertTrue(text.contains("CERT-In Reportable  : YES"))
        assertTrue(text.contains("Reporting Deadline  : Within 6 hours"))
    }

    @Test
    fun toCertInText_containsProfileId() {
        assertTrue(baseProfile.toCertInText().contains(baseProfile.profileId))
    }

    @Test
    fun toCertInText_containsAttackFamilyDisplayName() {
        assertTrue(baseProfile.toCertInText().contains(baseProfile.attackFamily.displayName))
    }

    @Test
    fun toCertInText_containsNetworkSection() {
        val text = baseProfile.toCertInText()
        assertTrue(text.contains("NETWORK ACTIVITY"))
        assertTrue(text.contains("C2 Attempt        : YES"))
        assertTrue(text.contains("Connections Blocked: 3"))
        assertTrue(text.contains("Tor Network       : NOT DETECTED"))
    }

    // =========================================================================
    // RansomwareDnaProfile — toShareableText()
    // =========================================================================

    @Test
    fun toShareableText_hasFiveLines() {
        // toShareableText uses appendLine for 4 lines and append for the 5th
        val lines = baseProfile.toShareableText().trimEnd().lines()
        assertEquals(5, lines.size)
    }

    @Test
    fun toShareableText_firstLineIsShieldHeader() {
        assertTrue(baseProfile.toShareableText().startsWith("🛡️ SHIELD Incident Summary"))
    }

    @Test
    fun toShareableText_containsScoreAndSeverity() {
        val text = baseProfile.toShareableText()
        assertTrue(text.contains("${baseProfile.compositeScore}/130"))
        assertTrue(text.contains(baseProfile.getRiskSeverityLabel()))
    }

    @Test
    fun toShareableText_containsSuspectInfo() {
        val text = baseProfile.toShareableText()
        assertTrue(text.contains("EvilApp"))
    }

    @Test
    fun toShareableText_unknownSuspect_whenBothNull() {
        val p = makeProfile(suspectPkg = null, suspectApp = null)
        assertTrue(p.toShareableText().contains("UNKNOWN"))
    }

    // =========================================================================
    // TimelineEventType enum
    // =========================================================================

    @Test
    fun timelineEventType_has9Values() {
        assertEquals(9, TimelineEventType.values().size)
    }

    @Test
    fun timelineEventType_allExpectedValuesPresent() {
        val names = TimelineEventType.values().map { it.name }.toSet()
        listOf(
            "FIRST_SIGNAL", "FILE_MODIFIED", "HONEYFILE_HIT", "HIGH_RISK_ALERT",
            "NETWORK_BLOCKED", "VPN_ACTIVATED", "PROCESS_KILLED",
            "RESTORE_STARTED", "RESTORE_COMPLETE"
        ).forEach { expected ->
            assertTrue("Missing TimelineEventType.$expected", names.contains(expected))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Builder helper — construct minimal RansomwareDnaProfile with overrides
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeProfile(
        compositeScore     : Int     = 80,
        entropyScore       : Int     = 30,
        filesAtRisk        : Int     = 10,
        filesRestored      : Int     = 8,
        filesEncrypted     : Int     = 8,
        c2Detected         : Boolean = false,
        c2Blocked          : Int     = 0,
        torDetected        : Boolean = false,
        honeyfileTriggered : Boolean = false,
        dataLoss           : Boolean = false,
        ransomRupees       : Long    = 150_000L,
        suspectPkg         : String? = "com.test.app",
        suspectApp         : String? = "TestApp"
    ) = RansomwareDnaProfile(
        profileId                   = "test-profile-id-001",
        generatedAt                 = System.currentTimeMillis(),
        shieldVersion               = "SHIELD-1 MVP",
        attackWindowStart           = System.currentTimeMillis() - 60_000L,
        attackWindowEnd             = System.currentTimeMillis(),
        attackFamily                = AttackFamily.CRYPTO_RANSOMWARE,
        compositeScore              = compositeScore,
        entropyScore                = entropyScore,
        kldScore                    = 20,
        sprtAcceptedH1              = true,
        primaryDetector             = "EntropyAnalyzer",
        confidenceLevel             = "HIGH",
        encryptionSpeedFilesPerMin  = 5.0f,
        attackDurationSeconds       = 60L,
        detectionTimeSeconds        = 30L,
        targetedExtensions          = listOf(".pdf", ".docx"),
        targetPriority              = "Documents > Downloads",
        totalFilesAtRisk            = filesAtRisk,
        honeyfileTriggered          = honeyfileTriggered,
        honeyfileTriggerDelaySeconds = if (honeyfileTriggered) 12L else -1L,
        c2AttemptDetected           = c2Detected,
        c2BlockedCount              = c2Blocked,
        torAttemptDetected          = torDetected,
        portsTargeted               = listOf(443, 8443),
        filesEncryptedEstimate      = filesEncrypted,
        filesRestoredCount          = filesRestored,
        dataLossOccurred            = dataLoss,
        estimatedRansomRupees       = ransomRupees,
        suspectPackage              = suspectPkg,
        suspectAppName              = suspectApp,
        timelineEvents              = emptyList()
    )
}
