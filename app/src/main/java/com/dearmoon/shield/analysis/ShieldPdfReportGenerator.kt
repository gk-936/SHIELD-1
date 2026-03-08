package com.dearmoon.shield.analysis

import android.content.Context
import android.content.Intent
import com.dearmoon.shield.R
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Generates a purely monochrome (with red severity hints), professional,
 * printable CERT-In compliant Incident Report PDF.
 */
object ShieldPdfReportGenerator {

    private const val TAG = "SHIELD_PDF"

    // A4 page dimensions in points (72 dpi — no density scaling)
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f

    // ─────────────────────────────────────────────────────────────────────────
    // Initialized Paints
    // ─────────────────────────────────────────────────────────────────────────
    private val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(12, 0, 0, 0)
        textSize = 420f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val headerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        textSize = 9f
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }
    private val heavyRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val whitePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val labelBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        textSize = 9f
    }
    private val altRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F7F7F7")
        style = Paint.Style.FILL
        strokeWidth = 1f
    }
    private val monoTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 8f
        typeface = Typeface.MONOSPACE
    }
    private val italicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 9f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val smallGreyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 7f
    }
    private val scoreBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val scoreBarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────
    fun generatePdf(context: Context, profile: RansomwareDnaProfile): File {
        Log.d(TAG, "generatePdf() start — profile ${profile.profileId}")

        val doc = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), 1).create()
        val page = doc.startPage(info)
        val c = page.canvas

        // Compute shared properties
        val normalizedScore = ((profile.compositeScore / 130f) * 100).roundToInt().coerceIn(0, 100)
        val confidenceLabel = when (normalizedScore) {
            in 0..29 -> "LOW"
            in 30..54 -> "MEDIUM"
            in 55..74 -> "HIGH"
            else -> "CRITICAL"
        }
        val severityColor = when (normalizedScore) {
            in 0..29 -> Color.parseColor("#444444")
            in 30..54 -> Color.parseColor("#886600")
            in 55..74 -> Color.parseColor("#AA3300")
            else -> Color.parseColor("#CC0000")
        }

        // PAGE 1
        // 1. Watermark FIRST
        drawWatermark(c, context)

        // 2. Header
        drawHeader(c)

        // 3. Classification Badge
        drawClassificationBadge(c, profile, confidenceLabel, severityColor)

        // 4. Sections
        var currentY = 110f
        currentY = drawExecutiveSummary(c, profile, currentY)
        currentY = drawIncidentClassification(c, profile, currentY, normalizedScore, confidenceLabel)
        currentY = drawTargetProfile(c, profile, currentY)
        currentY = drawNetworkIntelligence(c, profile, currentY)
        
        // 5. Footer LAST
        drawFooter(c)

        doc.finishPage(page)

        // PAGE 2
        val info2 = PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), 2).create()
        val page2 = doc.startPage(info2)
        val c2 = page2.canvas

        drawWatermark(c2, context)
        drawHeader(c2)

        var currentY2 = 110f
        currentY2 = drawDamageAssessment(c2, profile, currentY2)
        currentY2 = drawAttribution(c2, profile, currentY2)
        currentY2 = drawHoneyfileIntelligence(c2, profile, currentY2)
        currentY2 = drawAttackTimeline(c2, profile, currentY2)

        drawFooter(c2)
        doc.finishPage(page2)

        // Export
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        
        val shortId = profile.profileId.take(8)
        val fileName = "SHIELD_Report_$shortId.pdf"
        val file = File(dir, fileName)
        
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()

        Log.d(TAG, "generatePdf() saved → ${file.absolutePath}")
        return file
    }

    fun generateAndShare(context: Context, profile: RansomwareDnaProfile) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val file = generatePdf(context, profile)
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "CERT-In Incident Report — ${profile.profileId.take(8)}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Official Report"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateAndShare() failed", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout Sections
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawWatermark(c: Canvas, context: Context) {
        val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_guide_shield)
        if (d != null) {
            val size = 350
            val cx = 297f
            val cy = 421f
            d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            d.alpha = 20 // slightly stronger faint mark

            // The SVG is white. We MUST tint it black for the watermark to be visible on white paper.
            d.colorFilter = android.graphics.PorterDuffColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
            
            d.draw(c)
        }
    }

    private fun drawHeader(c: Canvas) {
        // Left
        c.drawText("SHIELD", 36f, 36f, headerTitlePaint)
        c.drawText("Ransomware Early Warning System", 36f, 50f, headerSubPaint)
        
        val tsPaintItalic = Paint(headerSubPaint).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }
        c.drawText("Threat Intelligence Report", 36f, 62f, tsPaintItalic)

        // Right
        val rightAlignPaint = Paint(headerSubPaint).apply {
            textAlign = Paint.Align.RIGHT
            color = Color.BLACK
        }
        val tsFormatted = SimpleDateFormat("dd MMMM yyyy  |  HH:mm z", Locale.ENGLISH).format(Date())
        c.drawText(tsFormatted, 559f, 50f, rightAlignPaint)

        // Heavy rule
        c.drawLine(36f, 82f, 559f, 82f, heavyRulePaint)
    }

    private fun drawClassificationBadge(
        c: Canvas,
        profile: RansomwareDnaProfile,
        confidenceLabel: String,
        severityColor: Int
    ) {
        // Left - badge
        val badgeY = 100f // Adjusted y to align texts visually near y=90-100
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#444444")
            textSize = 9f
        }
        c.drawText("Threat Level:  ", 36f, badgeY, textPaint)
        
        val w = textPaint.measureText("Threat Level:  ")
        val sevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = severityColor
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        c.drawText("■ $confidenceLabel", 36f + w, badgeY, sevPaint)
    }

    private fun drawExecutiveSummary(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = startY
        y = drawSectionTitle(c, "EXECUTIVE SUMMARY", y, 523f, 36f)
        
        val left = 36f
        val lh = 16f
        
        val rsVal = formatRupees(profile.estimatedRansomRupees)
        
        c.drawText("Detection Time:   ", left, y + lh, labelBoldPaint)
        c.drawText("${profile.detectionTimeSeconds}s", left + 85f, y + lh, valuePaint)
        
        c.drawText("Files Protected:  ", left, y + lh * 2, labelBoldPaint)
        c.drawText("${profile.filesRestoredCount}", left + 85f, y + lh * 2, valuePaint)
        
        c.drawText("Financial Risk:   ", left, y + lh * 3, labelBoldPaint)
        c.drawText("$rsVal estimated", left + 85f, y + lh * 3, valuePaint)

        return y + (lh * 3) + 48f
    }

    private fun drawIncidentClassification(
        c: Canvas,
        profile: RansomwareDnaProfile,
        startY: Float,
        normalizedScore: Int,
        confidenceLabel: String
    ): Float {
        var y = startY
        y = drawSectionTitle(c, "INCIDENT CLASSIFICATION", y, 523f, 36f)

        val lh = 17f
        val left = 36f
        val rightColX = 36f + (523f * 0.55f)

        // Left Column 
        val kvPairs = listOf(
            "Attack Family:" to profile.attackFamily.displayName,
            "Primary Detector:" to profile.primaryDetector,
            "Confidence:" to confidenceLabel,
            "SPRT Decision:" to if (profile.sprtAcceptedH1) "ACCEPT H1" else "ACCEPT H0",
            "Entropy Score:" to "${profile.entropyScore}/40",
            "KLD Score:" to "${profile.kldScore}/30",
            "Composite Score:" to "$normalizedScore/100" // normalized 
        )

        for ((i, pair) in kvPairs.withIndex()) {
            val rowY = y + (i * lh) + 12f
            c.drawText(pair.first, left, rowY, labelBoldPaint)
            c.drawText(pair.second, left + 90f, rowY, valuePaint)
        }

        // Right Column - Score Visual
        val barY = y + 12f
        val titlePaint8pt = Paint(labelBoldPaint).apply { textSize = 8f }
        c.drawText("Threat Score", rightColX, barY, titlePaint8pt)

        val barTop = barY + 8f
        val barW = 160f
        val barH = 12f
        c.drawRect(rightColX, barTop, rightColX + barW, barTop + barH, scoreBarBorderPaint)
        c.drawRect(rightColX, barTop, rightColX + (normalizedScore / 100f * barW), barTop + barH, scoreBarPaint)

        val textBelowPaint = Paint(valuePaint).apply { 
            textSize = 8f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("$normalizedScore / 100", rightColX + (barW / 2f), barTop + barH + 12f, textBelowPaint)

        return y + (kvPairs.size * lh) + 48f
    }

    private fun drawTargetProfile(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = drawSectionTitle(c, "TARGET PROFILE", startY, 523f, 36f)
        
        y = drawTableRow(c, "Files at Risk", "${profile.totalFilesAtRisk}", y, 36f, 523f, false)
        y = drawTableRow(c, "Priority", sanitizeString(profile.targetPriority), y, 36f, 523f, true)
        
        val exts = profile.targetedExtensions.joinToString(", ").ifEmpty { "Unknown" }
        y = drawTableRow(c, "File Types Targeted", sanitizeString(exts), y, 36f, 523f, false)
        y = drawTableRow(c, "Attack Speed", "%.1f files/min".format(profile.encryptionSpeedFilesPerMin), y, 36f, 523f, true)
        y = drawTableRow(c, "Duration", "${profile.attackDurationSeconds}s", y, 36f, 523f, false)
        
        return y + 48f
    }

    private fun drawNetworkIntelligence(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = drawSectionTitle(c, "NETWORK INTELLIGENCE", startY, 523f, 36f)
        
        y = drawTableRow(c, "C2 Activity Detected", if (profile.c2AttemptDetected) "YES" else "NO", y, 36f, 523f, false)
        y = drawTableRow(c, "Network Block Applied", if (profile.c2BlockedCount > 0) "YES" else "NO", y, 36f, 523f, true)
        y = drawTableRow(c, "C2 Domain / IP", sanitizeString(profile.portsTargeted.joinToString(", ").ifEmpty { "None detected" }), y, 36f, 523f, false)
        
        return y + 48f
    }

    private fun drawDamageAssessment(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = drawSectionTitle(c, "DAMAGE ASSESSMENT", startY, 523f, 36f)
        
        val dataLossLabel = when {
            !profile.dataLossOccurred                                           -> "NONE"
            profile.filesRestoredCount >= profile.filesEncryptedEstimate        -> "NONE"
            profile.filesRestoredCount > 0                                      -> "PARTIAL"
            else                                                                -> "TOTAL"
        }

        val savings = formatRupees(profile.filesRestoredCount.toLong() * 15_000L)
        
        y = drawTableRow(c, "Files Modified", "${profile.filesEncryptedEstimate}", y, 36f, 523f, false)
        y = drawTableRow(c, "Files Restored", "${profile.filesRestoredCount}", y, 36f, 523f, true)
        y = drawTableRow(c, "Data Loss", dataLossLabel, y, 36f, 523f, false)
        y = drawTableRow(c, "Ransom Demanded", formatRupees(profile.estimatedRansomRupees), y, 36f, 523f, true)
        y = drawTableRow(c, "Savings via SHIELD", savings, y, 36f, 523f, false)

        if (profile.filesRestoredCount > 0) {
            y += 8f
            c.drawText("✓ SHIELD automatically restored ${profile.filesRestoredCount} file(s) without data loss.", 36f, y + 16f, labelBoldPaint)
            y += 24f
        }

        return y + 48f
    }

    private fun drawAttribution(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = drawSectionTitle(c, "ATTRIBUTION", startY, 523f, 36f)
        
        y = drawTableRow(c, "Suspect Package", sanitizeString(profile.suspectPackage ?: "UNKNOWN"), y, 36f, 523f, false)
        y = drawTableRow(c, "Application Name", sanitizeString(profile.suspectAppName ?: "UNKNOWN"), y, 36f, 523f, true)
        y = drawTableRow(c, "Source", if (profile.suspectPackage != null) "Sideloaded APK" else "N/A", y, 36f, 523f, false)
        
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
        y = drawTableRow(c, "First Event", timeFmt.format(Date(profile.attackWindowStart)), y, 36f, 523f, true)

        return y + 48f
    }

    private fun drawHoneyfileIntelligence(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = drawSectionTitle(c, "HONEYFILE INTELLIGENCE", startY, 523f, 36f)
        
        val delayStr = if (profile.honeyfileTriggerDelaySeconds < 0) "N/A" else "${profile.honeyfileTriggerDelaySeconds}s"
        
        y = drawTableRow(c, "Trap Triggered", if (profile.honeyfileTriggered) "YES" else "NO", y, 36f, 523f, false)
        y = drawTableRow(c, "Trigger Delay", delayStr, y, 36f, 523f, true)
        y = drawTableRow(c, "Target Node", "SHIELD_HONEYFILE_TRAP", y, 36f, 523f, false)
        y = drawTableRow(c, "Response Strategy", "Immediate HIGH_RISK protocol", y, 36f, 523f, true)

        return y + 48f
    }

    private fun drawAttackTimeline(c: Canvas, profile: RansomwareDnaProfile, startY: Float): Float {
        var y = drawSectionTitle(c, "ATTACK TIMELINE", startY, 523f, 36f)
        
        if (profile.timelineEvents.isEmpty()) {
            c.drawText("No timeline events recorded.", 36f, y + 12f, italicPaint)
            return y + 36f
        }

        // Limit to 8 to save space
        val events = profile.timelineEvents.take(8) 
        
        val lineX = 54f
        val rh = 28f // Timeline row height increased
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)

        // Connecting vertical line 
        c.drawLine(lineX, y + 10f, lineX, y + (events.size * rh) - 10f, rulePaint)

        for (event in events) {
            // Dot
            c.drawCircle(lineX, y + 12f, 3.5f, dotPaint)
            
            // Time
            c.drawText(timeFmt.format(Date(event.timestamp)), 62f, y + 16f, monoTimePaint)
            
            // Desc
            c.drawText(sanitizeString(event.description).take(55), 120f, y + 16f, valuePaint)
            
            y += rh
        }
        
        return y + 48f
    }

    private fun drawFooter(c: Canvas) {
        c.drawLine(36f, 810f, 559f, 810f, rulePaint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawSectionTitle(c: Canvas, title: String, y: Float, contentWidth: Float, margin: Float): Float {
        val ruleY = y + 8f // Added padding above the title
        c.drawLine(margin, ruleY, margin + contentWidth, ruleY, rulePaint)
        val titleW = titlePaint.measureText("  $title  ")
        c.drawRect(margin, ruleY - 9f, margin + titleW, ruleY + 2f, whitePaint)
        c.drawText("  $title  ", margin, ruleY, titlePaint)
        return ruleY + 24f // Increased spacing below the title
    }

    private fun drawTableRow(c: Canvas, label: String, value: String, y: Float, margin: Float, contentWidth: Float, isAlt: Boolean): Float {
        val rowH = 22f // Increased row height from 17f
        if (isAlt) {
            c.drawRect(margin, y, margin + contentWidth, y + rowH, altRowPaint) 
        }
        
        val col2X = margin + contentWidth * 0.40f
        c.drawText(label, margin + 8f, y + 15f, labelBoldPaint) // shifted text down accordingly
        c.drawText(value, col2X + 4f, y + 15f, valuePaint)
        
        return y + rowH
    }

    private fun formatRupees(amount: Long): String {
        return "Rs. " + if (amount >= 100_000L) {
            "${amount / 100_000}.${(amount % 100_000) / 10_000}L"
        } else {
            "$amount"
        }
    }

    /** Ensure we don't present raw "UNKNOWN", "null", or empty to a pro analyst. */
    private fun sanitizeString(s: String?): String {
        if (s.isNullOrBlank() || s.equals("null", true) || s.equals("UNKNOWN", true)) {
            return "N/A"
        }
        return s
    }
}
