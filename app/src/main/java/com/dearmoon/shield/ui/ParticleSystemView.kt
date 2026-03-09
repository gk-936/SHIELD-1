package com.dearmoon.shield.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ParticleSystemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val particles = mutableListOf<Particle>()
    private val maxParticles = 1200 // Increased for denser galactic feel
    private var cx = 0f
    private var cy = 0f
    private var maxRadius = 0f

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        maxRadius = Math.min(w, h) * 0.48f
        
        if (particles.isEmpty()) {
            initParticles()
        }
    }

    private fun initParticles() {
        particles.clear()
        for (i in 0 until maxParticles) {
            particles.add(createParticle(true))
        }
    }

    private val TYPE_CORE = 0
    private val TYPE_LEAKING = 1
    private val TYPE_OUTER = 2

    private fun createParticle(initial: Boolean): Particle {
        val rand = Random.nextFloat()
        val type: Int
        var r: Float
        var angle = Random.nextFloat() * 2 * Math.PI.toFloat()
        val size: Float
        val alpha: Float
        var speedR = 0f
        var speedAngle = 0f
        val lifeSpeed: Float

        val coreR = maxRadius * 0.35f

        if (rand < 0.45f) {
            // Core - The bright central fragmented/irregular ring
            type = TYPE_CORE
            
            // To make it look "broken" and "C-shaped" like the reference image
            if (Random.nextFloat() > 0.25f) {
               // cluster largely in a C-shape arc
               angle = (Random.nextFloat() * 1.5f * Math.PI.toFloat()) + 0.3f
            }
            
            // Bias towards the edge of the core distance to form a ring
            r = coreR * sqrt(Random.nextFloat())
            if (Random.nextFloat() > 0.4f) {
                // push strongly to outer edge of the core
                r = coreR * 0.7f + Random.nextFloat() * coreR * 0.3f
            }
            
            size = 1.5f + Random.nextFloat() * 5.5f
            alpha = 180f + Random.nextFloat() * 75f
            speedAngle = (Random.nextFloat() - 0.5f) * 0.01f
            lifeSpeed = 0.005f + Random.nextFloat() * 0.015f
        } else if (rand < 0.75f) {
            // Leaking (from core to outside, breaking apart)
            type = TYPE_LEAKING
            r = if (initial) Random.nextFloat() * maxRadius else coreR * (0.8f + Random.nextFloat() * 0.3f)
            size = 1f + Random.nextFloat() * 3f
            alpha = 100f + Random.nextFloat() * 100f
            speedR = 0.1f + Random.nextFloat() * 1.2f
            speedAngle = (Random.nextFloat() - 0.5f) * 0.03f
            
            // Particles leaking out of the gap of the "C" shape
            if (!initial && Random.nextFloat() > 0.5f) {
                angle = (Random.nextFloat() * 0.5f * Math.PI.toFloat()) - 0.1f 
            }
            lifeSpeed = 0.008f + Random.nextFloat() * 0.02f
        } else {
            // Outer dust/rings / planetary fragments circling
            type = TYPE_OUTER
            
            // Concentrated in certain orbit bands
            val band = Random.nextInt(3)
            r = coreR * 1.2f + band * (maxRadius * 0.2f) + (Random.nextFloat() - 0.5f) * (maxRadius * 0.1f)
            
            size = 0.5f + Random.nextFloat() * 2f
            alpha = 40f + Random.nextFloat() * 120f
            speedAngle = 0.001f + Random.nextFloat() * 0.006f
            if (band % 2 == 0) speedAngle = -speedAngle // alternate rotation direction
            lifeSpeed = 0.002f + Random.nextFloat() * 0.004f
        }

        return Particle(
            type = type,
            r = r,
            angle = angle,
            size = size,
            maxAlpha = alpha,
            alpha = if (initial) alpha * Random.nextFloat() else 0f,
            speedR = speedR,
            speedAngle = speedAngle,
            life = if (initial) Random.nextFloat() else 0f,
            lifeSpeed = lifeSpeed
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val iterator = particles.iterator()
        val toAdd = mutableListOf<Particle>()

        // Soft center glow - much softer than before
        glowPaint.alpha = 8
        glowPaint.maskFilter = BlurMaskFilter(maxRadius * 0.35f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, maxRadius * 0.3f, glowPaint)

        // Disable mask filter for actual particles to draw fast
        particlePaint.maskFilter = null

        while (iterator.hasNext()) {
            val p = iterator.next()
            
            p.life += p.lifeSpeed
            
            // Fade in and fade out based on life 0..1
            val lifeFactor = when {
                p.life < 0.2f -> p.life / 0.2f
                p.life > 0.8f -> (1f - p.life) / 0.2f
                else -> 1f
            }
            p.alpha = p.maxAlpha * lifeFactor

            p.angle += p.speedAngle
            p.r += p.speedR

            if (p.type == TYPE_LEAKING) {
                // Leaking particles fade out as they reach maxRadius
                val distFactor = (1f - (p.r / maxRadius)).coerceIn(0f, 1f)
                p.alpha *= distFactor * 1.5f
            }
            
            // Jitter for core
            var dx = 0f
            var dy = 0f
            if (p.type == TYPE_CORE) {
                dx = (Random.nextFloat() - 0.5f) * 1.2f
                dy = (Random.nextFloat() - 0.5f) * 1.2f
                // very slight outward drift for the breaking effect
                if (Random.nextFloat() < 0.03f) {
                    p.r += 0.8f
                }
            }

            if (p.life >= 1f || p.r > maxRadius) {
                iterator.remove()
                toAdd.add(createParticle(false))
                continue
            }

            val x = cx + p.r * cos(p.angle) + dx
            val y = cy + p.r * sin(p.angle) + dy

            particlePaint.alpha = p.alpha.toInt().coerceIn(0, 255)
            
            // Slight glow for larger bright particles
            if (p.size > 3.5f && p.alpha > 120f) {
                particlePaint.setShadowLayer(p.size * 1.5f, 0f, 0f, Color.argb(120, 255, 255, 255))
            } else {
                particlePaint.clearShadowLayer()
            }

            canvas.drawCircle(x, y, p.size, particlePaint)
        }

        particles.addAll(toAdd)

        postInvalidateOnAnimation()
    }

    private data class Particle(
        val type: Int,
        var r: Float,
        var angle: Float,
        val size: Float,
        val maxAlpha: Float,
        var alpha: Float,
        val speedR: Float,
        val speedAngle: Float,
        var life: Float,
        val lifeSpeed: Float
    )
}
