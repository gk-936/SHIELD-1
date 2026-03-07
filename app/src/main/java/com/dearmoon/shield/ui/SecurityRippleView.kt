package com.dearmoon.shield.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * SecurityRippleView — Full-canvas overlay that renders the "Security Ripple"
 * animation for the S.H.I.E.L.D. Root Access and Non-Root Access buttons.
 *
 * Architecture:
 *  - Attach this View as a full-screen overlay above the button layout.
 *  - Call [triggerRipple(cx, cy, isRoot)] on button tap.
 *  - The view manages its own animation loop via ValueAnimator + Handler.
 *
 * Layers rendered per frame (back → front):
 *  1. Ring 4 ghost (lowest alpha)
 *  2. Ring 3 secondary echo
 *  3. Data streams (radial hex scrolling text, in gap between ring 1 & 2)
 *  4. Ring 2 echo
 *  5. Ring 1 primary ping (highest contrast)
 *  6. Button bloom (white-cyan flash at tap origin, fades quickly)
 */
class SecurityRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Color constants ──────────────────────────────────────────────────────

    /** Root Access: Luxury Green */
    private val COLOR_ROOT     = Color.parseColor("#52B788")
    private val COLOR_ROOT_LT  = Color.parseColor("#B7E4C7")

    /** Non-Root Access: Cyan */
    private val COLOR_NONROOT     = Color.parseColor("#00C8FF")
    private val COLOR_NONROOT_LT  = Color.parseColor("#90E0EF")

    // ── Screen blend Xfermode (API < 29 fallback) ────────────────────────────
    private val screenXfer = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    // ── Ring config ──────────────────────────────────────────────────────────

    /** Total rings in one burst */
    private val RING_COUNT = 4

    /** Stagger between ring spawns (ms) */
    private val RING_STAGGER_MS = 120L

    /** Ring expansion duration (ms) */
    private val EXPAND_MS = 750L

    /** Starting radius (dp) — just past button edge */
    private val R0_DP = 28f

    /** Max expansion radius (dp) */
    private val R_MAX_DP = 220f

    /** Fixed glyph slot pitch (dp) */
    private val SLOT_PITCH_DP = 12f

    // ── Idle heartbeat config ────────────────────────────────────────────────
    private val HEARTBEAT_PERIOD_MS = 3500L
    private val HEARTBEAT_R_MAX_DP = 52f
    private val HEARTBEAT_ALPHA_PEAK = 0.18f
    private val HEARTBEAT_DURATION_MS = 1800L

    // ── Glyph families ───────────────────────────────────────────────────────
    private val HEX_CHARS  = arrayOf("3A","F0","7C","1E","B2","9D","04","E7","AC","5F","8B","D1")
    private val BIN_CHARS  = arrayOf("01","10","11","00","01","10","11","00")
    private val NODE_CHARS = arrayOf("◆","⬡","✦","◈","⬢","◉","▸","⬟")

    // ── Data stream config ───────────────────────────────────────────────────
    private val STREAM_COUNT_ROOT    = 12
    private val STREAM_COUNT_NONROOT = 6
    private val STREAM_SCROLL_DP_SEC = 280f  // scroll speed dp/sec

    // ── State ────────────────────────────────────────────────────────────────
    private var centerX = 0f
    private var centerY = 0f
    private var isRoot  = false
    private var isBurstActive = false

    private val rings = ArrayList<RingState>(RING_COUNT)
    private val streams = ArrayList<StreamState>()
    private val handler = Handler(Looper.getMainLooper())

    // Idle heartbeat animator
    private var heartbeatAnimator: ValueAnimator? = null

    // Idle heartbeat state
    private var heartbeatRadius   = 0f
    private var heartbeatAlpha    = 0f
    private var heartbeatCX       = 0f
    private var heartbeatCY       = 0f
    private var heartbeatColor    = COLOR_NONROOT
    private var idleMode          = false

    // ── Paints ───────────────────────────────────────────────────────────────
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        xfermode = screenXfer
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        xfermode = screenXfer
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = screenXfer
    }
    private val streamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        xfermode = screenXfer
    }

    // ── dp helper ────────────────────────────────────────────────────────────
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ── Ring state ───────────────────────────────────────────────────────────
    inner class RingState(
        val index:         Int,          // 0 = primary, 3 = ghost
        val spawnTime:     Long,         // System.currentTimeMillis() when spawned
        val primaryColor:  Int,
        val glyphColor:    Int,
        val strokeDp:      Float,
        val peakAlpha:     Float,
        val glyphAlpha:    Float,
        val isElliptical:  Boolean       // true for root (85% h-ratio)
    ) {
        val glyphs = ArrayList<GlyphState>()
        var alive = true
    }

    inner class GlyphState(
        val family:     Int,            // 0=HEX, 1=BIN, 2=NODE
        val angleRad:   Float,          // fixed angle on ring
        var chars:      Array<String>,  // mutable for scramble
        var charIdx:    Int = 0,
        var fadeStart:  Long = 0L,
        var scrambles:  Int = 3,
        var lastScramble: Long = 0L
    )

    inner class StreamState(
        val angleRad:  Float,
        val spawnTime: Long,
        var scrollOffset: Float = 0f    // in dp, updated each frame
    )

    // ── Idle heartbeat ───────────────────────────────────────────────────────

    /**
     * Start the idle ambient heartbeat on a given button's centre.
     * Call this once after the view is laid out.
     */
    fun startIdleHeartbeat(cx: Float, cy: Float, rootMode: Boolean) {
        heartbeatCX    = cx
        heartbeatCY    = cy
        heartbeatColor = if (rootMode) COLOR_ROOT else COLOR_NONROOT
        idleMode       = true
        scheduleNextHeartbeat()
    }

    fun stopIdleHeartbeat() {
        idleMode = false
        heartbeatAnimator?.cancel()
        heartbeatAnimator = null
    }

    private fun scheduleNextHeartbeat() {
        if (!idleMode) return
        heartbeatAnimator?.cancel()
        heartbeatAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HEARTBEAT_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                heartbeatRadius = dp(HEARTBEAT_R_MAX_DP) * t
                heartbeatAlpha  = HEARTBEAT_ALPHA_PEAK * (1f - t)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    heartbeatAlpha = 0f
                    if (idleMode) {
                        handler.postDelayed({ scheduleNextHeartbeat() },
                            HEARTBEAT_PERIOD_MS - HEARTBEAT_DURATION_MS)
                    }
                }
            })
            start()
        }
    }

    // ── Main burst trigger ───────────────────────────────────────────────────

    /**
     * Call this on button tap.
     * @param cx/cy  centre of the tapped button in this View's coordinate space
     * @param rootMode  true = Root Access colours, false = Non-Root
     */
    fun triggerRipple(cx: Float, cy: Float, rootMode: Boolean) {
        // Cancel idle heartbeat during burst
        stopIdleHeartbeat()

        centerX = cx
        centerY = cy
        isRoot  = rootMode
        isBurstActive = true

        rings.clear()
        streams.clear()

        val primaryColor = if (rootMode) COLOR_ROOT    else COLOR_NONROOT
        val glyphColor   = if (rootMode) COLOR_ROOT_LT else COLOR_NONROOT_LT

        val ringConfigs = arrayOf(
            floatArrayOf(2.5f, 1.00f, 0.90f),  // primary
            floatArrayOf(1.8f, 0.72f, 0.65f),  // echo 1
            floatArrayOf(1.2f, 0.48f, 0.40f),  // echo 2
            floatArrayOf(0.8f, 0.22f, 0.18f)   // ghost
        )

        val now = System.currentTimeMillis()

        for (i in 0 until RING_COUNT) {
            handler.postDelayed({
                if (!isBurstActive) return@postDelayed
                val ring = RingState(
                    index        = i,
                    spawnTime    = System.currentTimeMillis(),
                    primaryColor = primaryColor,
                    glyphColor   = glyphColor,
                    strokeDp     = ringConfigs[i][0],
                    peakAlpha    = ringConfigs[i][1],
                    glyphAlpha   = ringConfigs[i][2],
                    isElliptical = rootMode
                )
                populateGlyphs(ring)
                rings.add(ring)
                if (i == 0) spawnStreams(rootMode)
                invalidate()
            }, i * RING_STAGGER_MS)
        }

        // Main render loop
        startRenderLoop(now)
    }

    // ── Glyph population ─────────────────────────────────────────────────────

    private fun populateGlyphs(ring: RingState) {
        // Use a representative radius ≈ R0 for initial slot count; glyphs scale with ring
        val approxR  = dp(R0_DP * 2f)
        val circumf  = 2f * Math.PI.toFloat() * approxR
        val n        = floor((circumf / dp(SLOT_PITCH_DP))).toInt().coerceAtLeast(12)
        val rng      = Random(ring.index.toLong() + System.nanoTime())

        for (s in 0 until n) {
            val family  = s % 3
            val angle   = (2f * Math.PI.toFloat() * s / n)
            val chars   = when (family) {
                0 -> HEX_CHARS
                1 -> BIN_CHARS
                else -> NODE_CHARS
            }
            // Randomised fade start — disintegration stagger
            val fadeStart = (ring.spawnTime + (EXPAND_MS * 0.35f).toLong()
                    + rng.nextLong(0L, 200L))
            ring.glyphs.add(GlyphState(
                family    = family,
                angleRad  = angle,
                chars     = chars,
                charIdx   = rng.nextInt(chars.size),
                fadeStart = fadeStart
            ))
        }
    }

    // ── Stream spawning ──────────────────────────────────────────────────────

    private fun spawnStreams(rootMode: Boolean) {
        val count = if (rootMode) STREAM_COUNT_ROOT else STREAM_COUNT_NONROOT
        val now   = System.currentTimeMillis()
        for (i in 0 until count) {
            val angle = (2f * Math.PI.toFloat() * i / count)
            streams.add(StreamState(angleRad = angle, spawnTime = now))
        }
    }

    // ── Render loop ──────────────────────────────────────────────────────────

    private var renderAnimator: ValueAnimator? = null

    private fun startRenderLoop(burstStart: Long) {
        renderAnimator?.cancel()
        renderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXPAND_MS + RING_STAGGER_MS * RING_COUNT + 500L   // full lifetime
            repeatCount = 0
            addUpdateListener {
                updateStreams()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Burst done — resume idle heartbeat
                    rings.clear()
                    streams.clear()
                    isBurstActive = false
                    invalidate()
                    val cx = centerX
                    val cy = centerY
                    val rm = isRoot
                    handler.postDelayed({
                        if (!isBurstActive) startIdleHeartbeat(cx, cy, rm)
                    }, 400L)
                }
            })
            start()
        }
    }

    private fun updateStreams() {
        val now = System.currentTimeMillis()
        val dt  = 16f / 1000f  // ≈ 60fps frame delta in seconds
        for (s in streams) {
            s.scrollOffset += dp(STREAM_SCROLL_DP_SEC) * dt
        }
    }

    // ── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw idle heartbeat first (bottom layer)
        if (!isBurstActive && heartbeatAlpha > 0f) {
            drawHeartbeat(canvas)
        }

        if (!isBurstActive && rings.isEmpty()) return

        val now = System.currentTimeMillis()

        // Draw back→front: ghost(3)→echo2(2)→streams→echo1(1)→primary(0)→bloom
        val sortedRings = rings.sortedByDescending { it.index }
        for ((drawPass, ring) in sortedRings.withIndex()) {
            // Insert streams between ring-index 2 and ring-index 1
            if (ring.index == 1 && streams.isNotEmpty()) {
                drawDataStreams(canvas, now)
            }
            drawRing(canvas, ring, now)
        }

        // Bloom layer: central flash, only first 300ms of burst
        val timeSinceFirst = now - (rings.minOfOrNull { it.spawnTime } ?: now)
        if (timeSinceFirst < 300L) {
            drawBloom(canvas, timeSinceFirst)
        }
    }

    // ── Heartbeat ────────────────────────────────────────────────────────────

    private fun drawHeartbeat(canvas: Canvas) {
        ringPaint.color = heartbeatColor
        ringPaint.alpha = (heartbeatAlpha * 255).toInt()
        ringPaint.strokeWidth = dp(1f)
        canvas.drawCircle(heartbeatCX, heartbeatCY, heartbeatRadius, ringPaint)
    }

    // ── Ring ─────────────────────────────────────────────────────────────────

    private fun drawRing(canvas: Canvas, ring: RingState, now: Long) {
        val elapsed = (now - ring.spawnTime).toFloat()
        if (elapsed < 0f) return

        val t       = FastOutSlowIn(elapsed / EXPAND_MS)
        val r       = dp(R0_DP) + (dp(R_MAX_DP) - dp(R0_DP)) * t
        val alpha   = ring.peakAlpha * (1f - (elapsed / (EXPAND_MS + 300f)).coerceAtMost(1f))
        if (alpha <= 0f) { ring.alive = false; return }

        // Draw ring arc
        ringPaint.color       = ring.primaryColor
        ringPaint.alpha       = (alpha * 255).toInt()
        ringPaint.strokeWidth = dp(ring.strokeDp)

        if (ring.isElliptical) {
            // Root mode: 85% height ratio (slightly squished)
            val oval = RectF(centerX - r, centerY - r * 0.85f, centerX + r, centerY + r * 0.85f)
            canvas.drawOval(oval, ringPaint)
        } else {
            canvas.drawCircle(centerX, centerY, r, ringPaint)
        }

        // Draw glyphs around the ring
        drawGlyphs(canvas, ring, r, alpha, now)
    }

    private fun drawGlyphs(canvas: Canvas, ring: RingState, r: Float, ringAlpha: Float, now: Long) {
        val hRatio = if (ring.isElliptical) 0.85f else 1f

        for (glyph in ring.glyphs) {
            val gx = centerX + r * cos(glyph.angleRad)
            val gy = centerY + r * hRatio * sin(glyph.angleRad)

            // Per-glyph fade
            var glyphAlpha = ring.glyphAlpha * ringAlpha
            if (now >= glyph.fadeStart) {
                val fadeElapsed = (now - glyph.fadeStart).toFloat()
                val fadeT = (fadeElapsed / 350f).coerceAtMost(1f)
                glyphAlpha *= (1f - DecelerateEase(fadeT))

                // Scramble before disappearing
                if (fadeElapsed < 180f && now - glyph.lastScramble > 60L) {
                    if (glyph.scrambles > 0) {
                        glyph.charIdx   = Random.nextInt(glyph.chars.size)
                        glyph.scrambles--
                        glyph.lastScramble = now
                    }
                }
            }

            if (glyphAlpha <= 0.01f) continue

            glyphPaint.color  = ring.glyphColor
            glyphPaint.alpha  = (glyphAlpha * 255).toInt()
            glyphPaint.textSize = dp(6f)

            val text = glyph.chars[glyph.charIdx]
            canvas.drawText(text, gx, gy + dp(3f), glyphPaint)
        }
    }

    // ── Data Streams ─────────────────────────────────────────────────────────

    private fun drawDataStreams(canvas: Canvas, now: Long) {
        if (streams.isEmpty()) return
        val firstSpawn = streams.firstOrNull()?.spawnTime ?: return
        val elapsed    = (now - firstSpawn).toFloat()

        // Stream lifetime: 80ms → 700ms (620ms window)
        val fadeIn  = (elapsed / 120f).coerceIn(0f, 1f)
        val fadeOut = if (elapsed > 540f) (1f - ((elapsed - 540f) / 160f)).coerceIn(0f, 1f) else 1f
        val streamAlpha = 0.40f * fadeIn * fadeOut
        if (streamAlpha <= 0f) return

        // Width of gap between ring 0 and ring 1 at current time
        val t0  = FastOutSlowIn((elapsed / EXPAND_MS).coerceAtMost(1f))
        val r0  = dp(R0_DP) + (dp(R_MAX_DP) - dp(R0_DP)) * t0
        val r1  = dp(R0_DP) + (dp(R_MAX_DP) - dp(R0_DP)) * FastOutSlowIn(
            ((elapsed - dp(RING_STAGGER_MS.toFloat())) / EXPAND_MS).coerceIn(0f, 1f))

        val streamColor = if (isRoot) COLOR_ROOT else COLOR_NONROOT
        streamPaint.color     = streamColor
        streamPaint.textSize  = dp(6f)

        for (stream in streams) {
            val startR = r1 + dp(4f)
            val endR   = r0 - dp(4f)
            if (endR <= startR) continue

            // Build scrolling hex string — long enough to fill the gap
            val hexStr = buildHexString(stream.scrollOffset, (endR - startR))
            val angle  = stream.angleRad

            // Forced perspective: textSize varies along the radial
            var curR = startR
            var charPos = 0
            while (curR < endR && charPos < hexStr.length) {
                val posT     = ((curR - startR) / (endR - startR)).coerceIn(0f, 1f)
                val textSz   = dp(6f) * (1f - posT * 0.35f)  // shrinks toward outer ring
                val curAlpha = streamAlpha * (1f - posT * 0.3f)

                streamPaint.textSize = textSz
                streamPaint.alpha    = (curAlpha * 255).toInt()

                val px = centerX + curR * cos(angle)
                val py = centerY + curR * sin(angle)

                // Rotate canvas to orient text along radial
                canvas.save()
                canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(), px, py)
                val ch = hexStr.getOrNull(charPos)?.toString() ?: break
                canvas.drawText(ch, px, py + textSz * 0.3f, streamPaint)
                canvas.restore()

                curR += textSz * 0.8f
                charPos++
            }
        }
    }

    private fun buildHexString(scrollOffset: Float, gapDp: Float): String {
        val hexPool = "A3F20C7B1E8D5294BEAC1765D034F9"
        val repeat  = ((gapDp / dp(8f)) + 4).toInt()
        val full    = hexPool.repeat(repeat)
        // Shift by scroll offset
        val shift   = (scrollOffset / dp(8f)).toInt() % hexPool.length
        return full.substring(shift.coerceAtMost(full.length - 1))
    }

    // ── Bloom ─────────────────────────────────────────────────────────────────

    private fun drawBloom(canvas: Canvas, elapsedMs: Long) {
        val t      = (elapsedMs / 300f).coerceAtMost(1f)
        val alpha  = 0.60f * (1f - t)
        val radius = dp(32f) * (0.2f + t * 0.8f)

        val bloomColor = if (isRoot) Color.parseColor("#AAFFD0") else Color.parseColor("#AAEEFF")
        bloomPaint.color = bloomColor
        bloomPaint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(centerX, centerY, radius, bloomPaint)
    }

    // ── Easing helpers ────────────────────────────────────────────────────────

    /** FastOutSlowIn (Cubic-Bézier 0.4, 0.0, 0.2, 1.0) approximation */
    private fun FastOutSlowIn(t: Float): Float {
        val tc = t.coerceIn(0f, 1f)
        return if (tc < 0.5f) 2f * tc * tc
        else -1f + (4f - 2f * tc) * tc
    }

    private fun DecelerateEase(t: Float): Float {
        val tc = t.coerceIn(0f, 1f)
        return 1f - (1f - tc) * (1f - tc)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderAnimator?.cancel()
        heartbeatAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Convert a child view’s centre to this overlay view’s coordinate space */
    fun centreOf(child: android.view.View): android.util.Pair<Float, Float> {
        val loc   = IntArray(2)
        val myLoc = IntArray(2)
        child.getLocationOnScreen(loc)
        getLocationOnScreen(myLoc)
        return android.util.Pair(
            loc[0] + child.width / 2f - myLoc[0],
            loc[1] + child.height / 2f - myLoc[1]
        )
    }
}
