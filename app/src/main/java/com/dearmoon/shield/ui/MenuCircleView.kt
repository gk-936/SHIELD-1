package com.dearmoon.shield.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.*

/**
 * MenuCircleView
 *
 * A single animated circle button for the FluidMenu.
 * Handles:
 *  - Frosted dark glass background: Color.argb(190, 28, 28, 30)
 *  - Rotating SweepGradient purple glow (built in onSizeChanged, not onDraw)
 *  - Pulsing border ring (scale + alpha loop)
 *  - Icon float+tilt animation (non-hamburger only)
 *  - Hamburger icon: 3 lines with independent scaleX animations
 *  - Press scale feedback (0.95) and long-press tooltip
 *
 * All Paint objects are created in init{} — NEVER in onDraw.
 * SweepGradient is built in onSizeChanged — NEVER in onDraw.
 */
class MenuCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    val isHamburger: Boolean = false,
    val isCenter: Boolean = false,
    val iconResId: Int = 0,
    val label: String = ""
) : View(context, attrs, defStyleAttr) {

    /** Callback wired by FluidMenuView */
    var onClick: (() -> Unit)? = null

    // ── Sizes ─────────────────────────────────────────────────────────────────
    // Hamburger: 56dp (prominent, easy to tap)
    // Center (Incident Report): 56dp (slightly prominent)
    // Regular menu buttons: 52dp
    // 5 regular × 52dp + 1 center × 56dp + 5 gaps × 6dp = 260 + 56 + 30 = 346dp — fits 360dp+ screens
    private val sizePx: Int = when {
        isHamburger || isCenter -> dp(56)
        else                   -> dp(52)
    }

    // ── Paints (created once here, never in onDraw) ───────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(190, 28, 28, 30)
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = Color.argb(20, 255, 255, 255)
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Shader assigned in onSizeChanged
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }

    private val hamburgerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#A78BFA")
        strokeCap = Paint.Cap.ROUND
    }

    // ── Animated values ───────────────────────────────────────────────────────
    private var sweepRotation = 0f
    private var pulseScale    = 1f
    private var pulseAlpha    = 0.5f
    private var iconFloatY    = 0f
    private var iconRotation  = 0f

    // Hamburger line scaleX values
    private var line1ScaleX = 1f
    private var line2ScaleX = 1f
    private var line3ScaleX = 1f

    // ── Animators ─────────────────────────────────────────────────────────────
    private var sweepAnimator:   ValueAnimator? = null
    private var pulseAnimator:   ValueAnimator? = null
    private var floatAnimator:   ValueAnimator? = null
    private var line1Animator:   ValueAnimator? = null
    private var line2Animator:   ValueAnimator? = null
    private var line3Animator:   ValueAnimator? = null

    // ── Tooltip ───────────────────────────────────────────────────────────────
    private var tooltipWindow: PopupWindow? = null
    private val showTooltipRunnable = Runnable { showTooltip() }

    // ── Measurement ──────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    // ── Shader built here, SweepGradient centre = exact cx, cy ───────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val cx = w / 2f
        val cy = h / 2f
        // SweepGradient: purple 20% alpha sweep arc → transparent
        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(0,   168, 85, 247),   // transparent purple
                Color.argb(51,  168, 85, 247),   // #33A855F7  20% alpha
                Color.argb(0,   168, 85, 247)    // back to transparent
            ),
            floatArrayOf(0f, 0.3f, 1f)
        )
    }

    // ── onDraw — no object allocations, no shader builds ─────────────────────

    override fun onDraw(canvas: Canvas) {
        val cx = width  / 2f
        val cy = height / 2f
        val r  = (min(width, height) / 2f) - dp(1).toFloat()

        // 1. Background circle
        canvas.drawCircle(cx, cy, r, bgPaint)

        // 2. Rotating sweep gradient
        canvas.save()
        canvas.rotate(sweepRotation, cx, cy)
        canvas.drawCircle(cx, cy, r, sweepPaint)
        canvas.restore()

        // 3. Pulsing border ring
        pulsePaint.color = Color.argb(
            (pulseAlpha * 255 * 0.2f).toInt(), 168, 85, 247
        )
        canvas.save()
        canvas.scale(pulseScale, pulseScale, cx, cy)
        canvas.drawCircle(cx, cy, r, pulsePaint)
        canvas.restore()

        // 4. Static glass border
        canvas.drawCircle(cx, cy, r, borderPaint)

        // 5a. Hamburger icon
        if (isHamburger) {
            drawHamburger(canvas, cx, cy)
        }
        // 5b. Regular icon with float animation
        else if (iconResId != 0) {
            drawIcon(canvas, cx, cy)
        }
    }

    private fun drawHamburger(canvas: Canvas, cx: Float, cy: Float) {
        val lineW = dp(24).toFloat()
        val lineH = dp(2).toFloat()
        val gap   = dp(5).toFloat()

        val lines = listOf(
            Pair(line1ScaleX, cy - gap - lineH / 2f),
            Pair(line2ScaleX, cy - lineH / 2f),
            Pair(line3ScaleX, cy + gap - lineH / 2f)
        )
        lines.forEach { (scaleX, y) ->
            canvas.save()
            canvas.scale(scaleX, 1f, cx, y + lineH / 2f)
            val rect = RectF(cx - lineW / 2f, y, cx + lineW / 2f, y + lineH)
            canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, hamburgerPaint)
            canvas.restore()
        }
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float) {
        val icon = ContextCompat.getDrawable(context, iconResId) ?: return
        val iconSize = if (isCenter) dp(24) else dp(20)
        val left = (cx - iconSize / 2f).toInt()
        val top  = (cy - iconSize / 2f + iconFloatY).toInt()
        icon.setBounds(left, top, left + iconSize, top + iconSize)
        icon.setTint(Color.parseColor("#A78BFA"))
        canvas.save()
        canvas.rotate(iconRotation, cx, cy)
        icon.draw(canvas)
        canvas.restore()
    }

    // ── Animation lifecycle ───────────────────────────────────────────────────

    fun startIdleAnimations() {
        // Sweep rotation — 3 s for hamburger, 8 s for menu buttons
        val sweepDuration = if (isHamburger) 3000L else 8000L
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration      = sweepDuration
            repeatCount   = ValueAnimator.INFINITE
            repeatMode    = ValueAnimator.RESTART
            interpolator  = LinearInterpolator()
            addUpdateListener { sweepRotation = it.animatedValue as Float; invalidate() }
            start()
        }

        // Pulse border
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration     = 2000L
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                pulseScale = 1f + t * 0.2f
                pulseAlpha = 0.5f + t * 0.3f
                invalidate()
            }
            start()
        }

        if (isHamburger) {
            // Line 1: 1.0 → 0.8 → 1.0
            line1Animator = ValueAnimator.ofFloat(1f, 0.8f, 1f).apply {
                duration    = 2000L; repeatCount = ValueAnimator.INFINITE
                repeatMode  = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { line1ScaleX = it.animatedValue as Float; invalidate() }
                start()
            }
            // Line 2: 1.0 → 1.2 → 1.0
            line2Animator = ValueAnimator.ofFloat(1f, 1.2f, 1f).apply {
                duration    = 2000L; repeatCount = ValueAnimator.INFINITE
                repeatMode  = ValueAnimator.RESTART; startDelay = 200L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { line2ScaleX = it.animatedValue as Float; invalidate() }
                start()
            }
            // Line 3: 1.0 → 0.9 → 1.0
            line3Animator = ValueAnimator.ofFloat(1f, 0.9f, 1f).apply {
                duration    = 2000L; repeatCount = ValueAnimator.INFINITE
                repeatMode  = ValueAnimator.RESTART; startDelay = 400L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { line3ScaleX = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            // Icon float: translateY 0 → -2dp → 0, tilt 0 → ±5° → 0
            val floatDp = dp(2).toFloat()
            floatAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration     = 3000L; repeatCount = ValueAnimator.INFINITE
                repeatMode   = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { v ->
                    val t = v.animatedValue as Float
                    iconFloatY   = -(t * floatDp)
                    iconRotation = sin(t * Math.PI).toFloat() * 5f
                    invalidate()
                }
                start()
            }
        }
    }

    fun stopAnimations() {
        sweepAnimator?.cancel(); sweepAnimator = null
        pulseAnimator?.cancel(); pulseAnimator = null
        floatAnimator?.cancel(); floatAnimator = null
        line1Animator?.cancel(); line1Animator = null
        line2Animator?.cancel(); line2Animator = null
        line3Animator?.cancel(); line3Animator = null
        removeCallbacks(showTooltipRunnable)
        tooltipWindow?.dismiss(); tooltipWindow = null
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); startIdleAnimations() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stopAnimations() }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                postDelayed(showTooltipRunnable, 600L)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(showTooltipRunnable)
                // Only fire click if within bounds
                val inBounds = event.x >= 0 && event.x <= width
                        && event.y >= 0 && event.y <= height
                animate().scaleX(1f).scaleY(1f).setDuration(100)
                    .withEndAction { if (inBounds) onClick?.invoke() }
                    .start()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(showTooltipRunnable)
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
        }
        return true
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    private fun showTooltip() {
        if (label.isEmpty() || !isAttachedToWindow) return
        val tooltipView = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(230, 17, 24, 39))
                setStroke(1, Color.argb(25, 255, 255, 255))
            }
        }
        tooltipView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val yOff = -(height + tooltipView.measuredHeight + dp(8))
        tooltipWindow?.dismiss()
        tooltipWindow = PopupWindow(
            tooltipView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            showAsDropDown(this@MenuCircleView, 0, yOff)
        }
        // Animate tooltip in
        tooltipView.scaleX = 0.8f; tooltipView.scaleY = 0.8f
        tooltipView.alpha  = 0f
        tooltipView.translationY = dp(10).toFloat()
        tooltipView.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(200).start()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
