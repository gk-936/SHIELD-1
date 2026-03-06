package com.dearmoon.shield.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Random

class GuideBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // All paints created here, never in onDraw
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#010201") // The Void
    }
    private val mainGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val accentGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
        color = Color.argb(18, 42, 72, 57)  // subtle emerald rings (#2A4839)
    }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val random = Random(42)  // fixed seed — same grain every draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        // Main Emerald Glow — bottom center
        mainGlowPaint.shader = RadialGradient(
            w * 0.50f,
            h * 0.95f,
            h * 0.80f,
            intArrayOf(
                Color.argb(255, 42, 72, 57),    // Emerald Mist (#2A4839) at core
                Color.argb(180, 32, 58, 45),    // mid emerald
                Color.argb(80, 20, 40, 30),     // dark falloff
                Color.argb(0, 1, 2, 1)          // transparent
            ),
            floatArrayOf(0f, 0.40f, 0.70f, 1.0f),
            Shader.TileMode.CLAMP
        )

        // Secondary accent glow — bottom-right, Mint Luminescence
        accentGlowPaint.shader = RadialGradient(
            w * 0.95f,
            h * 0.85f,
            h * 0.45f,
            intArrayOf(
                Color.argb(130, 82, 183, 136),  // Mint Luminescence (#52B788)
                Color.argb( 50, 42, 72,  57),
                Color.argb(  0,  1,  2,   1)
            ),
            floatArrayOf(0f, 0.5f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Step 1: black-navy base
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Step 2: save layer for ADD blend mode
        val layerCount = canvas.saveLayer(0f, 0f, w, h, null)

        // Step 3: main large purple glow
        canvas.drawRect(0f, 0f, w, h, mainGlowPaint)

        // Step 4: accent glow bottom-right
        canvas.drawRect(0f, 0f, w, h, accentGlowPaint)

        canvas.restoreToCount(layerCount)

        // Step 5: subtle concentric radar rings
        // Centred at same origin as main glow (bottom-center area)
        // 8 rings with increasing radii, very low alpha
        val ringCX = w * 0.50f
        val ringCY = h * 0.95f
        for (i in 1..8) {
            val r = h * 0.12f * i
            canvas.drawCircle(ringCX, ringCY, r, ringPaint)
        }

        // Step 6: film grain noise
        // Draw ~4000 tiny semi-transparent dots randomly scattered
        // Fixed seed ensures same pattern every draw (no flicker)
        random.setSeed(42)
        grainPaint.color = Color.argb(18, 255, 255, 255)
        repeat(4000) {
            val gx = random.nextFloat() * w
            val gy = random.nextFloat() * h
            canvas.drawPoint(gx, gy, grainPaint)
        }
        // Second pass darker grain for depth
        grainPaint.color = Color.argb(10, 0, 0, 0)
        repeat(2000) {
            val gx = random.nextFloat() * w
            val gy = random.nextFloat() * h
            canvas.drawPoint(gx, gy, grainPaint)
        }
    }
}
