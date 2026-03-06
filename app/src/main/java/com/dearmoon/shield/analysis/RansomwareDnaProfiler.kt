package com.dearmoon.shield.analysis

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.dearmoon.shield.data.EventDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

/**
 * Builds a [RansomwareDnaProfile] by querying the SHIELD event database.
 *
 * Design decisions:
 *  - Singleton via companion object — matches EventDatabase pattern.
 *  - All SQLite access is performed on Dispatchers.IO, never on Main.
 *  - Uses rawQuery only — no Room annotations, matching project constraints.
 *  - On any query failure the method returns a safe UNKNOWN profile rather
 *    than crashing, so the UI always has something to render.
 */
object RansomwareDnaProfiler {

    private const val TAG = "SHIELD_DNA"
    private const val SHIELD_VERSION = "SHIELD-1 MVP"
    private const val MAX_TIMELINE_EVENTS = 50
    private const val RANSOM_RUPEES_PER_FILE = 15_000L   // Indian SME average ransom per file

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a full [RansomwareDnaProfile] for the supplied attack window.
     * Called from IncidentActivity after all scoring parameters are known.
     *
     * All database I/O is executed on [Dispatchers.IO]; call this from a
     * coroutine scope (e.g. lifecycleScope.launch(Dispatchers.IO)).
     */
    suspend fun buildProfile(
        context: Context,
        attackWindowStart: Long,
        attackWindowEnd: Long,
        compositeScore: Int,
        entropyScore: Int,
        kldScore: Int,
        sprtAcceptedH1: Boolean,
        restoredFileCount: Int
    ): RansomwareDnaProfile = withContext(Dispatchers.IO) {

        Log.d(TAG, "buildProfile() start — window [$attackWindowStart, $attackWindowEnd] score=$compositeScore")

        val db = EventDatabase.getInstance(context)

        return@withContext try {
            // ── 1. Count locker events in window (needed for classification) ──
            val lockerEventCount = queryCount(db,
                "SELECT COUNT(*) FROM locker_shield_events WHERE timestamp BETWEEN ? AND ?",
                attackWindowStart, attackWindowEnd)

            // ── 2. Count file modification events in window ───────────────────
            val fileModCount = queryCount(db,
                "SELECT COUNT(*) FROM file_system_events WHERE timestamp BETWEEN ? AND ? AND (operation='WRITE' OR operation='MODIFY')",
                attackWindowStart, attackWindowEnd)

            // ── 3. Derive file modification rate (files per second) ────────────
            val windowSeconds = ((attackWindowEnd - attackWindowStart) / 1000L).coerceAtLeast(1L)
            val fileModRate = fileModCount.toFloat() / windowSeconds.toFloat()   // files/sec

            // ── 4. CLASSIFICATION LOGIC ───────────────────────────────────────
            //   Rule ordering matters — most specific conditions first.
            //   Hybrid: locker + high file-mod rate means both mechanisms active.
            //   Locker: locker events but low file-mod rate.
            //   Crypto: SPRT confirmed H1 (high file rate) + high entropy.
            //   Recon:  medium composite score — probing behaviour.
            val attackFamily: AttackFamily = when {
                // Hybrid — locker events AND high encryption rate → both attack planes active
                lockerEventCount > 0 && fileModRate > 2.0f -> AttackFamily.HYBRID

                // Locker only — device locked but files not mass-encrypted
                lockerEventCount > 0 && fileModRate <= 2.0f -> AttackFamily.LOCKER_RANSOMWARE

                // Crypto — statistical confirmation from SPRT + significant entropy increase
                sprtAcceptedH1 && entropyScore > 25 -> AttackFamily.CRYPTO_RANSOMWARE

                // Reconnaissance — score in medium band before full payload delivery
                compositeScore in 40..69 -> AttackFamily.RECONNAISSANCE

                // Cannot classify with available evidence
                else -> AttackFamily.UNKNOWN
            }

            // ── 5. Primary detector name ──────────────────────────────────────
            val primaryDetector = when {
                lockerEventCount > 0 -> "LockerShieldService"
                entropyScore >= 30   -> "EntropyAnalyzer"
                kldScore >= 20       -> "KLDivergenceCalculator"
                sprtAcceptedH1       -> "SPRTDetector"
                else                 -> "BehaviorCorrelationEngine"
            }

            // ── 6. Confidence level ───────────────────────────────────────────
            val confidenceLevel = when {
                compositeScore >= 70 -> "HIGH"
                compositeScore >= 40 -> "MEDIUM"
                else                 -> "LOW"
            }

            // ── 7. Targeted extensions (top 5 by frequency) ──────────────────
            val targetedExtensions = queryTopExtensions(db, attackWindowStart, attackWindowEnd)

            // ── 8. Directory priority string ──────────────────────────────────
            val targetPriority = queryDirectoryPriority(db, attackWindowStart, attackWindowEnd)

            // ── 9. Total files at risk = all file modification events ─────────
            val totalFilesAtRisk = fileModCount

            // ── 10. Honeyfile analysis ────────────────────────────────────────
            val honeyfileTriggeredAt = queryFirstHoneyfileTimestamp(db, attackWindowStart, attackWindowEnd)
            val honeyfileTriggered = honeyfileTriggeredAt != null
            val honeyfileTriggerDelaySeconds = if (honeyfileTriggeredAt != null)
                (honeyfileTriggeredAt - attackWindowStart) / 1000L
            else -1L

            // ── 11. Network analysis ──────────────────────────────────────────
            val (c2BlockedCount, torDetected, portsTargeted) =
                queryNetworkData(db, attackWindowStart, attackWindowEnd)
            val c2AttemptDetected = c2BlockedCount > 0

            // ── 12. Damage assessment ─────────────────────────────────────────
            val filesEncryptedEstimate = fileModCount
            val dataLossOccurred = filesEncryptedEstimate > restoredFileCount
            val estimatedRansomRupees = totalFilesAtRisk.toLong() * RANSOM_RUPEES_PER_FILE

            // ── 13. Attribution — use most recent correlation result ──────────
            val (suspectPackage, suspectAppName) =
                queryAttribution(db, attackWindowStart, attackWindowEnd)

            // ── 14. Speed metrics ─────────────────────────────────────────────
            val encryptionSpeedFilesPerMin = fileModRate * 60f
            val attackDurationSeconds = windowSeconds
            // Detection time = time from first event to first score ≥ 70
            val detectionTimeSeconds = queryDetectionLatency(db, attackWindowStart, attackWindowEnd)

            // ── 15. Build timeline ────────────────────────────────────────────
            val timelineEvents = buildTimeline(db, attackWindowStart, attackWindowEnd)

            val profile = RansomwareDnaProfile(
                profileId                  = UUID.randomUUID().toString(),
                generatedAt                = System.currentTimeMillis(),
                shieldVersion              = SHIELD_VERSION,
                attackWindowStart          = attackWindowStart,
                attackWindowEnd            = attackWindowEnd,
                attackFamily               = attackFamily,
                compositeScore             = compositeScore,
                entropyScore               = entropyScore,
                kldScore                   = kldScore,
                sprtAcceptedH1             = sprtAcceptedH1,
                primaryDetector            = primaryDetector,
                confidenceLevel            = confidenceLevel,
                encryptionSpeedFilesPerMin = encryptionSpeedFilesPerMin,
                attackDurationSeconds      = attackDurationSeconds,
                detectionTimeSeconds       = detectionTimeSeconds,
                targetedExtensions         = targetedExtensions,
                targetPriority             = targetPriority,
                totalFilesAtRisk           = totalFilesAtRisk,
                honeyfileTriggered         = honeyfileTriggered,
                honeyfileTriggerDelaySeconds = honeyfileTriggerDelaySeconds,
                c2AttemptDetected          = c2AttemptDetected,
                c2BlockedCount             = c2BlockedCount,
                torAttemptDetected         = torDetected,
                portsTargeted              = portsTargeted,
                filesEncryptedEstimate     = filesEncryptedEstimate,
                filesRestoredCount         = restoredFileCount,
                dataLossOccurred           = dataLossOccurred,
                estimatedRansomRupees      = estimatedRansomRupees,
                suspectPackage             = suspectPackage,
                suspectAppName             = suspectAppName,
                timelineEvents             = timelineEvents
            )

            persistProfile(db, profile)
            Log.d(TAG, "buildProfile() complete — family=${attackFamily.name} score=$compositeScore")
            profile

        } catch (e: Exception) {
            Log.e(TAG, "buildProfile() failed — returning UNKNOWN profile", e)
            buildEmptyProfile(attackWindowStart, attackWindowEnd, compositeScore,
                entropyScore, kldScore, sprtAcceptedH1, restoredFileCount)
        }
    }

    /**
     * Convenience method that locates the latest high-risk detection in the
     * database to derive the attack window automatically.
     * Returns null when no detection record exists.
     */
    suspend fun buildProfileFromLatestAttack(context: Context): RansomwareDnaProfile? =
        withContext(Dispatchers.IO) {
            val db = EventDatabase.getInstance(context)
            try {
                // Find the most recent detection result with a high confidence score
                val cursor = db.readableDatabase.rawQuery(
                    "SELECT timestamp, confidence_score FROM detection_results " +
                    "WHERE confidence_score >= 40 ORDER BY timestamp DESC LIMIT 1",
                    null
                )
                if (!cursor.moveToFirst()) { cursor.close(); return@withContext null }

                val latestTs = cursor.getLong(0)
                val latestScore = cursor.getInt(1)
                cursor.close()

                // Use a 60-second window before the detection timestamp
                val windowStart = latestTs - 60_000L
                val windowEnd = latestTs

                buildProfile(
                    context            = context,
                    attackWindowStart  = windowStart,
                    attackWindowEnd    = windowEnd,
                    compositeScore     = latestScore,
                    entropyScore       = 0,
                    kldScore           = 0,
                    sprtAcceptedH1     = latestScore >= 70,
                    restoredFileCount  = 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "buildProfileFromLatestAttack() failed", e)
                null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Runs a single-value COUNT query. Returns 0 on any failure. */
    private fun queryCount(db: EventDatabase, sql: String, start: Long, end: Long): Int {
        return try {
            val cursor = db.readableDatabase.rawQuery(sql, arrayOf(start.toString(), end.toString()))
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            count
        } catch (e: Exception) {
            Log.e(TAG, "queryCount failed: $sql", e)
            0
        }
    }

    /**
     * Extracts the top-5 file extensions attacked during the window.
     * Extension frequency counts are used to surface the most targeted
     * file types, guiding the victim on what data is at greatest risk.
     */
    private fun queryTopExtensions(db: EventDatabase, start: Long, end: Long): List<String> {
        return try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT file_extension, COUNT(*) AS cnt FROM file_system_events " +
                "WHERE timestamp BETWEEN ? AND ? AND file_extension IS NOT NULL AND file_extension != '' " +
                "GROUP BY file_extension ORDER BY cnt DESC LIMIT 5",
                arrayOf(start.toString(), end.toString())
            )
            val list = mutableListOf<String>()
            while (cursor.moveToNext()) { list.add(cursor.getString(0)) }
            cursor.close()
            list
        } catch (e: Exception) {
            Log.e(TAG, "queryTopExtensions failed", e)
            emptyList()
        }
    }

    /**
     * Maps file paths to high-level directory categories and returns a
     * priority string such as "Documents > Images > Downloads".
     * Ordering reflects the most-attacked directories first, giving the
     * victim a quick summary of data exposure.
     */
    private fun queryDirectoryPriority(db: EventDatabase, start: Long, end: Long): String {
        return try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT file_path FROM file_system_events " +
                "WHERE timestamp BETWEEN ? AND ? AND file_path IS NOT NULL",
                arrayOf(start.toString(), end.toString())
            )

            val counts = mutableMapOf(
                "Documents" to 0, "Downloads" to 0,
                "Images" to 0, "Videos" to 0, "Other" to 0
            )

            while (cursor.moveToNext()) {
                val path = cursor.getString(0) ?: continue
                when {
                    path.contains("/Documents", ignoreCase = true) -> counts["Documents"] = counts["Documents"]!! + 1
                    path.contains("/Downloads", ignoreCase = true) -> counts["Downloads"] = counts["Downloads"]!! + 1
                    path.contains("/Pictures", ignoreCase = true)
                        || path.contains("/DCIM", ignoreCase = true) -> counts["Images"] = counts["Images"]!! + 1
                    path.contains("/Videos", ignoreCase = true)  -> counts["Videos"] = counts["Videos"]!! + 1
                    else                                          -> counts["Other"] = counts["Other"]!! + 1
                }
            }
            cursor.close()

            counts.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .joinToString(" > ") { it.key }
                .ifEmpty { "Unknown" }

        } catch (e: Exception) {
            Log.e(TAG, "queryDirectoryPriority failed", e)
            "Unknown"
        }
    }

    /**
     * Returns the epoch-ms timestamp of the first honeyfile access in the
     * window, or null when none occurred.
     */
    private fun queryFirstHoneyfileTimestamp(db: EventDatabase, start: Long, end: Long): Long? {
        return try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT timestamp FROM honeyfile_events " +
                "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC LIMIT 1",
                arrayOf(start.toString(), end.toString())
            )
            val ts = if (cursor.moveToFirst()) cursor.getLong(0) else null
            cursor.close()
            ts
        } catch (e: Exception) {
            Log.e(TAG, "queryFirstHoneyfileTimestamp failed", e)
            null
        }
    }

    /**
     * Analyses network events in the window.
     * NetworkGuardService stores blocked connections with "BLOCKED" in the
     * event_type column (e.g. "BLOCKED: Suspicious IP", "BLOCKED: Tor exit node").
     * Port numbers are parsed from event_type descriptions so the PDF can list them.
     *
     * Returns Triple(blockedCount, torDetected, portsTargeted).
     */
    private fun queryNetworkData(
        db: EventDatabase, start: Long, end: Long
    ): Triple<Int, Boolean, List<Int>> {
        return try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT event_type, destination_port FROM network_events " +
                "WHERE timestamp BETWEEN ? AND ?",
                arrayOf(start.toString(), end.toString())
            )

            var blockedCount = 0
            var torDetected = false
            val ports = mutableSetOf<Int>()

            while (cursor.moveToNext()) {
                val eventType = cursor.getString(0) ?: ""
                val port = cursor.getInt(1)

                if (eventType.contains("BLOCKED", ignoreCase = true)) {
                    blockedCount++
                    if (port > 0) ports.add(port)
                }
                if (eventType.contains("Tor", ignoreCase = true)) {
                    torDetected = true
                }
            }
            cursor.close()

            Triple(blockedCount, torDetected, ports.toList().sorted())

        } catch (e: Exception) {
            Log.e(TAG, "queryNetworkData failed", e)
            Triple(0, false, emptyList())
        }
    }

    /**
     * Looks up the most probable suspect package from correlation_results
     * during the attack window.
     * Returns Pair(packageName, appLabel) — both nullable.
     */
    private fun queryAttribution(db: EventDatabase, start: Long, end: Long): Pair<String?, String?> {
        return try {
            // Use the record with the highest behavior_score in the window
            val cursor = db.readableDatabase.rawQuery(
                "SELECT package_name FROM correlation_results " +
                "WHERE timestamp BETWEEN ? AND ? AND package_name IS NOT NULL " +
                "ORDER BY behavior_score DESC LIMIT 1",
                arrayOf(start.toString(), end.toString())
            )
            val pkg = if (cursor.moveToFirst()) cursor.getString(0) else null
            cursor.close()

            // Derive a human-readable name — trim to app name portion of package
            val appName = pkg?.substringAfterLast('.')?.replaceFirstChar { it.uppercaseChar() }
            Pair(pkg, appName)

        } catch (e: Exception) {
            Log.e(TAG, "queryAttribution failed", e)
            Pair(null, null)
        }
    }

    /**
     * Computes how long it took SHIELD to elevate to a HIGH_RISK_ALERT after
     * the first anomalous event.  This is the detection latency KPI.
     */
    private fun queryDetectionLatency(db: EventDatabase, start: Long, end: Long): Long {
        return try {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT timestamp FROM detection_results " +
                "WHERE timestamp BETWEEN ? AND ? AND confidence_score >= 70 " +
                "ORDER BY timestamp ASC LIMIT 1",
                arrayOf(start.toString(), end.toString())
            )
            val alertTs = if (cursor.moveToFirst()) cursor.getLong(0) else end
            cursor.close()
            abs((alertTs - start) / 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "queryDetectionLatency failed", e)
            0L
        }
    }

    /**
     * Merges events from all 6 monitored tables into a single chronological
     * timeline limited to MAX_TIMELINE_EVENTS rows.
     *
     * Each event type maps to a [TimelineEventType] that controls the colour
     * used in both the RecyclerView and the PDF visual.
     * The very first event in the merged list is always overridden to
     * FIRST_SIGNAL so the PDF timeline has a clear anchor point.
     */
    private fun buildTimeline(
        db: EventDatabase, start: Long, end: Long
    ): List<RansomwareDnaProfile.TimelineEvent> {

        val events = mutableListOf<RansomwareDnaProfile.TimelineEvent>()

        // file_system_events — WRITE/MODIFY operations
        safeQuery(db,
            "SELECT timestamp, operation, file_path FROM file_system_events " +
            "WHERE timestamp BETWEEN ? AND ? AND (operation='WRITE' OR operation='MODIFY') " +
            "ORDER BY timestamp ASC",
            start, end
        ) { cursor ->
            val ts   = cursor.getLong(0)
            val op   = cursor.getString(1) ?: "WRITE"
            val path = cursor.getString(2) ?: ""
            val name = path.substringAfterLast('/').take(40)
            events.add(RansomwareDnaProfile.TimelineEvent(
                timestamp   = ts,
                eventType   = TimelineEventType.FILE_MODIFIED,
                description = "$op: $name",
                sourceTable = "file_system_events"
            ))
        }

        // honeyfile_events
        safeQuery(db,
            "SELECT timestamp, access_type, file_path FROM honeyfile_events " +
            "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC",
            start, end
        ) { cursor ->
            val ts   = cursor.getLong(0)
            val acc  = cursor.getString(1) ?: "ACCESS"
            val path = cursor.getString(2) ?: ""
            val name = path.substringAfterLast('/').take(40)
            events.add(RansomwareDnaProfile.TimelineEvent(
                timestamp   = ts,
                eventType   = TimelineEventType.HONEYFILE_HIT,
                description = "Honeyfile $acc: $name",
                sourceTable = "honeyfile_events"
            ))
        }

        // network_events — only BLOCKED rows
        safeQuery(db,
            "SELECT timestamp, event_type, destination_ip FROM network_events " +
            "WHERE timestamp BETWEEN ? AND ? AND event_type LIKE '%BLOCKED%' ORDER BY timestamp ASC",
            start, end
        ) { cursor ->
            val ts   = cursor.getLong(0)
            val evt  = cursor.getString(1) ?: "BLOCKED"
            val ip   = cursor.getString(2) ?: ""
            events.add(RansomwareDnaProfile.TimelineEvent(
                timestamp   = ts,
                eventType   = TimelineEventType.NETWORK_BLOCKED,
                description = "$evt $ip".trim().take(50),
                sourceTable = "network_events"
            ))
        }

        // detection_results — high-risk alerts (score ≥ 70)
        safeQuery(db,
            "SELECT timestamp, confidence_score FROM detection_results " +
            "WHERE timestamp BETWEEN ? AND ? AND confidence_score >= 70 ORDER BY timestamp ASC",
            start, end
        ) { cursor ->
            val ts    = cursor.getLong(0)
            val score = cursor.getInt(1)
            events.add(RansomwareDnaProfile.TimelineEvent(
                timestamp   = ts,
                eventType   = TimelineEventType.HIGH_RISK_ALERT,
                description = "HIGH_RISK_ALERT score=$score",
                sourceTable = "detection_results"
            ))
        }

        // locker_shield_events — screen-locking actions (treated as file-mod equivalent)
        safeQuery(db,
            "SELECT timestamp, event_type, threat_type FROM locker_shield_events " +
            "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC",
            start, end
        ) { cursor ->
            val ts     = cursor.getLong(0)
            val evt    = cursor.getString(1) ?: "LOCKER"
            val threat = cursor.getString(2) ?: ""
            events.add(RansomwareDnaProfile.TimelineEvent(
                timestamp   = ts,
                eventType   = TimelineEventType.FILE_MODIFIED,
                description = "Locker: $evt $threat".trim().take(50),
                sourceTable = "locker_shield_events"
            ))
        }

        // correlation_results — high-score correlation events
        safeQuery(db,
            "SELECT timestamp, event_type, behavior_score FROM correlation_results " +
            "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC",
            start, end
        ) { cursor ->
            val ts    = cursor.getLong(0)
            val evt   = cursor.getString(1) ?: "CORRELATION"
            val score = cursor.getInt(2)
            events.add(RansomwareDnaProfile.TimelineEvent(
                timestamp   = ts,
                eventType   = TimelineEventType.FILE_MODIFIED,
                description = "$evt score=$score",
                sourceTable = "correlation_results"
            ))
        }

        // Sort all merged events by timestamp, cap at MAX_TIMELINE_EVENTS
        val sorted = events.sortedBy { it.timestamp }.take(MAX_TIMELINE_EVENTS).toMutableList()

        // First event in the merged timeline becomes the FIRST_SIGNAL anchor
        if (sorted.isNotEmpty()) {
            sorted[0] = sorted[0].copy(eventType = TimelineEventType.FIRST_SIGNAL)
        }

        return sorted
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inserts the finished profile into the dna_profiles table (v5).
     * Uses INSERT so every incident gets its own immutable row.
     */
    private fun persistProfile(db: EventDatabase, profile: RansomwareDnaProfile) {
        try {
            val cv = ContentValues().apply {
                put("profile_id",             profile.profileId)
                put("generated_at",           profile.generatedAt)
                put("attack_window_start",    profile.attackWindowStart)
                put("attack_window_end",      profile.attackWindowEnd)
                put("attack_family",          profile.attackFamily.name)
                put("composite_score",        profile.compositeScore)
                put("files_at_risk",          profile.totalFilesAtRisk)
                put("files_restored",         profile.filesRestoredCount)
                put("c2_detected",            if (profile.c2AttemptDetected) 1 else 0)
                put("honeyfile_triggered",    if (profile.honeyfileTriggered) 1 else 0)
                put("suspect_package",        profile.suspectPackage)
                put("full_report_text",       profile.toCertInText())
            }
            db.writableDatabase.insert("dna_profiles", null, cv)
            Log.d(TAG, "Profile persisted to dna_profiles: ${profile.profileId}")
        } catch (e: Exception) {
            Log.e(TAG, "persistProfile failed: ${profile.profileId}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs a rawQuery over [start, end] window, iterating all rows with [block].
     * Catches and logs any exception rather than propagating, so a single
     * bad table never aborts the entire timeline build.
     */
    private fun safeQuery(
        db: EventDatabase,
        sql: String,
        start: Long,
        end: Long,
        block: (android.database.Cursor) -> Unit
    ) {
        try {
            val cursor = db.readableDatabase.rawQuery(sql, arrayOf(start.toString(), end.toString()))
            while (cursor.moveToNext()) { block(cursor) }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "safeQuery failed: $sql", e)
        }
    }

    /** Returns an UNKNOWN/zero profile when DB access fails entirely. */
    private fun buildEmptyProfile(
        start: Long, end: Long,
        compositeScore: Int, entropyScore: Int, kldScore: Int,
        sprtH1: Boolean, restoredCount: Int
    ) = RansomwareDnaProfile(
        profileId                  = UUID.randomUUID().toString(),
        generatedAt                = System.currentTimeMillis(),
        shieldVersion              = SHIELD_VERSION,
        attackWindowStart          = start,
        attackWindowEnd            = end,
        attackFamily               = AttackFamily.UNKNOWN,
        compositeScore             = compositeScore,
        entropyScore               = entropyScore,
        kldScore                   = kldScore,
        sprtAcceptedH1             = sprtH1,
        primaryDetector            = "Unknown",
        confidenceLevel            = "LOW",
        encryptionSpeedFilesPerMin = 0f,
        attackDurationSeconds      = (end - start) / 1000L,
        detectionTimeSeconds       = 0L,
        targetedExtensions         = emptyList(),
        targetPriority             = "Unknown",
        totalFilesAtRisk           = 0,
        honeyfileTriggered         = false,
        honeyfileTriggerDelaySeconds = -1L,
        c2AttemptDetected          = false,
        c2BlockedCount             = 0,
        torAttemptDetected         = false,
        portsTargeted              = emptyList(),
        filesEncryptedEstimate     = 0,
        filesRestoredCount         = restoredCount,
        dataLossOccurred           = false,
        estimatedRansomRupees      = 0L,
        suspectPackage             = null,
        suspectAppName             = null,
        timelineEvents             = emptyList()
    )
}
