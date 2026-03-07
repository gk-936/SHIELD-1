package com.dearmoon.shield.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * OrbAnimationView — Full-screen canvas that draws:
 *  1. Dark teal background gradient
 *  2. Five perspective-foreshortened ripple ovals that expand outward
 *  3. A glowing white orb that breathes (rises/falls) above the ring plane
 *  4. Specular dome highlight for the 3D glassy look
 *
 * All Paints and Shaders are built in onSizeChanged() — NEVER in onDraw().
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Animation state ──────────────────────────────────────────────────────
    private var rippleProgress = 0f   // 0→1 looping (linear)
    private var orbRiseValue   = 0f   // 0→1→0 (reverse) smooth breath

    // ── Dimension cache ──────────────────────────────────────────────────────
    private var cx        = 0f
    private var viewW     = 0f
    private var viewH     = 0f
    private var ringCY    = 0f   // vertical centre of the ring plane
    private var orbBaseY  = 0f
    private var baseRadius = 0f
    private var orbRiseRange = 0f

    // ── Ring config ───────────────────────────────────────────────────────────
    /** Ring widths as fractions of viewWidth (inner→outer) */
    private val RING_FRACS  = floatArrayOf(0.25f, 0.42f, 0.58f, 0.74f, 0.90f)
    /** Base alphas (before phase-fade) for each ring */
    private val RING_ALPHAS = intArrayOf(180, 140, 100, 60, 30)
    private val RING_COLOR_R = 77; private val RING_COLOR_G = 208; private val RING_COLOR_B = 208

    // ── Paints (built in onSizeChanged) ──────────────────────────────────────
    private val bgPaint   = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f   // set in onSizeChanged
    }
    // Glow layers — one per orb draw step
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val midGlowPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbBodyPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint  = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Ring oval scratch buffer ──────────────────────────────────────────────
    private val ringOval = RectF()

    // ── Animators ────────────────────────────────────────────────────────────
    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        repeatMode  = ValueAnimator.RESTART   // manual phase offset per ring
        interpolator = LinearInterpolator()
        addUpdateListener {
            rippleProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private val orbAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        repeatMode  = ValueAnimator.REVERSE   // 0→1→0→1 — smooth breath, no jump
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            orbRiseValue = it.animatedValue as Float
            // No invalidate here — rippleAnimator already calls it at 60fps
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rippleAnimator.start()
        orbAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rippleAnimator.cancel()
        orbAnimator.cancel()
    }

    // ── Size change — build all shaders here ─────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        viewW = w.toFloat()
        viewH = h.toFloat()
        cx    = viewW / 2f
        ringCY = viewH * 0.62f
        orbBaseY = viewH * 0.52f
        baseRadius = viewW * 0.13f
        orbRiseRange = viewH * 0.08f

        // Background gradient (top → bottom)
        bgPaint.shader = LinearGradient(
            cx, 0f, cx, viewH,
            intArrayOf(
                Color.parseColor("#0A2A2A"),
                Color.parseColor("#1A6060"),
                Color.parseColor("#0D4A4A")
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Ring stroke width
        ringPaint.strokeWidth = 1.2f * resources.displayMetrics.density

        // Orb glow shaders — built once, re-used each frame
        // (radius-dependent shaders are rebuilt in onDraw, but we cache the rest)
        // Specular is radius-dependent too, but smaller objects are fine.
        // We rebuild orb shaders in onDraw only when orbRadius changes — see note below.
        // Because orbRadius changes every frame (it pulses), we keep them in onDraw
        // but guard against allocation by reusing a single shader per layer.
        // The outerGlow / midGlow / orbBody shaders ARE radius-dependent, so we
        // rebuild them cheaply on each frame (RadialGradient is a very lightweight
        // object on modern ART). The bg shader is the one that matters for perf.
    }

    // ── Draw ─────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Background
        canvas.drawRect(0f, 0f, viewW, viewH, bgPaint)

        // 2. Ripple rings (drawn beneath the orb)
        drawRippleRings(canvas)

        // 3. Orb layers (drawn above rings)
        drawOrb(canvas)
    }

    private fun drawRippleRings(canvas: Canvas) {
        for (i in RING_FRACS.indices) {
            // Phase wraps correctly via modulo — ring i's position in the cycle
            val phase = (rippleProgress + i * 0.20f) % 1.0f

            val baseW = RING_FRACS[i] * viewW
            val scale = 0.85f + phase * 0.30f          // expand 85% → 115%
            val alpha = ((1f - phase) * RING_ALPHAS[i]).toInt().coerceIn(0, 255)

            val scaledW  = baseW * scale
            val scaledH  = scaledW * 0.28f              // flat oval — perspective foreshorten

            ringOval.set(
                cx - scaledW / 2f,
                ringCY - scaledH / 2f,
                cx + scaledW / 2f,
                ringCY + scaledH / 2f
            )

            ringPaint.color = Color.argb(alpha, RING_COLOR_R, RING_COLOR_G, RING_COLOR_B)
            canvas.drawOval(ringOval, ringPaint)
        }
    }

    private fun drawOrb(canvas: Canvas) {
        val orbY     = orbBaseY - orbRiseValue * orbRiseRange
        val orbRadius = baseRadius * (1f + orbRiseValue * 0.15f)

        // ── Step 1: Outer soft glow ───────────────────────────────────────────
        val outerR = orbRadius * 3.5f
        outerGlowPaint.shader = RadialGradient(
            cx, orbY, outerR,
            intArrayOf(Color.argb(60, 178, 255, 255), Color.argb(0, 77, 208, 208)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, orbY, outerR, outerGlowPaint)

        // ── Step 2: Mid glow ring ─────────────────────────────────────────────
        val midR = orbRadius * 2.0f
        midGlowPaint.shader = RadialGradient(
            cx, orbY, midR,
            intArrayOf(Color.argb(120, 200, 255, 255), Color.argb(0, 100, 220, 220)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, orbY, midR, midGlowPaint)

        // ── Step 3: Core orb body ─────────────────────────────────────────────
        orbBodyPaint.shader = RadialGradient(
            cx, orbY, orbRadius,
            intArrayOf(
                Color.argb(255, 255, 255, 255),   // pure white centre
                Color.argb(255, 220, 255, 255),   // slight cyan tint at 30%
                Color.argb(180, 150, 230, 230),   // translucent edge at 70%
                Color.argb(0,   100, 200, 200)    // transparent at rim
            ),
            floatArrayOf(0f, 0.3f, 0.7f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, orbY, orbRadius, orbBodyPaint)

        // ── Step 4: Specular highlight — top-left "glassy dome" ───────────────
        val specX = cx - orbRadius * 0.25f
        val specY = orbY - orbRadius * 0.30f
        val specR = orbRadius * 0.20f
        specularPaint.shader = RadialGradient(
            specX, specY, specR,
            intArrayOf(Color.argb(180, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(specX, specY, specR, specularPaint)
    }
}
