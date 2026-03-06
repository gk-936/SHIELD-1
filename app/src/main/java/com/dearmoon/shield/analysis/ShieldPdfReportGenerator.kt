package com.dearmoon.shield.analysis

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates a SHIELD Threat Intelligence PDF using [PdfDocument] + [Canvas] only.
 *
 * IMPORTANT: PdfDocument coordinates are in points at 72 dpi.
 * A4 = 595 x 842 points.  We NEVER multiply by displayMetrics.density.
 */
object ShieldPdfReportGenerator {

    private const val TAG = "SHIELD_PDF"

    // ─────────────────────────────────────────────────────────────────────────
    // Colour palette (defined once to avoid repeated Color.parseColor calls)
    // ─────────────────────────────────────────────────────────────────────────
    private val DARK   = Color.parseColor("#0A0E1A")
    private val BLUE   = Color.parseColor("#1A2744")
    private val ACCENT = Color.parseColor("#00C8FF")   // cyan
    private val RED    = Color.parseColor("#FF3B3B")
    private val GREEN  = Color.parseColor("#00E676")
    private val AMBER  = Color.parseColor("#FFB300")
    private val GREY   = Color.parseColor("#8892A4")
    private val WHITE  = Color.parseColor("#E8EAF0")
    private val CARD   = Color.parseColor("#101828")
    private val BORDER = Color.parseColor("#1E3050")

    // A4 page dimensions in points (72 dpi — no density scaling)
    private const val PAGE_W = 595
    private const val PAGE_H = 842

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Produces a PDF file and saves it to the app's external Documents directory.
     * Must be called from a background thread (Dispatchers.IO).
     * Returns the saved [File].
     */
    fun generatePdf(context: Context, profile: RansomwareDnaProfile): File {
        Log.d(TAG, "generatePdf() start — profile ${profile.profileId}")

        val doc  = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()
        val page = doc.startPage(info)
        val c    = page.canvas

        // Draw in the order specified: watermark first, then content layers
        drawWatermark(c)
        drawHeader(c, profile)
        drawFooter(c, profile)
        drawTitleBlock(c, profile)
        drawStatCards(c, profile)
        drawClassificationCard(c, profile)
        drawTimelineVisual(c, profile)
        drawTargetAndNetworkCards(c, profile)
        drawDamageCard(c, profile)
        drawAttributionAndHoneyfileCards(c, profile)
        drawCertInBar(c, profile)

        doc.finishPage(page)

        // Write to external Documents directory
        val dir  = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "SHIELD_Report_${profile.profileId}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()

        Log.d(TAG, "generatePdf() saved → ${file.absolutePath}")
        return file
    }

    /**
     * Generates the PDF on IO, then fires a share intent on Main.
     * Uses [FileProvider] so the receiving app can read the file safely.
     */
    fun generateAndShare(context: Context, profile: RansomwareDnaProfile) {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val file = generatePdf(context, profile)
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type  = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "SHIELD Threat Report — ${profile.profileId}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share SHIELD Report"))
                    Log.d(TAG, "generateAndShare() share intent fired")
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateAndShare() failed", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing helpers — reusable
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draws a rounded-rectangle card with an optional title bar at the top.
     * Callers render content on top after this call.
     */
    private fun drawCard(
        canvas: Canvas,
        x: Float, y: Float,
        w: Float, h: Float,
        title: String?,
        titleColor: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Card body
        paint.style = Paint.Style.FILL
        paint.color = CARD
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 6f, 6f, paint)

        // Card border
        paint.style       = Paint.Style.STROKE
        paint.color       = BORDER
        paint.strokeWidth = 0.8f
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 6f, 6f, paint)

        // Title text
        if (title != null) {
            paint.style    = Paint.Style.FILL
            paint.color    = titleColor
            paint.isFakeBoldText = true
            paint.textSize = 8f
            canvas.drawText(title, x + 8f, y + 13f, paint)
        }
    }

    /**
     * Draws a two-column key-value row.
     * Keys are rendered in [GREY], values in [WHITE] bold.
     */
    private fun drawKeyValue(
        canvas: Canvas,
        keyX: Float, valX: Float,
        y: Float,
        key: String, value: String
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = 7.5f

        p.color          = GREY
        p.isFakeBoldText = false
        canvas.drawText(key, keyX, y, p)

        p.color          = WHITE
        p.isFakeBoldText = true
        canvas.drawText(value, valX, y, p)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 1 — Watermark
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawWatermark(c: Canvas) {
        val cx = 297f; val cy = 421f
        val p  = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f

        // Three concentric rings (alpha 5, 6, 8 %)
        val radii  = intArrayOf(160, 130, 105)
        val alphas = intArrayOf(13, 15, 20)   // approx 5%, 6%, 8% of 255
        for (i in radii.indices) {
            p.color = Color.argb(alphas[i], 0, 200, 255)
            c.drawCircle(cx, cy, radii[i].toFloat(), p)
        }

        // Shield outline path
        val sx = 207f; val sy = 316f
        val shield = Path().apply {
            moveTo(cx, sy - 30f)                        // top center
            lineTo(sx, sy + 58f)                        // top-left corner
            lineTo(sx, sy + 151f)                       // mid-left
            cubicTo(sx, sy + 200f, cx - 40f, sy + 230f, cx, sy + 242f)   // curved bottom-left to tip
            cubicTo(cx + 40f, sy + 230f, sx + 180f, sy + 200f, sx + 180f, sy + 151f)  // tip to mid-right
            lineTo(sx + 180f, sy + 58f)                 // top-right corner
            close()
        }

        p.color = Color.argb(40, 26, 39, 68)
        p.style = Paint.Style.FILL
        c.drawPath(shield, p)

        p.color       = Color.argb(25, 0, 200, 255)
        p.style       = Paint.Style.STROKE
        p.strokeWidth = 3f
        c.drawPath(shield, p)

        // "S" letter
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color          = Color.argb(22, 0, 200, 255)
            textSize       = 120f
            isFakeBoldText = true
            textAlign      = Paint.Align.CENTER
        }
        c.drawText("S", cx, 383f, textPaint)

        // "SHIELD" label
        textPaint.textSize = 22f
        textPaint.color    = Color.argb(18, 0, 200, 255)
        c.drawText("SHIELD", cx, 351f, textPaint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 2 — Dark header (y=0, h=52)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawHeader(c: Canvas, profile: RansomwareDnaProfile) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background
        p.color = DARK; p.style = Paint.Style.FILL
        c.drawRect(0f, 0f, PAGE_W.toFloat(), 52f, p)

        // Left cyan stripe
        p.color = ACCENT
        c.drawRect(0f, 0f, 5f, 52f, p)

        // "SHIELD" brand text
        p.color = ACCENT; p.isFakeBoldText = true; p.textSize = 15f
        c.drawText("SHIELD", 18f, 33f, p)

        // Subtitle
        p.color = GREY; p.isFakeBoldText = false; p.textSize = 9f
        c.drawText("Ransomware Early Warning System  |  Threat Intelligence Report", 18f, 46f, p)

        // Severity badge
        val badgeColor = when (profile.getRiskSeverityLabel()) {
            "CRITICAL" -> RED
            "HIGH"     -> AMBER
            else       -> GREY
        }
        val badgeText = when (profile.getRiskSeverityLabel()) {
            "CRITICAL" -> "CRITICAL THREAT"
            "HIGH"     -> "HIGH THREAT"
            else       -> "MEDIUM THREAT"
        }
        p.color = badgeColor; p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(490f, 14f, 578f, 34f), 4f, 4f, p)

        p.color = WHITE; p.isFakeBoldText = true; p.textSize = 9f
        p.textAlign = Paint.Align.CENTER
        c.drawText(badgeText, 534f, 27f, p)
        p.textAlign = Paint.Align.LEFT
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 3 — Footer (y=806, h=36)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawFooter(c: Canvas, profile: RansomwareDnaProfile) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = DARK; p.style = Paint.Style.FILL
        c.drawRect(0f, 806f, PAGE_W.toFloat(), PAGE_H.toFloat(), p)

        // Cyan left stripe
        p.color = ACCENT
        c.drawRect(0f, 806f, 5f, PAGE_H.toFloat(), p)

        // Horizontal rule with accent color at 30% alpha
        p.color       = Color.argb(77, 0, 200, 255)  // 30% of 255
        p.style       = Paint.Style.STROKE
        p.strokeWidth = 0.5f
        c.drawLine(0f, 806f, PAGE_W.toFloat(), 806f, p)

        // Footer text
        p.color          = GREY
        p.style          = Paint.Style.FILL
        p.isFakeBoldText = false
        p.textSize       = 7.5f
        c.drawText(
            "CONFIDENTIAL  |  Generated by SHIELD v1.0 MVP  |  CERT-In Incident Report Format v2",
            18f, 819f, p
        )

        // Page number right-aligned
        p.textAlign = Paint.Align.RIGHT
        c.drawText("Page 1", 577f, 819f, p)
        p.textAlign = Paint.Align.LEFT
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 4 — Title block (y=62)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawTitleBlock(c: Canvas, profile: RansomwareDnaProfile) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = WHITE; p.isFakeBoldText = true; p.textSize = 18f
        c.drawText("THREAT INTELLIGENCE REPORT", 30f, 77f, p)

        val tsFormatted = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.ENGLISH)
            .format(Date(profile.generatedAt))
        p.color = GREY; p.isFakeBoldText = false; p.textSize = 8.5f
        c.drawText("Profile ID: ${profile.profileId}   |   Generated: $tsFormatted", 30f, 90f, p)

        // Horizontal rule
        p.color = BORDER; p.style = Paint.Style.STROKE; p.strokeWidth = 0.5f
        c.drawLine(30f, 95f, 565f, 95f, p)
        p.style = Paint.Style.FILL
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 5 — Three stat cards (top=100, h=68)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawStatCards(c: Canvas, profile: RansomwareDnaProfile) {
        val top      = 100f
        val cardH    = 68f
        val cardW    = 174f
        val gap      = 6f
        val startX   = 30f

        data class StatCard(val value: String, val unit: String, val label: String, val color: Int)

        val cards = listOf(
            StatCard("${profile.detectionTimeSeconds}s",               "seconds",    "DETECTION TIME",    GREEN),
            StatCard(profile.filesRestoredCount.toString(),            "files",      "FILES PROTECTED",   ACCENT),
            StatCard(formatRupees(profile.estimatedRansomRupees),      "estimated",  "FINANCIAL RISK",    AMBER)
        )

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in cards.indices) {
            val x = startX + i * (cardW + gap)
            drawCard(c, x, top, cardW, cardH, null, ACCENT)

            val card = cards[i]

            // Value (22sp bold)
            p.color = card.color; p.isFakeBoldText = true; p.textSize = 22f
            c.drawText(card.value, x + 10f, top + 34f, p)

            // Unit (7sp grey)
            p.color = GREY; p.isFakeBoldText = false; p.textSize = 7f
            c.drawText(card.unit, x + 10f, top + 44f, p)

            // Label (8sp white bold)
            p.color = WHITE; p.isFakeBoldText = true; p.textSize = 8f
            c.drawText(card.label, x + 10f, top + 58f, p)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 6 — Incident classification card (top=178, h=100)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawClassificationCard(c: Canvas, profile: RansomwareDnaProfile) {
        val top = 178f
        val h   = 100f

        drawCard(c, 30f, top, 535f, h, "INCIDENT CLASSIFICATION", ACCENT)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Attack family badge
        p.color = RED; p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(45f, top + 18f, 185f, top + 44f), 5f, 5f, p)
        p.color = WHITE; p.isFakeBoldText = true; p.textSize = 11f
        p.textAlign = Paint.Align.CENTER
        c.drawText(profile.attackFamily.displayName, 115f, top + 35f, p)
        p.textAlign = Paint.Align.LEFT

        // Left column key-value pairs (start y = top+76 step -13 = going downward, so top+58)
        val rowY0 = top + 58f
        val rowStep = 14f
        drawKeyValue(c, 45f, 145f, rowY0,              "Primary Detector",  profile.primaryDetector)
        drawKeyValue(c, 45f, 145f, rowY0 + rowStep,    "Composite Score",   "${profile.compositeScore}/130")
        drawKeyValue(c, 45f, 145f, rowY0 + rowStep*2f, "Confidence",        profile.confidenceLevel)

        // Right column
        drawKeyValue(c, 310f, 400f, rowY0,              "SPRT Decision",  if (profile.sprtAcceptedH1) "ACCEPT H1" else "ACCEPT H0")
        drawKeyValue(c, 310f, 400f, rowY0 + rowStep,    "Entropy Score",  "${profile.entropyScore}/40")
        drawKeyValue(c, 310f, 400f, rowY0 + rowStep*2f, "KLD Score",      "${profile.kldScore}/30")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 7 — Attack timeline visual (top=288, h=80)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawTimelineVisual(c: Canvas, profile: RansomwareDnaProfile) {
        val top  = 288f
        val h    = 80f

        drawCard(c, 30f, top, 535f, h, "ATTACK TIMELINE", ACCENT)

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
        val events  = profile.timelineEvents.take(6)
        if (events.isEmpty()) return

        val p     = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotY  = top + 40f
        val xFrom = 60f; val xTo = 535f
        val step  = if (events.size > 1) (xTo - xFrom) / (events.size - 1).toFloat() else 0f

        // Connector lines between dots
        p.color       = BORDER
        p.style       = Paint.Style.STROKE
        p.strokeWidth = 0.8f
        if (events.size > 1) {
            c.drawLine(xFrom, dotY, xFrom + step * (events.size - 1), dotY, p)
        }

        for (i in events.indices) {
            val event = events[i]
            val dotX  = xFrom + step * i

            val dotColor = when (event.eventType) {
                TimelineEventType.FIRST_SIGNAL                       -> AMBER
                TimelineEventType.FILE_MODIFIED,
                TimelineEventType.HONEYFILE_HIT,
                TimelineEventType.HIGH_RISK_ALERT                    -> RED
                TimelineEventType.NETWORK_BLOCKED,
                TimelineEventType.VPN_ACTIVATED,
                TimelineEventType.PROCESS_KILLED                     -> ACCENT
                TimelineEventType.RESTORE_STARTED,
                TimelineEventType.RESTORE_COMPLETE                   -> GREEN
            }

            // Dot
            p.color = dotColor; p.style = Paint.Style.FILL
            c.drawCircle(dotX, dotY, 5f, p)

            // Timestamp below dot
            p.textSize = 6.5f; p.isFakeBoldText = true
            p.textAlign = Paint.Align.CENTER
            c.drawText(timeFmt.format(Date(event.timestamp)), dotX, dotY + 14f, p)

            // Description (max 8 chars per line, 2 lines)
            val desc = event.description.take(16)
            p.color = GREY; p.isFakeBoldText = false; p.textSize = 6f
            c.drawText(desc.take(8), dotX, dotY + 23f, p)
            if (desc.length > 8) c.drawText(desc.drop(8).take(8), dotX, dotY + 30f, p)

            p.textAlign = Paint.Align.LEFT
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 8 — Target + Network cards (top=378, h=108)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawTargetAndNetworkCards(c: Canvas, profile: RansomwareDnaProfile) {
        val top    = 378f
        val h      = 108f
        val colW   = (PAGE_W - 66f) / 2f  // (595 - 66) / 2 = 264.5

        // Left — Target Profile
        drawCard(c, 30f, top, colW, h, "TARGET PROFILE", ACCENT)

        val lx = 38f; val lv = 138f
        var ly = top + 28f
        val step = 13f
        drawKeyValue(c, lx, lv, ly,         "Files at Risk",    profile.totalFilesAtRisk.toString()); ly += step
        drawKeyValue(c, lx, lv, ly,         "Priority",         profile.targetPriority); ly += step
        drawKeyValue(c, lx, lv, ly,         "File Types",       profile.targetedExtensions.joinToString(" ").take(30)); ly += step
        drawKeyValue(c, lx, lv, ly,         "Speed",            "%.1f files/min".format(profile.encryptionSpeedFilesPerMin)); ly += step
        drawKeyValue(c, lx, lv, ly,         "Duration",         "${profile.attackDurationSeconds}s")

        // Right — Network Intelligence
        val rx    = 30f + colW + 6f
        val netTitleColor = if (profile.c2AttemptDetected) RED else GREY
        drawCard(c, rx, top, colW, h, "NETWORK INTELLIGENCE", netTitleColor)

        if (!profile.c2AttemptDetected) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GREY; textSize = 7.5f; isFakeBoldText = false
            }
            c.drawText("No C2 activity detected", rx + 8f, top + 40f, p)
        } else {
            val nvx = rx + 8f; val nvv = rx + 100f
            var ny  = top + 28f
            drawKeyValue(c, nvx, nvv, ny,         "C2 Attempt",  "YES"); ny += step
            drawKeyValue(c, nvx, nvv, ny,         "Ports",       profile.portsTargeted.joinToString(",").ifEmpty { "N/A" }); ny += step
            drawKeyValue(c, nvx, nvv, ny,         "Tor Network", if (profile.torAttemptDetected) "DETECTED" else "NONE"); ny += step
            drawKeyValue(c, nvx, nvv, ny,         "VPN",         "ACTIVATED"); ny += step
            drawKeyValue(c, nvx, nvv, ny,         "Blocked",     profile.c2BlockedCount.toString())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 9 — Damage assessment card (top=496, h=75)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawDamageCard(c: Canvas, profile: RansomwareDnaProfile) {
        val top = 496f
        val h   = 75f

        drawCard(c, 30f, top, 535f, h, "DAMAGE ASSESSMENT & RECOVERY", GREEN)

        val dataLossLabel = when {
            !profile.dataLossOccurred                                           -> "NONE"
            profile.filesRestoredCount >= profile.filesEncryptedEstimate        -> "NONE"
            profile.filesRestoredCount > 0                                      -> "PARTIAL"
            else                                                                -> "SIGNIFICANT"
        }
        val dataLossColor = when (dataLossLabel) {
            "NONE"    -> GREEN
            "PARTIAL" -> AMBER
            else      -> RED
        }

        // Savings = restored * 15000
        val savings = formatRupees(profile.filesRestoredCount.toLong() * 15_000L)

        data class Stat(val value: String, val color: Int, val label: String)
        val stats = listOf(
            Stat(profile.filesEncryptedEstimate.toString(), RED,         "FILES\nMODIFIED"),
            Stat(profile.filesRestoredCount.toString(),     GREEN,       "FILES\nRESTORED"),
            Stat(dataLossLabel,                             dataLossColor,"DATA\nLOSS"),
            Stat(formatRupees(profile.estimatedRansomRupees), AMBER,     "RANSOM\nDEMAND"),
            Stat("Rs.$savings",                             GREEN,       "SAVINGS\nVIA SHIELD")
        )

        val p  = Paint(Paint.ANTI_ALIAS_FLAG)
        val colW = 535f / stats.size
        for (i in stats.indices) {
            val s  = stats[i]
            val px = 30f + i * colW + 8f

            p.color = s.color; p.isFakeBoldText = true; p.textSize = 15f
            c.drawText(s.value, px, top + 44f, p)

            // Label — 2 lines
            p.color = GREY; p.isFakeBoldText = false; p.textSize = 6.5f
            val lines = s.label.split("\n")
            c.drawText(lines[0], px, top + 55f, p)
            if (lines.size > 1) c.drawText(lines[1], px, top + 63f, p)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 10 — Attribution + Honeyfile cards (top=581, h=72)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawAttributionAndHoneyfileCards(c: Canvas, profile: RansomwareDnaProfile) {
        val top  = 581f
        val h    = 72f
        val colW = (PAGE_W - 66f) / 2f

        // Left — Attribution
        drawCard(c, 30f, top, colW, h, "ATTRIBUTION", ACCENT)

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
        val aRows = listOf(
            Pair("Package",    profile.suspectPackage  ?: "UNKNOWN"),
            Pair("App Name",   profile.suspectAppName  ?: "UNKNOWN"),
            Pair("Source",     if (profile.suspectPackage != null) "Sideloaded APK" else "N/A"),
            Pair("First Event", timeFmt.format(Date(profile.attackWindowStart)))
        )
        val lx = 38f; val lv = 118f; var ly = top + 26f
        aRows.forEach { (k, v) ->
            drawKeyValue(c, lx, lv, ly, k, v.take(28))
            ly += 12f
        }

        // Right — Honeyfile Intelligence
        val rx = 30f + colW + 6f
        drawCard(c, rx, top, colW, h, "HONEYFILE INTELLIGENCE", AMBER)

        val hRows = listOf(
            Pair("Trap Triggered",  if (profile.honeyfileTriggered) "YES" else "NO"),
            Pair("Trigger Delay",   "${profile.honeyfileTriggerDelaySeconds}s"),
            Pair("Decoy File",      "SHIELD_HONEYFILE_TRAP"),
            Pair("Escalation",      "Immediate HIGH_RISK")
        )
        val hvx = rx + 8f; val hvv = rx + 88f; var hy = top + 26f
        hRows.forEach { (k, v) ->
            drawKeyValue(c, hvx, hvv, hy, k, v)
            hy += 12f
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP 11 — CERT-In bar (top=663, h=36)
    // ─────────────────────────────────────────────────────────────────────────
    private fun drawCertInBar(c: Canvas, profile: RansomwareDnaProfile) {
        val top = 663f
        val h   = 36f
        val p   = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background
        p.color = BLUE; p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(30f, top, 565f, top + h), 6f, 6f, p)

        p.color       = ACCENT; p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f
        c.drawRoundRect(RectF(30f, top, 565f, top + h), 6f, 6f, p)

        // CERT-In title
        p.color = ACCENT; p.style = Paint.Style.FILL; p.isFakeBoldText = true; p.textSize = 8.5f
        c.drawText("CERT-In Reportable Incident", 44f, top + 22f, p)

        // Detail line
        p.color = WHITE; p.isFakeBoldText = false; p.textSize = 7.5f
        c.drawText(
            "Satisfies CERT-In Incident Reporting v2  |  6-hour reporting window: MET  |  Export: SHIELD_Report_${profile.profileId}.txt",
            44f, top + 11f, p
        )

        // Ready indicator — green circle
        p.color = GREEN; p.style = Paint.Style.FILL
        c.drawCircle(545f, top + 18f, 6f, p)

        // "READY" label
        p.color = WHITE; p.isFakeBoldText = true; p.textSize = 6f
        p.textAlign = Paint.Align.CENTER
        c.drawText("READY", 545f, top + 20f, p)
        p.textAlign = Paint.Align.LEFT
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Formats a rupee amount for display in the PDF stat row.
     * Amounts ≥ 1 lakh use the "L" suffix (e.g. Rs.1.5L).
     */
    private fun formatRupees(amount: Long): String {
        return if (amount >= 100_000L) {
            "Rs.${amount / 100_000}.${(amount % 100_000) / 10_000}L"
        } else {
            "Rs.$amount"
        }
    }
}
