package com.dearmoon.shield.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow
import android.widget.TextView
import com.dearmoon.shield.R
import kotlin.math.cos
import kotlin.math.sin

class SecurityGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var totalFiles: Int = 0
    private var attackedFiles: Int = 0
    private var safeFiles: Int = 0
    private var attackPercent: Int = 0

    private val gaugeRadius = 120f.dpToPx()
    private var cx: Float = 0f
    private var cy: Float = 0f

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val largeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f.spToPx()
        letterSpacing = 0.05f
    }

    private val secondaryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9f.spToPx()
        color = Color.parseColor("#8892A4")
    }

    private var pulseValue = 0f
    private var pulseAnimator: ValueAnimator? = null

    private var displayValueHolder = 0
    private var countUpAnimator: ValueAnimator? = null

    private var lastAnimatedTick = 39
    private val handler = Handler(Looper.getMainLooper())

    private var isPopupShowing = false
    private var popupWindow: PopupWindow? = null

    init {
        setOnClickListener {
            showPopup()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h * 0.70f
    }

    fun updateGauge(attacked: Int, total: Int) {
        val wasAttacked = this.attackedFiles > 0
        val isAttacked = attacked > 0

        val oldDisplayVal = if (wasAttacked) this.attackPercent else this.totalFiles

        this.attackedFiles = attacked
        this.totalFiles = total
        this.safeFiles = (totalFiles - attacked).coerceAtLeast(0)
        this.attackPercent = if (totalFiles > 0) (attacked * 100f / totalFiles).toInt() else 0

        val newDisplayVal = if (isAttacked) this.attackPercent else this.totalFiles

        if (oldDisplayVal != newDisplayVal) {
            animateCountUp(oldDisplayVal, newDisplayVal)
        } else {
            displayValueHolder = newDisplayVal
        }

        if (wasAttacked != isAttacked) {
            updatePulseState(isAttacked)
            animateTickTransition()
        } else {
            invalidate()
        }
    }

    private fun updatePulseState(isAttacked: Boolean) {
        if (isAttacked) {
            if (pulseAnimator == null) {
                pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1500
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener {
                        pulseValue = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulseValue = 0f
        }
    }

    private fun animateCountUp(oldVal: Int, newVal: Int) {
        countUpAnimator?.cancel()
        countUpAnimator = ValueAnimator.ofInt(oldVal, newVal).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                displayValueHolder = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun animateTickTransition() {
        lastAnimatedTick = -1
        var delay = 0L
        for (i in 0..39) {
            handler.postDelayed({
                lastAnimatedTick = i
                invalidate()
            }, delay)
            delay += 18L
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val greenTicks = ((safeFiles.toFloat() / totalFiles.coerceAtLeast(1).toFloat()) * 39f).toInt()
        
        for (i in 0..39) {
            val angle = Math.toRadians(180.0 + (i / 39f) * 180f)
            val x = cx + gaugeRadius * cos(angle)
            val y = cy + gaugeRadius * sin(angle)

            canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            canvas.rotate(180f + (i / 39f) * 180f + 90f)

            val tickW = 4.5f.dpToPx()
            val tickH = 18f.dpToPx()

            tickPaint.color = getTickColor(i, greenTicks)

            canvas.drawRoundRect(
                -tickW / 2, -tickH / 2, tickW / 2, tickH / 2,
                tickW / 2f, tickW / 2f,
                tickPaint
            )
            canvas.restore()
        }

        // Draw Center Display
        if (attackedFiles > 0) {
            largeTextPaint.color = Color.parseColor("#FF3333")
            largeTextPaint.textSize = 46f.spToPx()
            canvas.drawText("${displayValueHolder}%", cx, cy - 14f.dpToPx(), largeTextPaint)

            subLabelPaint.color = Color.parseColor("#FF3333")
            canvas.drawText("UNDER ATTACK", cx, cy + 12f.dpToPx(), subLabelPaint)

            canvas.drawText("${attackedFiles} of ${totalFiles} files", cx, cy + 28f.dpToPx(), secondaryLinePaint)
        } else {
            largeTextPaint.color = Color.WHITE
            largeTextPaint.textSize = 46f.spToPx()
            val textNum = if (displayValueHolder > 9999) String.format("%.1fK", displayValueHolder / 1000f) else displayValueHolder.toString()
            canvas.drawText(textNum, cx, cy - 14f.dpToPx(), largeTextPaint)

            subLabelPaint.color = Color.parseColor("#8892A4")
            canvas.drawText("FILES SAFE", cx, cy + 12f.dpToPx(), subLabelPaint)
        }
    }

    private fun getTickColor(index: Int, greenTicks: Int): Int {
        if (index > lastAnimatedTick && lastAnimatedTick < 39) {
            return Color.parseColor("#1E3A4A")
        }
        
        return when {
            index <= greenTicks -> getSafeTickColor(index, 40)
            index <= 39 && attackedFiles > 0 -> {
                val redBase = 255
                val pulseAlpha = (150 + pulseValue * 105).toInt()
                Color.argb(pulseAlpha, redBase, 51, 51)
            }
            else -> Color.parseColor("#1E3A4A")
        }
    }

    private fun getSafeTickColor(index: Int, totalTicks: Int): Int {
        val fraction = index.toFloat() / (totalTicks - 1).coerceAtLeast(1).toFloat()
        val startColor = Color.parseColor("#13B8A6") // Teal
        val endColor = Color.parseColor("#2096F3") // Blue
        
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)
        
        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)
        
        return Color.argb(
            (startA + (endA - startA) * fraction).toInt(),
            (startR + (endR - startR) * fraction).toInt(),
            (startG + (endG - startG) * fraction).toInt(),
            (startB + (endB - startB) * fraction).toInt()
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        countUpAnimator?.cancel()
    }

    private fun Float.dpToPx(): Float = this * resources.displayMetrics.density
    private fun Float.spToPx(): Float = this * resources.displayMetrics.scaledDensity

    private fun showPopup() {
        if (isPopupShowing) return
        isPopupShowing = true
        
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_gauge_stats, null)
        
        val tvAttacks = popupView.findViewById<TextView>(R.id.tvPopupAttacks)
        val tvTotal = popupView.findViewById<TextView>(R.id.tvPopupTotal)
        val vUnderlineLeft = popupView.findViewById<View>(R.id.vUnderlineLeft)
        val vUnderlineRight = popupView.findViewById<View>(R.id.vUnderlineRight)

        tvAttacks.text = attackedFiles.toString()
        tvTotal.text = totalFiles.toString()

        // Setup Underline Left (#00BFFF)
        val leftUnderlineColor = Color.parseColor("#00BFFF")
        vUnderlineLeft.setLayerType(LAYER_TYPE_SOFTWARE, null)
        vUnderlineLeft.background = GlowDrawable(leftUnderlineColor)

        // Setup Underline Right (#00E676)
        val rightUnderlineColor = Color.parseColor("#00E676")
        vUnderlineRight.setLayerType(LAYER_TYPE_SOFTWARE, null)
        vUnderlineRight.background = GlowDrawable(rightUnderlineColor)

        popupWindow = PopupWindow(
            popupView,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(null) // Allows custom bg with corners to work without PopupWindow's default outline
            setOnDismissListener {
                isPopupShowing = false
            }
        }

        // Apply a small custom offset to cover the gauge perfectly
        // Usually, showAsDropDown would place it below. Let's use showAsDropDown with yOffset.
        val measureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        popupView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), measureSpec)
        val popupHeight = popupView.measuredHeight
        
        // Show over the gauge center roughly
        val yOffset = -(height / 2 + popupHeight / 2)
        
        popupView.alpha = 0f
        popupView.scaleY = 0.8f
        
        popupWindow?.showAsDropDown(this, 0, yOffset)
        
        val alphaAnim = ObjectAnimator.ofFloat(popupView, "alpha", 0f, 1f)
        val scaleAnim = ObjectAnimator.ofFloat(popupView, "scaleY", 0.8f, 1.0f)
        
        AnimatorSet().apply {
            playTogether(alphaAnim, scaleAnim)
            duration = 200
            interpolator = OvershootInterpolator(1.5f)
            start()
        }
    }

    // Custom Drawable to handle BlurMaskFilter for the underlines
    private inner class GlowDrawable(private val glowColor: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            alpha = (255 * 0.60).toInt() // 60% alpha
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        
        override fun draw(canvas: Canvas) {
            val bounds = bounds
            canvas.drawRect(bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
