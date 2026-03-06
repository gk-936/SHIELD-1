package com.dearmoon.shield.ui

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.dearmoon.shield.R

/**
 * WavyUnderlineTextView
 *
 * Displays a string that:
 *  1. Slides in from y-20dp upward to y=0 while fading 0→1 (all text at once).
 *  2. Draws a wavy (quadratic-bezier) underline from left to right using
 *     PathMeasure.getSegment() so the stroke grows progressively.
 *  3. On tap: morphs the wave to its vertical inverse and back over 1200ms.
 *
 * Paints are created in init{}. Paths are built in onSizeChanged(). Neither
 * is created inside onDraw().
 */
class WavyUnderlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Attribute-backed fields ───────────────────────────────────────────────

    private var text: String = ""
    private var textSizePx: Float = 32f.spToPx()
    private var textColor: Int = 0xFFE8EAF0.toInt()
    private var underlineColor: Int = 0xFF00C8FF.toInt()
    private var underlineStrokeWidthPx: Float = 3f.dpToPx()
    private var underlineDuration: Long = 1500L
    private var textSlideDuration: Long = 600L
    private var wavyAmplitudePx: Float = 10f.dpToPx()
    private var underlineOffsetPx: Float = 8f.dpToPx()

    // ── Animation state ───────────────────────────────────────────────────────

    private var textAlpha: Float = 0f
    private var textTranslationY: Float = -20f   // dp; animated -20→0
    private var pathProgress: Float = 0f          // 0f→1f controls drawn length
    private var isInverseWave: Boolean = false

    // ── Path objects (built in onSizeChanged) ────────────────────────────────

    private var measuredPath: Path = Path()
    private var segmentPath: Path = Path()
    private var pathMeasure: PathMeasure = PathMeasure()
    private var totalPathLength: Float = 0f

    // ── Paint objects (created in init{}) ────────────────────────────────────

    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val pathPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // ── Active animators (cancelled on detach) ───────────────────────────────

    private val activeAnimators = mutableListOf<ValueAnimator>()

    // ── Init: read XML attributes ─────────────────────────────────────────────

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.WavyUnderlineTextView)
            try {
                text = ta.getString(R.styleable.WavyUnderlineTextView_wut_text) ?: ""
                textSizePx = if (ta.hasValue(R.styleable.WavyUnderlineTextView_wut_textSize))
                    ta.getDimension(R.styleable.WavyUnderlineTextView_wut_textSize, 32f.spToPx())
                else 32f.spToPx()
                textColor = ta.getColor(
                    R.styleable.WavyUnderlineTextView_wut_textColor, 0xFFE8EAF0.toInt()
                )
                underlineColor = ta.getColor(
                    R.styleable.WavyUnderlineTextView_wut_underlineColor, 0xFF00C8FF.toInt()
                )
                underlineStrokeWidthPx = if (ta.hasValue(
                        R.styleable.WavyUnderlineTextView_wut_underlineStrokeWidth))
                    ta.getDimension(
                        R.styleable.WavyUnderlineTextView_wut_underlineStrokeWidth, 3f.dpToPx()
                    )
                else 3f.dpToPx()
                underlineDuration = ta.getInt(
                    R.styleable.WavyUnderlineTextView_wut_underlineDuration, 1500
                ).toLong()
                textSlideDuration = ta.getInt(
                    R.styleable.WavyUnderlineTextView_wut_textSlideDuration, 600
                ).toLong()
                wavyAmplitudePx = if (ta.hasValue(
                        R.styleable.WavyUnderlineTextView_wut_wavyAmplitude))
                    ta.getDimension(
                        R.styleable.WavyUnderlineTextView_wut_wavyAmplitude, 10f.dpToPx()
                    )
                else 10f.dpToPx()
                underlineOffsetPx = if (ta.hasValue(
                        R.styleable.WavyUnderlineTextView_wut_underlineOffset))
                    ta.getDimension(
                        R.styleable.WavyUnderlineTextView_wut_underlineOffset, 8f.dpToPx()
                    )
                else 8f.dpToPx()
            } finally {
                ta.recycle()
            }
        }

        // Apply attribute values to paints
        textPaint.textSize = textSizePx
        textPaint.color = textColor
        pathPaint.color = underlineColor
        pathPaint.strokeWidth = underlineStrokeWidthPx
    }

    // ── Path builders ─────────────────────────────────────────────────────────

    /**
     * Normal wave: peaks UP at width×0.25, dips DOWN at width×0.75.
     * Modelled on SVG: M 0,amp  Q w*0.25,0  w*0.5,amp  Q w*0.75,amp*2  w,amp
     */
    private fun buildWavePath(w: Float, amp: Float): Path = Path().apply {
        moveTo(0f, amp)
        quadTo(w * 0.25f, 0f,       w * 0.5f, amp)
        quadTo(w * 0.75f, amp * 2f, w,        amp)
    }

    /**
     * Inverse wave: dips DOWN at width×0.25, peaks UP at width×0.75.
     */
    private fun buildInverseWavePath(w: Float, amp: Float): Path = Path().apply {
        moveTo(0f, amp)
        quadTo(w * 0.25f, amp * 2f, w * 0.5f, amp)
        quadTo(w * 0.75f, 0f,       w,         amp)
    }

    // ── onSizeChanged: build paths here, NOT in onDraw ───────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        measuredPath = buildWavePath(w.toFloat(), wavyAmplitudePx)
        pathMeasure = PathMeasure(measuredPath, false)
        totalPathLength = pathMeasure.length
        segmentPath = Path()
        isInverseWave = false   // reset toggle on size change
    }

    // ── onMeasure ─────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val totalHeight = (textHeight + underlineOffsetPx
                + wavyAmplitudePx * 2f
                + 8f.dpToPx()
                + paddingTop + paddingBottom)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            totalHeight.toInt()
        )
    }

    // ── onDraw ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        // Vertical centre of the text block (baseline is at textHeight - descent below this)
        val textCentreY = (height - (textHeight + underlineOffsetPx + wavyAmplitudePx * 2f)) / 2f
        // Baseline for drawText (Align.CENTER handles horizontal centring)
        val baseline = textCentreY - fm.ascent + textTranslationY.dpToPx()

        // 1. Draw text
        textPaint.alpha = (textAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawText(text, width / 2f, baseline, textPaint)

        // 2. Draw partial wavy underline using PathMeasure
        if (pathProgress > 0f && totalPathLength > 0f) {
            segmentPath.reset()
            pathMeasure.getSegment(
                0f,
                totalPathLength * pathProgress,
                segmentPath,
                true
            )
            // Translate so the wave sits just below the text baseline
            val pathTop = baseline + underlineOffsetPx
            canvas.translate(0f, pathTop)
            canvas.drawPath(segmentPath, pathPaint)
        }

        canvas.restore()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the intro animation: text slides+fades in, then the wavy underline
     * draws itself from left to right.
     */
    fun startAnimation() {
        // Cancel anything already running
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()

        // Reset state
        textAlpha = 0f
        textTranslationY = -20f
        pathProgress = 0f
        invalidate()

        // Text translation: -20dp → 0dp (ease-out)
        val translAnim = ValueAnimator.ofFloat(-20f, 0f).apply {
            duration = textSlideDuration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                textTranslationY = it.animatedValue as Float
                invalidate()
            }
        }

        // Text alpha: 0 → 1 (linear)
        val alphaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = textSlideDuration
            interpolator = LinearInterpolator()
            addUpdateListener {
                textAlpha = it.animatedValue as Float
                invalidate()
            }
        }

        // Wavy path draw: progress 0 → 1 (accelerate-decelerate, longer duration)
        val pathAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = underlineDuration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pathProgress = it.animatedValue as Float
                invalidate()
            }
        }

        translAnim.start()
        alphaAnim.start()
        pathAnim.start()
        activeAnimators += translAnim
        activeAnimators += alphaAnim
        activeAnimators += pathAnim
    }

    // ── Touch: tap to morph wave ──────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            morphWave()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun morphWave() {
        isInverseWave = !isInverseWave
        val targetPath = if (isInverseWave)
            buildInverseWavePath(width.toFloat(), wavyAmplitudePx)
        else
            buildWavePath(width.toFloat(), wavyAmplitudePx)

        // Phase 1: retract the current wave (progress 1→0)
        val retractAnim = ValueAnimator.ofFloat(pathProgress, 0f).apply {
            duration = 400L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pathProgress = it.animatedValue as Float
                invalidate()
            }
        }

        // Phase 2: draw the new wave (progress 0→1) — starts when phase 1 ends
        retractAnim.addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                // Swap in the new path
                measuredPath = targetPath
                pathMeasure = PathMeasure(measuredPath, false)
                totalPathLength = pathMeasure.length
                segmentPath = Path()

                val drawAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 800L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        pathProgress = it.animatedValue as Float
                        invalidate()
                    }
                }
                drawAnim.start()
                activeAnimators += drawAnim
            }
        })

        retractAnim.start()
        activeAnimators += retractAnim
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
    }

    // ── Private extension helpers ─────────────────────────────────────────────

    private fun Float.dpToPx(): Float =
        this * resources.displayMetrics.density

    private fun Float.spToPx(): Float =
        this * resources.displayMetrics.scaledDensity
}
