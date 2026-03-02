package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Data-Stream Compilation animation.
 *
 * Phase 1 (progress 0.00–0.40): Binary digits scattered as digital noise — low opacity, drifting.
 * Phase 2 (progress 0.40–0.75): Particles accelerate toward the screen centre.
 * Phase 3 (progress 0.65–0.90): "SYSTEM ARMED" materialises as the particles converge.
 * Phase 4 (progress 0.85–1.00): Text pulses with a neon glow; particles fully fade out.
 */
public class BinaryConvergeView extends View {

    // ── Colour constants ───────────────────────────────────────────────────────
    private static final int CYAN    = 0xFF00f3ff;
    private static final int MAGENTA = 0xFFff00ff;

    // ── Paints ─────────────────────────────────────────────────────────────────
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Particles ──────────────────────────────────────────────────────────────
    private static final int PARTICLE_COUNT = 130;
    private final List<Particle> particles  = new ArrayList<>(PARTICLE_COUNT);
    private final Random rng                = new Random();

    // ── Animation ──────────────────────────────────────────────────────────────
    private ValueAnimator animator;
    private float progress = 0f;         // 0.0 → 1.0 drives every phase

    // Pulse sub-animation
    private ValueAnimator pulseAnim;
    private float pulseAlpha = 0f;

    // ── Callbacks ──────────────────────────────────────────────────────────────
    private Runnable onFinishedCallback;

    // ── Internal state flags ──────────────────────────────────────────────────
    private boolean particlesSeeded = false;
    private float   cx = 0f, cy = 0f;    // centre of the view

    // ─────────────────────────────────────────────────────────────────────────
    private static class Particle {
        float  x, y;          // current position (absolute px)
        float  startX, startY;// spawn position
        char   bit;           // '0' or '1'
        float  driftVx;       // slow random drift velocity (phase-1)
        float  size;          // text size in px
        float  phaseOffset;   // 0..1 stagger so they don't all arrive at once
    }

    // ─────────────────────────────────────────────────────────────────────────
    public BinaryConvergeView(Context context) {
        super(context); init(context);
    }
    public BinaryConvergeView(Context context, AttributeSet attrs) {
        super(context, attrs); init(context);
    }
    public BinaryConvergeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init(context);
    }

    private void init(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;

        // Particle paint — monospace so 0/1 look crisp
        particlePaint.setTypeface(Typeface.MONOSPACE);
        particlePaint.setTextAlign(Paint.Align.CENTER);

        // Main "SYSTEM ARMED" text paint
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(38f * dp);
        textPaint.setLetterSpacing(0.15f);
        textPaint.setColor(CYAN);

        // Glow paint (slightly larger, magenta, very transparent → drawn first)
        glowPaint.setTypeface(Typeface.MONOSPACE);
        glowPaint.setTextAlign(Paint.Align.CENTER);
        glowPaint.setTextSize(40f * dp);
        glowPaint.setLetterSpacing(0.15f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    /** Call this when the progress bar hits 100% to kick off the full sequence. */
    public void startAnimation(Runnable onFinished) {
        this.onFinishedCallback = onFinished;
        setVisibility(VISIBLE);
        progress = 0f;
        particlesSeeded = false;

        if (animator != null) animator.cancel();

        // Total duration: 3.6 s
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3600);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            if (getWidth() > 0 && !particlesSeeded) seedParticles();
            updateParticles();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                startPulseThenFinish();
            }
        });
        animator.start();
    }

    public void stopAnimation() {
        if (animator  != null) animator.cancel();
        if (pulseAnim != null) pulseAnim.cancel();
        setVisibility(GONE);
        particles.clear();
        particlesSeeded = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    private void seedParticles() {
        particlesSeeded = true;
        cx = getWidth()  / 2f;
        cy = getHeight() / 2f;
        particles.clear();

        float dp   = getResources().getDisplayMetrics().density;
        int   W    = getWidth();
        int   H    = getHeight();

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.bit         = (rng.nextBoolean()) ? '1' : '0';
            p.startX      = 20f + rng.nextFloat() * (W - 40f);
            p.startY      = 20f + rng.nextFloat() * (H - 40f);
            p.x           = p.startX;
            p.y           = p.startY;
            p.driftVx     = (rng.nextFloat() - 0.5f) * 1.2f * dp;
            p.size        = (8f + rng.nextFloat() * 8f) * dp;
            p.phaseOffset = rng.nextFloat() * 0.25f;   // stagger convergence
            particles.add(p);
        }
    }

    /** Interpolate each particle toward the centre based on current progress. */
    private void updateParticles() {
        if (!particlesSeeded) return;
        for (Particle p : particles) {
            // Convergence starts at ~0.35 and completes at ~(0.75 + phaseOffset)
            float convStart = 0.35f + p.phaseOffset * 0.15f;
            float convEnd   = 0.75f + p.phaseOffset * 0.20f;
            float t         = clamp01((progress - convStart) / (convEnd - convStart));

            // Ease-in: particles start slow, then rocket toward centre
            float eased = t * t * t;

            p.x = lerp(p.startX, cx, eased);
            p.y = lerp(p.startY, cy, eased);

            // Phase-1 drift
            if (t < 0.01f) {
                p.x += p.driftVx * progress * 60f;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onDraw(Canvas canvas) {
        int W = getWidth();
        int H = getHeight();
        if (W == 0 || H == 0) return;

        // ── Draw binary particles ────────────────────────────────────────────
        for (Particle p : particles) {
            // Particle alpha: full from 0→0.65, fades out from 0.65→0.90
            float showT  = clamp01((progress) / 0.35f);          // 0→1 during phase-1 fade-in
            float hideT  = clamp01((progress - 0.65f) / 0.25f);  // 0→1 during convergence fade-out
            float pAlpha = showT * (1f - hideT);

            if (pAlpha < 0.01f) continue;

            particlePaint.setTextSize(p.size);
            particlePaint.setColor(Color.argb((int)(pAlpha * 200), 0, 243, 255));
            canvas.drawText(String.valueOf(p.bit), p.x, p.y, particlePaint);
        }

        // ── Draw "SYSTEM ARMED" text ─────────────────────────────────────────
        // Materialises from 0.60 → 0.88, then stays at 1.0
        float textT    = clamp01((progress - 0.60f) / 0.28f);
        float textAlpha = textT;

        if (textAlpha > 0.01f) {
            float yPos = H / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;

            // Magenta glow layer (behind, semi-transparent)
            float glowA = textAlpha * (0.4f + pulseAlpha * 0.6f);
            glowPaint.setColor(Color.argb((int)(glowA * 255), 255, 0, 255));
            canvas.drawText("LET'S SECURE", cx + 3f, yPos + 3f, glowPaint);

            // Main cyan text
            textPaint.setAlpha((int)(textAlpha * 255));
            canvas.drawText("LET'S SECURE", cx, yPos, textPaint);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    /** After main animation, run a 2-cycle neon pulse then invoke the callback. */
    private void startPulseThenFinish() {
        pulseAnim = ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f);
        pulseAnim.setDuration(1400);
        pulseAnim.setInterpolator(new AccelerateInterpolator(0.5f));
        pulseAnim.addUpdateListener(a -> {
            pulseAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // Fade out everything
                ValueAnimator fadeOut = ValueAnimator.ofFloat(1f, 0f);
                fadeOut.setDuration(700);
                fadeOut.addUpdateListener(a2 -> setAlpha((float) a2.getAnimatedValue()));
                fadeOut.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        setVisibility(GONE);
                        setAlpha(1f);
                        if (onFinishedCallback != null) onFinishedCallback.run();
                    }
                });
                fadeOut.start();
            }
        });
        pulseAnim.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }
}
