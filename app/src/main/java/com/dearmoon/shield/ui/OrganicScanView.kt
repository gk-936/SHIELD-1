package com.dearmoon.shield.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * OrganicScanView — A highly polished, organic, luminescent ripple scanner
 * based on fluid UI principles (soft blurs, glowing cores).
 */
class OrganicScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val CORE_COLOR   = Color.parseColor("#E0F7FA")      // bright almost white cyan
    private val RIPPLE_COLOR = Color.parseColor("#00C8FF")    // luminous cyan
    
    // Smooth scanning cycle
    private val PING_DURATION_MS = 3800L
    private val RIPPLE_COUNT = 3

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animationProgress = 0f
    private var scanAnimator: ValueAnimator? = null

    // Avoid object creation in onDraw
    private var lastWidth = 0f
    
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startScan() {
        scanAnimator?.cancel()
        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PING_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                animationProgress = anim.animatedFraction
                invalidate()
            }
            start()
        }
    }

    fun stopScan() {
        scanAnimator?.cancel()
        scanAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cw = width.toFloat()
        val ch = height.toFloat()
        val cx = cw / 2f
        val cy = ch / 2f
        
        if (cw != lastWidth) {
            lastWidth = cw
            // Core shader
            val coreMaxRadius = cw * 0.15f
            corePaint.shader = RadialGradient(
                cx, cy, coreMaxRadius * 1.5f,
                intArrayOf(CORE_COLOR, RIPPLE_COLOR, Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        
        // Draw expanding soft ripples
        val coreMaxRadius = cw * 0.15f
        val maxRippleRadius = cw * 0.5f
        
        for (i in 0 until RIPPLE_COUNT) {
            // Stagger the ripples evenly
            val offset = (animationProgress + (1f / RIPPLE_COUNT) * i) % 1f
            
            val r = coreMaxRadius + (maxRippleRadius - coreMaxRadius) * offset
            
            // Thin out as it expands
            val strokeW = (cw * 0.06f) * (1f - offset)
            val alpha = (255 * (1f - offset)).toInt()
            
            basePaint.strokeWidth = strokeW
            basePaint.color = RIPPLE_COLOR
            basePaint.alpha = alpha
            
            // We use overlapping strokes/blurs for an organic aura
            val blurRadius = cw * 0.05f * offset
            if (blurRadius > 1f) {
                basePaint.setShadowLayer(blurRadius, 0f, 0f, RIPPLE_COLOR)
            } else {
                basePaint.clearShadowLayer()
            }
            
            canvas.drawCircle(cx, cy, r, basePaint)
        }

        // Draw soft glowing core breathing
        val corePulse = (Math.sin(animationProgress * Math.PI * 4.0).toFloat() + 1f) / 2f
        val currentCoreRadius = coreMaxRadius * (0.85f + 0.15f * corePulse)
        canvas.drawCircle(cx, cy, currentCoreRadius * 1.4f, corePaint)
    }
}
