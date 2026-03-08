package com.dearmoon.shield.analysis

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Timeline event type — each value maps to a specific UI colour in both the
// in-app RecyclerView and the PDF visual timeline.
// ─────────────────────────────────────────────────────────────────────────────
enum class TimelineEventType {
    FIRST_SIGNAL,     // amber  — first anomaly that opened the attack window
    FILE_MODIFIED,    // orange — file write/modify event during the attack
    HONEYFILE_HIT,    // red    — honeyfile trap was accessed by ransomware
    HIGH_RISK_ALERT,  // red    — composite score crossed the ≥70 threshold
    NETWORK_BLOCKED,  // cyan   — outbound C2 or Tor connection was blocked
    VPN_ACTIVATED,    // cyan   — NetworkGuardService activated VPN interception
    PROCESS_KILLED,   // cyan   — malicious process termination by SHIELD
    RESTORE_STARTED,  // green  — RestoreEngine began selective file recovery
    RESTORE_COMPLETE  // green  — all recoverable files have been restored
}

// ─────────────────────────────────────────────────────────────────────────────
// RansomwareDnaProfile — immutable snapshot of one complete ransomware incident.
// Built by RansomwareDnaProfiler and consumed by IncidentActivity, the two
// report fragments, and ShieldPdfReportGenerator.
// ─────────────────────────────────────────────────────────────────────────────
data class RansomwareDnaProfile(

    // ── Identity ──────────────────────────────────────────────────────────────
    val profileId: String,          // UUID uniquely identifying this report
    val generatedAt: Long,          // epoch ms when buildProfile() completed
    val shieldVersion: String,      // "SHIELD-1 MVP"
    val attackWindowStart: Long,    // epoch ms — first anomalous event
    val attackWindowEnd: Long,      // epoch ms — last event / detection time

    // ── Classification ────────────────────────────────────────────────────────
    val attackFamily: AttackFamily,
    val compositeScore: Int,        // 0..130 (entropy 40 + KLD 30 + SPRT 30 + correlation 30)
    val entropyScore: Int,          // 0..40 from EntropyAnalyzer
    val kldScore: Int,              // 0..30 from KLDivergenceCalculator
    val sprtAcceptedH1: Boolean,    // true when SPRTDetector chose ACCEPT_H1
    val primaryDetector: String,    // highest-scoring subsystem name
    val confidenceLevel: String,    // "HIGH" / "MEDIUM" / "LOW"

    // computed field
    val normalizedScore: Int = ((compositeScore / 130f) * 100).roundToInt().coerceIn(0, 100),

    // ── Speed metrics ─────────────────────────────────────────────────────────
    val encryptionSpeedFilesPerMin: Float,  // files modified per minute in window
    val attackDurationSeconds: Long,        // attackWindowEnd - attackWindowStart / 1000
    val detectionTimeSeconds: Long,         // seconds from first signal → ≥70 score

    // ── Target ────────────────────────────────────────────────────────────────
    val targetedExtensions: List<String>,   // top 5 extensions attacked (e.g. ".pdf")
    val targetPriority: String,             // "Documents > Images > Downloads"
    val totalFilesAtRisk: Int,

    // ── Honeyfile ─────────────────────────────────────────────────────────────
    val honeyfileTriggered: Boolean,
    val honeyfileTriggerDelaySeconds: Long, // -1 when honeyfile was not triggered

    // ── Network ───────────────────────────────────────────────────────────────
    val c2AttemptDetected: Boolean,
    val c2BlockedCount: Int,
    val torAttemptDetected: Boolean,
    val portsTargeted: List<Int>,

    // ── Damage ────────────────────────────────────────────────────────────────
    val filesEncryptedEstimate: Int,
    val filesRestoredCount: Int,
    val dataLossOccurred: Boolean,
    val estimatedRansomRupees: Long,    // totalFilesAtRisk * 15_000

    // ── Attribution ───────────────────────────────────────────────────────────
    val suspectPackage: String?,    // null when attribution failed
    val suspectAppName: String?,

    // ── Timeline (Tab 1 data source) ─────────────────────────────────────────
    val timelineEvents: List<TimelineEvent>

) {

    // ─────────────────────────────────────────────────────────────────────────
    // Nested: one row in the attack timeline
    // ─────────────────────────────────────────────────────────────────────────
    data class TimelineEvent(
        val timestamp: Long,
        val eventType: TimelineEventType,
        val description: String,
        val sourceTable: String     // DB table this row was read from
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** "HIGH" when normalizedScore >= 55, "MEDIUM" 30..54, else "LOW". */
    @JvmName("computeConfidenceLevel")
    fun getConfidenceLevel(): String = when (normalizedScore) {
        in 0..29  -> "LOW"
        in 30..54 -> "MEDIUM"  
        in 55..74 -> "HIGH"
        in 75..100 -> "CRITICAL"
        else -> "LOW"
    }

    /** CERT-In-aligned severity label. */
    fun getRiskSeverityLabel(): String = getConfidenceLevel()

    // ─────────────────────────────────────────────────────────────────────────
    // Short shareable summary (5 lines)
    // ─────────────────────────────────────────────────────────────────────────
    fun toShareableText(): String = buildString {
        appendLine("🛡️ SHIELD Incident Summary")
        appendLine("Family  : ${attackFamily.displayName}  |  Score: $normalizedScore/100  |  ${getRiskSeverityLabel()}")
        appendLine("Files   : ${totalFilesAtRisk} at risk, ${filesRestoredCount} restored")
        appendLine("Network : C2 ${if (c2AttemptDetected) "BLOCKED ($c2BlockedCount)" else "None"}  |  Tor: ${if (torAttemptDetected) "DETECTED" else "None"}")
        append("Suspect : ${suspectAppName ?: suspectPackage ?: "UNKNOWN"}  |  Profile: $profileId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full CERT-In plain-text report
    // ─────────────────────────────────────────────────────────────────────────
    fun toCertInText(): String {
        val istFmt = SimpleDateFormat("dd MMM yyyy HH:mm:ss 'IST'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        val timeFmt = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

        val dataLossLabel = when {
            !dataLossOccurred                           -> "NONE"
            filesRestoredCount >= filesEncryptedEstimate -> "NONE"
            filesRestoredCount > 0                      -> "PARTIAL"
            else                                        -> "SIGNIFICANT"
        }

        val formattedRansom = buildString {
            val r = estimatedRansomRupees
            if (r >= 100_000L) append("Rs.${r / 100_000}.${(r % 100_000) / 10_000}L")
            else append("Rs.$r")
        }

        return buildString {
            appendLine("═══════════════════════════════════════════════════")
            appendLine("SHIELD THREAT INTELLIGENCE REPORT")
            appendLine("Generated: ${istFmt.format(Date(generatedAt))}")
            appendLine("Profile ID: $profileId")
            appendLine("═══════════════════════════════════════════════════")
            appendLine("INCIDENT CLASSIFICATION")
            appendLine("  Attack Family     : ${attackFamily.displayName}")
            appendLine("  CERT-In Category  : ${attackFamily.certInCategory}")
            appendLine("  Primary Detector  : $primaryDetector")
            appendLine("  Threat Score      : $normalizedScore / 100")
            appendLine("  Confidence        : $confidenceLevel")
            appendLine("  Severity          : ${getRiskSeverityLabel()}")
            appendLine()
            appendLine("ATTACK TIMELINE")
            appendLine("  First Signal      : ${timeFmt.format(Date(attackWindowStart))}")
            appendLine("  Detection Time    : $detectionTimeSeconds seconds")
            appendLine("  Attack Duration   : $attackDurationSeconds seconds")
            appendLine("  Encryption Speed  : $encryptionSpeedFilesPerMin files/minute")
            appendLine()
            appendLine("TARGET PROFILE")
            appendLine("  Files at Risk     : $totalFilesAtRisk")
            appendLine("  Target Priority   : $targetPriority")
            appendLine("  File Types        : ${targetedExtensions.joinToString(", ")}")
            appendLine()
            appendLine("NETWORK ACTIVITY")
            appendLine("  C2 Attempt        : ${if (c2AttemptDetected) "YES" else "NO"}")
            appendLine("  Connections Blocked: $c2BlockedCount")
            appendLine("  Tor Network       : ${if (torAttemptDetected) "DETECTED" else "NOT DETECTED"}")
            appendLine("  Ports Targeted    : ${portsTargeted.joinToString(", ").ifEmpty { "NONE" }}")
            appendLine()
            appendLine("HONEYFILE INTELLIGENCE")
            appendLine("  Trap Triggered    : ${if (honeyfileTriggered) "YES" else "NO"}")
            appendLine("  Trigger Delay     : $honeyfileTriggerDelaySeconds seconds")
            appendLine()
            appendLine("DAMAGE ASSESSMENT")
            appendLine("  Files at Risk     : $totalFilesAtRisk")
            appendLine("  Files Restored    : $filesRestoredCount")
            appendLine("  Data Loss         : $dataLossLabel")
            appendLine("  Est. Ransom Demand: $formattedRansom")
            appendLine()
            appendLine("ATTRIBUTION")
            appendLine("  Suspect Package   : ${suspectPackage ?: "UNKNOWN"}")
            appendLine("  Suspect App       : ${suspectAppName ?: "UNKNOWN"}")
            appendLine()
            appendLine("CERT-In Reportable  : YES")
            appendLine("Reporting Deadline  : Within 6 hours of incident detection")
            append("═══════════════════════════════════════════════════")
        }
    }
}
