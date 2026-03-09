package com.dearmoon.shield.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * GuideBackgroundView — Premium animated dark background for the User Guide.
 *
 * Performance strategy:
 *  1. Pre-render the expensive blurred glows + grain into a single cached Bitmap (once).
 *  2. Each frame: only translate that cached bitmap to a new orbit position.
 *     This uses hardware-accelerated canvas.drawBitmap() — zero software rendering per frame.
 *
 * Animation: The glow cluster slowly orbits clockwise around the screen center (~18s per revolution).
 */
class GuideBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Cached glow bitmap — rendered once in onSizeChanged
    private var glowBitmap: Bitmap? = null
    // Cached grain bitmap
    private var grainBitmap: Bitmap? = null

    // Orbit animation
    private var orbitAngle = Math.PI.toFloat()  // starts LEFT
    private val orbitSpeed = (2f * Math.PI.toFloat()) / 1125f  // ~18s per revolution
    private val orbitRadiusFraction = 0.45f

    private var screenW = 0f
    private var screenH = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        screenW = w.toFloat()
        screenH = h.toFloat()

        // Pre-render the glow cluster into an off-screen bitmap (SOFTWARE layer, done ONCE)
        buildGlowBitmap(w, h)
        buildGrainBitmap(w, h)
    }

    private fun buildGlowBitmap(w: Int, h: Int) {
        // The glow bitmap is rendered larger than the screen to allow orbit movement
        // without clipping. Size = screen * 2 centered at (w, h).
        val bw = w * 2
        val bh = h * 2
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)

        val blurRadius = w * 0.55f

        // All paints with BlurMaskFilter — only used here, not per-frame
        val glow1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                -w * 0.3f, h * (-0.50f), w * 0.3f, h * (-0.30f),
                intArrayOf(Color.parseColor("#B3DC143C"), Color.parseColor("#808B0000"), Color.parseColor("#00000000")),
                floatArrayOf(0f, 0.4f, 1.0f), Shader.TileMode.CLAMP
            )
        }
        val glow2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                -w * 0.25f, h * (-0.25f), w * 0.35f, h * (-0.10f),
                intArrayOf(Color.parseColor("#B3BF00FF"), Color.parseColor("#804B0082"), Color.parseColor("#00000000")),
                floatArrayOf(0f, 0.4f, 1.0f), Shader.TileMode.CLAMP
            )
        }
        val glow3Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                -w * 0.2f, h * (-0.05f), w * 0.4f, h * 0.08f,
                intArrayOf(Color.parseColor("#B3007FFF"), Color.parseColor("#80000080"), Color.parseColor("#00000000")),
                floatArrayOf(0f, 0.4f, 1.0f), Shader.TileMode.CLAMP
            )
        }
        val glow4Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(blurRadius * 0.9f, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                -w * 0.3f, h * 0.15f, w * 0.3f, h * 0.28f,
                intArrayOf(Color.parseColor("#AAFF4500"), Color.parseColor("#66800000"), Color.parseColor("#00000000")),
                floatArrayOf(0f, 0.4f, 1.0f), Shader.TileMode.CLAMP
            )
        }

        // Draw the 4 glows centered in the bitmap at (bw/2, bh/2)
        c.translate(bw / 2f, bh / 2f)

        c.save(); c.rotate(-45f)
        c.drawRect(-w * 0.8f, -h * 0.55f, w * 0.5f, h * 0.0f, glow1Paint)
        c.restore()

        c.save(); c.rotate(-42f)
        c.drawRect(-w * 0.7f, -h * 0.35f, w * 0.5f, h * 0.15f, glow2Paint)
        c.restore()

        c.save(); c.rotate(-48f)
        c.drawRect(-w * 0.6f, -h * 0.15f, w * 0.5f, h * 0.30f, glow3Paint)
        c.restore()

        c.save(); c.rotate(-40f)
        c.drawRect(-w * 0.7f, h * 0.05f, w * 0.45f, h * 0.55f, glow4Paint)
        c.restore()

        glowBitmap = bitmap
    }

    private fun buildGrainBitmap(w: Int, h: Int) {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val random = Random(42)
        val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        grainPaint.color = Color.argb(14, 255, 255, 255)
        repeat(3000) {
            c.drawPoint(random.nextFloat() * w, random.nextFloat() * h, grainPaint)
        }
        grainPaint.color = Color.argb(8, 0, 0, 0)
        repeat(1500) {
            c.drawPoint(random.nextFloat() * w, random.nextFloat() * h, grainPaint)
        }

        grainBitmap = bitmap
    }

    override fun onDraw(canvas: Canvas) {
        val w = screenW
        val h = screenH
        val glow = glowBitmap ?: return
        val grain = grainBitmap

        // Advance orbit
        orbitAngle += orbitSpeed
        if (orbitAngle > 2 * Math.PI) orbitAngle -= (2 * Math.PI).toFloat()

        val orbitRadius = w * orbitRadiusFraction
        val clusterX = w / 2f + orbitRadius * cos(orbitAngle)
        val clusterY = h / 2f + orbitRadius * sin(orbitAngle)

        // Step 1: black base
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // Step 2: draw pre-rendered glow bitmap at orbit position
        // The glow bitmap center is at (bw/2, bh/2), we want it at (clusterX, clusterY)
        val bitmapLeft = clusterX - glow.width / 2f
        val bitmapTop = clusterY - glow.height / 2f
        canvas.drawBitmap(glow, bitmapLeft, bitmapTop, bitmapPaint)

        // Step 3: grain overlay (also pre-rendered)
        if (grain != null) {
            canvas.drawBitmap(grain, 0f, 0f, bitmapPaint)
        }

        // Schedule next frame
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowBitmap?.recycle()
        glowBitmap = null
        grainBitmap?.recycle()
        grainBitmap = null
    }
}
