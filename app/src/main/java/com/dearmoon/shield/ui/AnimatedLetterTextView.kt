package com.dearmoon.shield.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.dearmoon.shield.R

/**
 * AnimatedLetterTextView
 *
 * A custom View that animates each character of a string individually:
 *  - Letters drop in from below with a spring-style bounce (overshoot + settle).
 *  - Letters fade in simultaneously with the drop.
 *  - After all letters finish, a gradient underline sweeps outward from centre.
 *
 * All animation uses Android's built-in ValueAnimator — zero third-party libs.
 * Paint is created once in init{}, never inside onDraw().
 * LinearGradient is created / recreated in onSizeChanged(), stored as a field.
 */
class AnimatedLetterTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Attribute-backed fields ───────────────────────────────────────────────

    private var text: String = ""
    private var letterDelay: Long = 100L          // ms between each letter start
    @Suppress("unused")
    private var springDamping: Float = 0.6f       // stored for completeness
    private var textSizePx: Float = 32f.spToPx()
    private var textColor: Int = 0xFFE8EAF0.toInt()
    private var underlineGradientStart: Int = 0xFF00C8FF.toInt()
    private var underlineGradientEnd: Int = 0xFF1A2744.toInt()
    private var underlineHeightPx: Float = 3f.dpToPx()
    private var underlineOffsetPx: Float = 6f.dpToPx()

    // ── Internal state ────────────────────────────────────────────────────────

    private var letters: List<Char> = emptyList()
    private var letterAlphas: FloatArray = FloatArray(0)
    private var letterTranslations: FloatArray = FloatArray(0)  // dp units
    private var underlineProgress: Float = 0f
    private var animationStarted = false

    // ── Paint ─────────────────────────────────────────────────────────────────

    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    private val underlinePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Pre-computed layout values ────────────────────────────────────────────

    /** Width of each character, measured once when text or textSize changes. */
    private var letterWidths: FloatArray = FloatArray(0)
    private var totalTextWidth: Float = 0f

    /** Cached LinearGradient — recreated in onSizeChanged only. */
    private var underlineShader: LinearGradient? = null
    private var cachedViewWidth: Int = 0

    // ── Active animators list (for cancel on detach) ──────────────────────────
    private val activeAnimators = mutableListOf<ValueAnimator>()

    // ── Init block: read XML attributes ──────────────────────────────────────

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.AnimatedLetterTextView)
            try {
                text = ta.getString(R.styleable.AnimatedLetterTextView_alt_text) ?: ""
                letterDelay = ta.getInt(
                    R.styleable.AnimatedLetterTextView_alt_letterDelay, 100
                ).toLong()
                springDamping = ta.getFloat(
                    R.styleable.AnimatedLetterTextView_alt_springDamping, 0.6f
                )
                // alt_textSize is declared as dimension, getDimension returns px already
                textSizePx = if (ta.hasValue(R.styleable.AnimatedLetterTextView_alt_textSize)) {
                    ta.getDimension(R.styleable.AnimatedLetterTextView_alt_textSize, 32f.spToPx())
                } else {
                    32f.spToPx()
                }
                textColor = ta.getColor(
                    R.styleable.AnimatedLetterTextView_alt_textColor, 0xFFE8EAF0.toInt()
                )
                underlineGradientStart = ta.getColor(
                    R.styleable.AnimatedLetterTextView_alt_underlineGradientStart,
                    0xFF00C8FF.toInt()
                )
                underlineGradientEnd = ta.getColor(
                    R.styleable.AnimatedLetterTextView_alt_underlineGradientEnd,
                    0xFF1A2744.toInt()
                )
                underlineHeightPx = if (ta.hasValue(
                        R.styleable.AnimatedLetterTextView_alt_underlineHeight)) {
                    ta.getDimension(
                        R.styleable.AnimatedLetterTextView_alt_underlineHeight, 3f.dpToPx()
                    )
                } else 3f.dpToPx()
                underlineOffsetPx = if (ta.hasValue(
                        R.styleable.AnimatedLetterTextView_alt_underlineOffset)) {
                    ta.getDimension(
                        R.styleable.AnimatedLetterTextView_alt_underlineOffset, 6f.dpToPx()
                    )
                } else 6f.dpToPx()
            } finally {
                ta.recycle()
            }
        }
        applyText(text)
        configurePaint()
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    private fun applyText(s: String) {
        letters = s.toList()
        val n = letters.size
        letterAlphas = FloatArray(n) { 0f }
        letterTranslations = FloatArray(n) { 20f }
        measureLetterWidths()
    }

    private fun configurePaint() {
        textPaint.textSize = textSizePx
        textPaint.color = textColor
        measureLetterWidths()
    }

    private fun measureLetterWidths() {
        if (letters.isEmpty()) {
            letterWidths = FloatArray(0)
            totalTextWidth = 0f
            return
        }
        letterWidths = FloatArray(letters.size) { i ->
            textPaint.measureText(letters[i].toString())
        }
        totalTextWidth = letterWidths.sum()
    }

    // ── onMeasure ─────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Use the actual text width as the desired width so that wrap_content
        // parents (LinearLayout, etc.) measure this view correctly.
        // suggestedMinimumWidth is 0 by default, which causes the view to
        // collapse to 0px and paint nothing.
        val desiredWidth = (totalTextWidth + paddingLeft + paddingRight).toInt()
        val desiredHeight = (textSizePx + underlineOffsetPx + underlineHeightPx +
                paddingTop + paddingBottom).toInt()

        val w = resolveSize(desiredWidth, widthMeasureSpec)
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── onSizeChanged: recreate shader here, NOT in onDraw ────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedViewWidth = w
        rebuildShader(w)
    }

    private fun rebuildShader(viewWidth: Int) {
        if (viewWidth <= 0 || letters.isEmpty()) return
        val centreX = (viewWidth / 2f)
        val halfTotal = totalTextWidth / 2f
        val left = centreX - halfTotal
        val right = centreX + halfTotal
        underlineShader = LinearGradient(
            left, 0f, right, 0f,
            underlineGradientStart, underlineGradientEnd,
            Shader.TileMode.CLAMP
        )
    }

    // ── onDraw ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return

        val viewWidth = width.toFloat()
        val startX = (viewWidth - totalTextWidth) / 2f
        // baseline: text sits in the top portion of the view
        val baseline = paddingTop + textSizePx

        // 1. Draw each letter
        var curX = startX
        for (i in letters.indices) {
            val alpha = (letterAlphas[i] * 255).toInt().coerceIn(0, 255)
            textPaint.alpha = alpha
            val yOffset = letterTranslations[i].dpToPx()
            canvas.drawText(
                letters[i].toString(),
                curX,
                baseline - yOffset,
                textPaint
            )
            curX += letterWidths[i]
        }
        // Reset alpha for re-use (Paint is shared)
        textPaint.alpha = 255

        // 2. Draw gradient underline (only when animating / done)
        if (underlineProgress > 0f && underlineShader != null) {
            val centreX = (viewWidth / 2f)
            val halfWidth = (totalTextWidth / 2f) * underlineProgress
            val left = centreX - halfWidth
            val right = centreX + halfWidth
            val underlineY = baseline + underlineOffsetPx

            // We need a fresh shader each frame because the x-coords change with progress.
            // Build a temporary shader whose endpoints match the current swept width.
            val shader = LinearGradient(
                left, 0f, right, 0f,
                underlineGradientStart, underlineGradientEnd,
                Shader.TileMode.CLAMP
            )
            underlinePaint.shader = shader
            canvas.drawRect(
                left, underlineY,
                right, underlineY + underlineHeightPx,
                underlinePaint
            )
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Kick off the per-letter drop-in animation followed by the underline sweep.
     * Safe to call from SplashActivity.post{} once the view is laid out.
     *
     * @param underlineStartOverride When >= 0, overrides the auto-calculated underline
     *   start time (letters.size * letterDelay + 300ms). Pass the same value to two views
     *   with different letter counts to make their underlines appear simultaneously.
     *   Default -1 uses the auto-calculated value.
     */
    @JvmOverloads
    fun startAnimation(underlineStartOverride: Long = -1L) {
        if (letters.isEmpty()) return
        if (animationStarted) return
        animationStarted = true

        for (i in letters.indices) {
            val startMs = (i * letterDelay)

            // Translation: spring overshoot — 20dp → -4dp (overshoot) → 0dp (settle)
            val translationAnimator = ValueAnimator.ofFloat(20f, -4f, 0f).apply {
                duration = 400L
                startDelay = startMs
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { anim ->
                    letterTranslations[i] = anim.animatedValue as Float
                    invalidate()
                }
            }

            // Alpha: fade in 0 → 1
            val alphaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300L
                startDelay = startMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    letterAlphas[i] = anim.animatedValue as Float
                    invalidate()
                }
            }

            translationAnimator.start()
            alphaAnimator.start()
            activeAnimators += translationAnimator
            activeAnimators += alphaAnimator
        }

        // Underline delay: use override if supplied, otherwise auto-calculate.
        // Auto = last letter start + 300ms buffer after its 400ms translation.
        val underlineDelay = if (underlineStartOverride >= 0L) {
            underlineStartOverride
        } else {
            letters.size * letterDelay + 300L
        }
        val underlineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            startDelay = underlineDelay
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                underlineProgress = anim.animatedValue as Float
                invalidate()
            }
        }
        underlineAnimator.start()
        activeAnimators += underlineAnimator
    }

    /**
     * Resets all animation state so [startAnimation] can be called again.
     */
    fun resetAnimation() {
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
        animationStarted = false
        for (i in letters.indices) {
            letterAlphas[i] = 0f
            letterTranslations[i] = 20f
        }
        underlineProgress = 0f
        invalidate()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
    }

    // ── Extension helpers (private) ───────────────────────────────────────────

    private fun Float.dpToPx(): Float =
        this * resources.displayMetrics.density

    private fun Float.spToPx(): Float =
        this * resources.displayMetrics.scaledDensity
}
