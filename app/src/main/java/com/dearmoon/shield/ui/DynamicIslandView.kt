package com.dearmoon.shield.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import com.dearmoon.shield.R
import kotlin.math.roundToInt

/**
 * DynamicIslandView — fully custom Canvas-drawn pill progress indicator.
 *
 * Collapsed: 200×50dp pill with icon circle, step name, % text, shimmer, and progress fill.
 * Expanded:  360×110dp pill with the same + "Step X of 6" + 6 segment dots.
 *
 * Tap to toggle expand/collapse with OvershootInterpolator spring.
 */
class DynamicIslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Step / progress data ─────────────────────────────────────────────────

    private var currentStep = 0         // 0–5
    private var stepProgress = 0f       // 0f–100f

    data class StepData(val name: String, val iconRes: Int)

    private val stepData = listOf(
        StepData("About SHIELD",  R.drawable.ic_guide_shield),
        StepData("How It Works",  R.drawable.ic_guide_layers),
        StepData("Root Mode",     R.drawable.ic_guide_key),
        StepData("Standard Mode", R.drawable.ic_guide_phone),
        StepData("Your Controls", R.drawable.ic_guide_menu),
        StepData("You're Ready",  R.drawable.ic_guide_check)
    )

    // ── Expand/collapse dimensions ───────────────────────────────────────────

    private var isExpanded = false

    private val collapsedW get() = 200f.dpToPx()
    private val collapsedH get() = 50f.dpToPx()
    private val expandedW  get() = 360f.dpToPx()
    private val expandedH  get() = 110f.dpToPx()

    private var currentWidth  = 200f.dpToPx()
    private var currentHeight = 50f.dpToPx()

    // ── Paints ───────────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1E")
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f.spToPx()
        isFakeBoldText = true
    }

    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BCD4")
        textSize = 13f.spToPx()
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }

    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8892A4")
        textSize = 10f.spToPx()
    }

    private val tealFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00897B")
        style = Paint.Style.FILL
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Shimmer animator ─────────────────────────────────────────────────────

    private var shimmerOffset = 0f
    private var shimmerAnimator: ValueAnimator? = null

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        setWillNotDraw(false)
        setOnClickListener { toggleExpand() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shimmerAnimator?.cancel()
    }

    private fun startShimmer() {
        shimmerAnimator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                shimmerOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ── onMeasure / onSizeChanged ────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = currentWidth.roundToInt()
        val h = currentHeight.roundToInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildProgressShader(w.toFloat())
    }

    private fun rebuildProgressShader(width: Float) {
        progressPaint.shader = LinearGradient(
            0f, 0f, width, 0f,
            intArrayOf(
                Color.parseColor("#00897B"),
                Color.parseColor("#00BCD4")
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = currentWidth
        val h = currentHeight
        val r = h / 2f

        // 1. Background pill
        canvas.drawRoundRect(0f, 0f, w, h, r, r, bgPaint)

        // 2. Progress fill + shimmer clipped to pill
        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)

        val fillW = w * (stepProgress / 100f)
        canvas.drawRect(0f, 0f, fillW, h, progressPaint)

        // Shimmer
        val shimmerW = w * 0.4f
        val shimmerX = shimmerOffset * (w + shimmerW) - shimmerW
        shimmerPaint.shader = LinearGradient(
            shimmerX, 0f, shimmerX + shimmerW, 0f,
            intArrayOf(
                Color.argb(0,   255, 255, 255),
                Color.argb(25,  255, 255, 255),
                Color.argb(0,   255, 255, 255)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(shimmerX, 0f, shimmerX + shimmerW, h, shimmerPaint)

        canvas.restore()

        // 3. Content
        if (!isExpanded) drawCollapsedContent(canvas)
        else             drawExpandedContent(canvas)
    }

    private fun drawCollapsedContent(canvas: Canvas) {
        val h = currentHeight
        val w = currentWidth

        // Icon circle
        val iconR  = 14f.dpToPx()
        val iconCX = 8f.dpToPx() + iconR
        val iconCY = h / 2f
        canvas.drawCircle(iconCX, iconCY, iconR, tealFillPaint)

        // Step icon
        drawStepIcon(canvas, iconCX, iconCY, iconR * 0.75f)

        // Step name
        val textX = iconCX + iconR + 8f.dpToPx()
        val textY = iconCY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(stepData[currentStep].name, textX, textY, textPaint)

        // Removed percent right-aligned text as per request
    }

    private fun drawExpandedContent(canvas: Canvas) {
        val h = currentHeight
        val w = currentWidth

        // Top row — same as collapsed
        val iconR  = 14f.dpToPx()
        val iconCX = 8f.dpToPx() + iconR
        val topRowY = h * 0.30f

        canvas.drawCircle(iconCX, topRowY, iconR, tealFillPaint)
        drawStepIcon(canvas, iconCX, topRowY, iconR * 0.75f)

        val textX   = iconCX + iconR + 8f.dpToPx()
        val nameY   = topRowY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(stepData[currentStep].name, textX, nameY, textPaint)

        // "Step X of 6" below the name
        val subY = nameY + subtextPaint.textSize + 2f.dpToPx()
        canvas.drawText("Step ${currentStep + 1} of 6", textX, subY, subtextPaint)

        // Removed percent text as per request

        // Segment dots
        val segCount  = 6
        val padding   = 8f.dpToPx()
        val gap       = 4f.dpToPx()
        val segH      = 6f.dpToPx()
        val totalGaps = gap * (segCount - 1)
        val segW      = (w - padding * 2 - totalGaps) / segCount
        val segTop    = h - 14f.dpToPx()
        val segBot    = segTop + segH

        for (i in 0 until segCount) {
            val segX = padding + i * (segW + gap)
            segmentPaint.color = when {
                i < currentStep  -> Color.parseColor("#00897B")
                i == currentStep -> Color.parseColor("#00BCD4")
                else             -> Color.parseColor("#37474F")
            }
            canvas.drawRoundRect(segX, segTop, segX + segW, segBot, 3f, 3f, segmentPaint)
        }
    }

    /** Draws the vector icon for the current step centred at (cx, cy) with given radius. */
    private fun drawStepIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        try {
            val iconResId = stepData.getOrNull(currentStep)?.iconRes ?: return
            val d = ResourcesCompat.getDrawable(resources, iconResId, null) ?: return
            val ir = r.roundToInt()
            d.setBounds(
                (cx - ir).roundToInt(), (cy - ir).roundToInt(),
                (cx + ir).roundToInt(), (cy + ir).roundToInt()
            )
            d.setTint(Color.WHITE)
            d.draw(canvas)
        } catch (_: Exception) { /* icon missing — skip */ }
    }

    // ── Expand / Collapse ────────────────────────────────────────────────────

    private fun toggleExpand() {
        isExpanded = !isExpanded
        val startW = currentWidth
        val startH = currentHeight
        val targetW = if (isExpanded) expandedW else collapsedW
        val targetH = if (isExpanded) expandedH else collapsedH

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                currentWidth  = startW + (targetW - startW) * t
                currentHeight = startH + (targetH - startH) * t
                rebuildProgressShader(currentWidth)
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun setStep(stepIndex: Int) {
        currentStep = stepIndex.coerceIn(0, 5)
        invalidate()
    }

    fun setProgress(progress: Float) {
        stepProgress = progress.coerceIn(0f, 100f)
        invalidate()
    }

    // ── Extension helpers ────────────────────────────────────────────────────

    private fun Float.dpToPx(): Float =
        this * resources.displayMetrics.density

    private fun Float.spToPx(): Float =
        this * resources.displayMetrics.scaledDensity
}
