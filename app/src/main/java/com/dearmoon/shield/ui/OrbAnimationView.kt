package com.dearmoon.shield.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * OrbAnimationView — Full-canvas rendering of the "breathing cone" animation.
 *
 * ANIMATION PHASES:
 *  Phase 1 (0 → 2s):  Static cone — 5 perspective-foreshortened ellipses stacked
 *                      from narrow (near orb) to wide (bottom), forming a 3D cone.
 *                      The orb glows and breathes above.
 *                      Rings slowly expand from the orb centre outward (2.4s ripple loop).
 *
 *  Phase 2 (2s → 4s): Compression — rings simultaneously converge from their
 *                      spread-out cone positions downward to the base.
 *                      Each ring's Y and width lerp toward ring[4]'s resting values.
 *
 * VISUAL STYLE (matches reference photo):
 *  - Background: teal gradient (#0D3D3D → #1A7070 → #0D4A4A)
 *  - Rings: near-white translucent ellipses  Color.argb(alpha, 210, 245, 245)
 *  - Stroke: soft, ~2dp  (thinner = more ethereal)
 *  - Orb: bright white dome with cyan glow halo; specular highlight for 3D glassy look
 *
 * All Paints/shaders built in onSizeChanged(), never in onDraw().
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Tuning constants ─────────────────────────────────────────────────────

    /** Number of rings forming the cone */
    private val RING_COUNT = 5

    /** Ripple loop (Phase 1 idle expansion) */
    private val RIPPLE_DURATION_MS = 2400L

    /** Orb breathing cycle */
    private val ORB_BREATH_MS = 3000L

    /** After this delay (ms), Phase 2 compression starts */
    private val COMPRESS_START_DELAY_MS = 2000L

    /** How long Phase 2 compression lasts */
    private val COMPRESS_DURATION_MS = 1600L

    // ── Dimension cache (all set in onSizeChanged) ────────────────────────────
    private var cx = 0f
    private var viewW = 0f
    private var viewH = 0f

    // Orb position
    private var orbCY    = 0f    // rest Y
    private var orbRadiusBase = 0f
    private var orbRiseRange  = 0f

    // Ring resting geometry (cone shape)
    //   ringRestY[i]    = Y centre for ring i at rest (cone spread)
    //   ringRestWidth[i] = half-width at rest
    private val ringRestY     = FloatArray(RING_COUNT)
    private val ringRestWidth  = FloatArray(RING_COUNT)
    // Ring height = width * ASPECT (flat oval, perspective foreshorten)
    private val RING_ASPECT = 0.22f

    // Phase-1 ripple scale/alpha per ring
    // (ring[0] is innermost / top; ring[4] is outermost / bottom)
    private val RING_BASE_ALPHA = intArrayOf(160, 130, 100, 70, 40)

    // ── Animation state ──────────────────────────────────────────────────────
    private var rippleProgress  = 0f   // 0→1 looping
    private var orbBreathValue  = 0f   // 0→1→0 smooth breath
    private var compressProgress = 0f  // 0→1 during Phase 2

    // ── Paints ───────────────────────────────────────────────────────────────
    private val bgPaint       = Paint()
    private val ringPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val midGlowPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbBodyPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringOval = RectF()

    // ── Animators ────────────────────────────────────────────────────────────
    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration     = RIPPLE_DURATION_MS
        repeatCount  = ValueAnimator.INFINITE
        repeatMode   = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            rippleProgress = it.animatedValue as Float
            invalidate()
        }
    }

    private val orbAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration     = ORB_BREATH_MS
        repeatCount  = ValueAnimator.INFINITE
        repeatMode   = ValueAnimator.REVERSE       // REVERSE = smooth 0→1→0, no jump
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            orbBreathValue = it.animatedValue as Float
            // rippleAnimator's listener already calls invalidate() at 60fps
        }
    }

    // Phase 2 compression animator — started from onAttachedToWindow via postDelayed
    private var compressAnimator: ValueAnimator? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rippleAnimator.start()
        orbAnimator.start()

        // After COMPRESS_START_DELAY_MS, begin cone compression
        postDelayed({
            startCompressionPhase()
        }, COMPRESS_START_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        rippleAnimator.cancel()
        orbAnimator.cancel()
        compressAnimator?.cancel()
        removeCallbacks(null)
    }

    // ── onSizeChanged — build all shaders and ring geometry here ─────────────
    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        viewW = w.toFloat()
        viewH = h.toFloat()
        cx    = viewW / 2f

        // Orb sits at ~32% from top of this view
        orbCY        = viewH * 0.32f
        orbRadiusBase = viewW * 0.115f
        orbRiseRange  = viewH * 0.045f

        // Build cone ring resting geometry
        //   Ring 0 (top, innermost, near orb): narrowest, highest Y
        //   Ring 4 (bottom, outermost): widest, lowest Y
        val coneTopY    = orbCY + orbRadiusBase * 0.6f   // just below orb equator
        val coneBottomY = viewH * 0.68f                   // base of cone

        for (i in 0 until RING_COUNT) {
            val frac = i.toFloat() / (RING_COUNT - 1)

            // Y position: linear from coneTop → coneBottom
            ringRestY[i] = coneTopY + frac * (coneBottomY - coneTopY)

            // Width: exponential flare — inner rings very narrow, outer very wide
            // This gives the distinct cone/funnel silhouette
            val minW  = viewW * 0.10f
            val maxW  = viewW * 0.90f
            ringRestWidth[i] = minW + (maxW - minW) * (frac * frac)   // quadratic flare
        }

        // Background gradient: teal (matches reference photo)
        bgPaint.shader = LinearGradient(
            cx, 0f, cx, viewH,
            intArrayOf(
                Color.parseColor("#0D3D3D"),
                Color.parseColor("#1A7070"),
                Color.parseColor("#0D4A4A"),
                Color.parseColor("#082828")
            ),
            floatArrayOf(0f, 0.35f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )

        // Ring stroke width: 1.6dp
        ringPaint.strokeWidth = 1.6f * resources.displayMetrics.density
    }

    // ── Phase 2: Compression ─────────────────────────────────────────────────
    private fun startCompressionPhase() {
        compressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = COMPRESS_DURATION_MS
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener {
                compressProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Hold at compressed state — ripple + orb continue
                }
            })
            start()
        }
    }

    // ── Draw ─────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, viewW, viewH, bgPaint)
        drawConeRings(canvas)
        drawOrb(canvas)
    }

    // ── Cone Rings ────────────────────────────────────────────────────────────
    private fun drawConeRings(canvas: Canvas) {
        // Target state for Phase 2 compression:
        // All rings converge to ring[RING_COUNT-1]'s Y and width
        val targetY = ringRestY[RING_COUNT - 1]
        val targetW = ringRestWidth[RING_COUNT - 1]

        for (i in 0 until RING_COUNT) {
            // Phase-offset ripple: ring 0 pulses first, ring 4 last
            val phase = (rippleProgress + i * 0.18f) % 1.0f
            val rippleScale = 0.88f + phase * 0.25f
            val rippleAlpha = (1f - phase).coerceIn(0f, 1f)

            // Compression: lerp each ring toward the base position
            val cp = compressProgress          // 0f = Phase1, 1.0f = fully compressed
            val currentY = lerp(ringRestY[i],  targetY, cp)
            val currentHalfW = lerp(ringRestWidth[i], targetW, cp) * rippleScale / 2f
            val currentHalfH = (currentHalfW * RING_ASPECT)

            val alpha = (RING_BASE_ALPHA[i] * rippleAlpha * (1f - cp * 0.3f)).toInt()
                .coerceIn(0, 255)

            ringOval.set(
                cx - currentHalfW,
                currentY - currentHalfH,
                cx + currentHalfW,
                currentY + currentHalfH
            )

            ringPaint.color = Color.argb(alpha, 210, 245, 245)
            canvas.drawOval(ringOval, ringPaint)

            // Second inner ring (slightly smaller, more opaque) — the "double edge" look
            val innerAlpha = (alpha * 0.55f).toInt()
            if (innerAlpha > 8) {
                ringOval.set(
                    cx - currentHalfW * 0.82f,
                    currentY - currentHalfH * 0.82f,
                    cx + currentHalfW * 0.82f,
                    currentY + currentHalfH * 0.82f
                )
                ringPaint.color = Color.argb(innerAlpha, 230, 255, 255)
                canvas.drawOval(ringOval, ringPaint)
            }
        }
    }

    // ── Orb ───────────────────────────────────────────────────────────────────
    private fun drawOrb(canvas: Canvas) {
        // Phase 1: orb breathes at orbCY (rises slightly)
        // Phase 2: orb also compresses — moves from orbCY down to ring base Y
        val cp       = compressProgress
        val breathY  = orbCY - orbBreathValue * orbRiseRange * (1f - cp)  // breath stops as compress starts
        val orbY     = lerp(breathY, ringRestY[RING_COUNT - 1], cp)        // drop toward base
        val orbR     = orbRadiusBase * (1f + orbBreathValue * 0.12f * (1f - cp)) * lerp(1f, 0.55f, cp) // shrinks

        // Step 1: Outer soft ambient glow
        outerGlowPaint.shader = RadialGradient(
            cx, orbY, orbR * 3.8f,
            intArrayOf(Color.argb(55, 180, 255, 255), Color.argb(0, 40, 160, 160)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, orbY, orbR * 3.8f, outerGlowPaint)

        // Step 2: Mid glow
        midGlowPaint.shader = RadialGradient(
            cx, orbY, orbR * 2.2f,
            intArrayOf(Color.argb(110, 200, 255, 255), Color.argb(0, 80, 200, 200)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, orbY, orbR * 2.2f, midGlowPaint)

        // Step 3: Core dome body
        orbBodyPaint.shader = RadialGradient(
            cx, orbY, orbR,
            intArrayOf(
                Color.argb(255, 255, 255, 255),   // pure white core
                Color.argb(255, 225, 255, 255),   // near-white with cyan blush
                Color.argb(180, 140, 225, 225),   // translucent cyan edge
                Color.argb(0,   60,  160, 160)    // transparent rim
            ),
            floatArrayOf(0f, 0.28f, 0.65f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, orbY, orbR, orbBodyPaint)

        // Step 4: Specular dome highlight (top-left) — gives 3D glassy look
        val sX = cx - orbR * 0.26f
        val sY = orbY - orbR * 0.28f
        val sR = orbR * 0.22f
        specularPaint.shader = RadialGradient(
            sX, sY, sR,
            intArrayOf(Color.argb(190, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sX, sY, sR, specularPaint)
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
}
