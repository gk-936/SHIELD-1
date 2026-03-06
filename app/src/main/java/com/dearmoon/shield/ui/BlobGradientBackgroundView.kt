package com.dearmoon.shield.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * BlobGradientBackgroundView
 *
 * Static two-blob radial-gradient background:
 *  • Pure black base  #08080F
 *  • Deep purple glow — top-left  (cx=10%, cy=8%,  r=80% of width)
 *  • Deep orange glow — top-right (cx=90%, cy=5%,  r=75% of width)
 *  • PorterDuff.Mode.ADD composites the two blobs inside a saveLayer()
 *    so they brighten each other where they overlap at the top-centre,
 *    matching the vivid "hot zone" in the reference image.
 *
 * Rules:
 *  - Paint objects created once (in the class body / init).
 *  - RadialGradient shaders built in onSizeChanged(), never in onDraw().
 *  - canvas.saveLayer() wraps the blob draws so ADD blends against the
 *    black base layer, not the window compositor surface.
 *  - No animation — static is more premium here.
 *
 * TUNING GUIDE:
 *  Too dark/invisible     → increase argb alpha values (max 255)
 *  Too bright/washed out  → decrease alpha values
 *  Blobs too small        → increase radius multipliers (0.80f etc.)
 *  Purple too far left    → increase purpleCX multiplier (e.g. 0.15f)
 *  Orange too far right   → decrease orangeCX multiplier (e.g. 0.85f)
 *  Overlap too bright     → switch ADD → SCREEN (softer mix)
 *  Overlap too dark       → switch SCREEN → ADD (brighter mix)
 */
class BlobGradientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Base fill ─────────────────────────────────────────────────────────────

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0A0A0A") // Deep Charcoal/Black Base
    }

    // ── Blob paints ───────────────────────────────────────────────────────────
    // Using SCREEN to blend the glows softly into the black background

    private val purplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    private val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    private val indigoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }

    // ── onSizeChanged: build RadialGradient shaders here, NOT in onDraw ───────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        // ── Primary Glow (Top Left): #6300B4 to #A259FF (Electric Purple/Violet)
        val purpleCX = w * 0.15f
        val purpleCY = h * 0.08f
        val purpleR  = w * 0.90f // Large, soft edge

        purplePaint.shader = RadialGradient(
            purpleCX, purpleCY, purpleR,
            intArrayOf(
                Color.parseColor("#CC6300B4"),  // Dense electric purple center
                Color.parseColor("#66A259FF"),  // Transitions to violet
                Color.parseColor("#00A259FF")   // Transparent edge
            ),
            floatArrayOf(0f, 0.40f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // ── Secondary Glow (Top Right): #C8501E to #FF8C00 (Burnt Orange/Amber)
        val orangeCX = w * 0.85f
        val orangeCY = h * 0.05f
        val orangeR  = w * 0.70f // Slightly smaller, intense

        orangePaint.shader = RadialGradient(
            orangeCX, orangeCY, orangeR,
            intArrayOf(
                Color.parseColor("#D9C8501E"),  // Intense burnt orange center
                Color.parseColor("#80FF8C00"),  // Transitions to amber
                Color.parseColor("#00FF8C00")   // Transparent edge
            ),
            floatArrayOf(0f, 0.35f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // ── Accent Glow (Subtle Middle): #3A1078 (Deep Indigo)
        val indigoCX = w * 0.50f
        val indigoCY = h * 0.30f
        val indigoR  = w * 1.00f // Broad & subtle

        indigoPaint.shader = RadialGradient(
            indigoCX, indigoCY, indigoR,
            intArrayOf(
                Color.parseColor("#663A1078"),  // 40% deep indigo
                Color.parseColor("#223A1078"),  // 13% deep indigo
                Color.parseColor("#003A1078")   // Transparent
            ),
            floatArrayOf(0f, 0.50f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    // ── onDraw ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Step 1 — solid #0A0A0A base directly on the canvas
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Step 2 — isolated layer so SCREEN composites blobs against each other
        //          and against the base, NOT the window surface behind.
        val saveCount = canvas.saveLayer(0f, 0f, w, h, null)

        canvas.drawRect(0f, 0f, w, h, purplePaint)
        canvas.drawRect(0f, 0f, w, h, orangePaint)
        canvas.drawRect(0f, 0f, w, h, indigoPaint)

        // Step 3 — restore to merge the layer back with the base
        canvas.restoreToCount(saveCount)
    }
}
