package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dynamic Island-style liquid fill view.
 * Neon green "boiling" liquid with animated sine-wave leading edge
 * and bubble particles near the fill boundary.
 */
public class WaterFillView extends View {

    // ── Liquid paints ────────────────────────────────────────────
    private final Paint waterPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterBgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  wavePath      = new Path();
    private final Path  wavePathBg    = new Path();

    // ── State ─────────────────────────────────────────────────────
    private float fillFraction = 0f;
    private float wavePhase    = 0f;
    private float waveAmpDp    = 3f;

    // ── Bubbles ───────────────────────────────────────────────────
    private static final int MAX_BUBBLES = 10;
    private final List<Bubble> bubbles   = new ArrayList<>();
    private final Random rng             = new Random();

    // ── Animators ─────────────────────────────────────────────────
    private ValueAnimator waveAnim;
    private ValueAnimator fillAnim;

    // ── Neon Green palette ────────────────────────────────────────
    private static final int COLOR_NEON_GREEN  = 0xFF00FF41;  // bright neon green
    private static final int COLOR_MID_GREEN   = 0xFF00C832;  // mid green
    private static final int COLOR_DEEP_GREEN  = 0xFF006B1B;  // deep green

    // =============================================================
    private static class Bubble {
        float relX;      // x relative to fill-right (0..1)
        float relY;      // y fraction of height (0..1)
        float radius;    // px
        float speed;     // upward speed per frame
        float alpha;
    }

    public WaterFillView(Context context) {
        super(context); init(context);
    }
    public WaterFillView(Context context, AttributeSet attrs) {
        super(context, attrs); init(context);
    }
    public WaterFillView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init(context);
    }

    private void init(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        waveAmpDp = 3.5f * dp;

        waterPaint.setStyle(Paint.Style.FILL);
        waterBgPaint.setStyle(Paint.Style.FILL);

        bubblePaint.setStyle(Paint.Style.FILL);

        glowPaint.setStyle(Paint.Style.FILL);

        // Seed initial bubbles
        for (int i = 0; i < MAX_BUBBLES; i++) spawnBubble();

        // Continuous wave phase animation (drives wave + bubble updates)
        waveAnim = ValueAnimator.ofFloat(0f, (float)(Math.PI * 2));
        waveAnim.setDuration(1100);
        waveAnim.setRepeatCount(ValueAnimator.INFINITE);
        waveAnim.setInterpolator(new LinearInterpolator());
        waveAnim.addUpdateListener(a -> {
            wavePhase = (float) a.getAnimatedValue();
            tickBubbles();
            invalidate();
        });
        waveAnim.start();
    }

    private void spawnBubble() {
        Bubble b = new Bubble();
        // Bubbles hug the leading edge — random x in [-18px..+10px] relative to fillRight
        float dp = getResources().getDisplayMetrics().density;
        b.relX    = (rng.nextFloat() - 0.7f) * 18f * dp;  // mostly behind edge
        b.relY    = 0.5f + rng.nextFloat() * 0.5f;         // lower half rising
        b.radius  = (2f + rng.nextFloat() * 3f) * dp;
        b.speed   = 0.004f + rng.nextFloat() * 0.008f;     // fraction of height per tick
        b.alpha   = 0.4f + rng.nextFloat() * 0.5f;
        bubbles.add(b);
    }

    private void tickBubbles() {
        for (int i = bubbles.size() - 1; i >= 0; i--) {
            Bubble b = bubbles.get(i);
            b.relY -= b.speed;
            b.alpha -= 0.012f;
            if (b.relY < 0f || b.alpha <= 0f) {
                bubbles.remove(i);
                spawnBubble();
            }
        }
    }

    /** Animate to a new fill level (0.0–1.0) */
    public void setFillFraction(float target) {
        if (fillAnim != null) fillAnim.cancel();
        float start = fillFraction;
        fillAnim = ValueAnimator.ofFloat(start, target);
        fillAnim.setDuration(650);
        fillAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        fillAnim.addUpdateListener(a -> {
            fillFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        fillAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int W = getWidth();
        int H = getHeight();
        if (W == 0 || H == 0) return;

        float fillRight = W * fillFraction;
        if (fillRight <= 0f) return;

        // ── Foreground Wave ────────────────────────────────────────
        int steps = H * 2;
        wavePath.reset();
        wavePath.moveTo(0, 0);
        wavePath.lineTo(fillRight, 0);
        for (int i = 0; i <= steps; i++) {
            float y = (float) i / steps * H;
            float x = fillRight + (float) Math.sin(y / H * Math.PI * 3 + wavePhase) * waveAmpDp;
            wavePath.lineTo(x, y);
        }
        wavePath.lineTo(0, H);
        wavePath.close();

        // ── Background Wave (parallax) ─────────────────────────────
        wavePathBg.reset();
        wavePathBg.moveTo(0, 0);
        wavePathBg.lineTo(fillRight, 0);
        for (int i = 0; i <= steps; i++) {
            float y = (float) i / steps * H;
            float x = fillRight + (float) Math.sin(y / H * Math.PI * 4 - wavePhase * 1.5f) * (waveAmpDp * 1.5f);
            wavePathBg.lineTo(x, y);
        }
        wavePathBg.lineTo(0, H);
        wavePathBg.close();

        // ── Neon green gradient ─────────────────────────────────────
        LinearGradient mainGrad = new LinearGradient(
                0, 0, fillRight, 0,
                new int[]{ COLOR_DEEP_GREEN, COLOR_MID_GREEN, COLOR_NEON_GREEN },
                new float[]{ 0f, 0.6f, 1f },
                Shader.TileMode.CLAMP
        );
        waterPaint.setShader(mainGrad);

        LinearGradient bgGrad = new LinearGradient(
                0, 0, fillRight, 0,
                new int[]{ COLOR_DEEP_GREEN, COLOR_MID_GREEN, COLOR_NEON_GREEN },
                new float[]{ 0f, 0.6f, 1f },
                Shader.TileMode.CLAMP
        );
        waterBgPaint.setShader(bgGrad);
        waterBgPaint.setAlpha(80);

        canvas.drawPath(wavePathBg, waterBgPaint);
        canvas.drawPath(wavePath,   waterPaint);

        // ── Leading edge glow ──────────────────────────────────────
        if (fillRight > 4f) {
            float glowRadius = H * 0.85f;
            RadialGradient edgeGlow = new RadialGradient(
                    fillRight, H / 2f, glowRadius,
                    new int[]{ 0x6600FF41, 0x2200C832, 0x00000000 },
                    new float[]{ 0f, 0.4f, 1f },
                    Shader.TileMode.CLAMP
            );
            glowPaint.setShader(edgeGlow);
            canvas.drawCircle(fillRight, H / 2f, glowRadius, glowPaint);
        }

        // ── Bubble particles ───────────────────────────────────────
        for (Bubble b : bubbles) {
            float bx = fillRight + b.relX;
            float by = H * b.relY;
            int alpha = Math.max(0, Math.min(255, (int)(b.alpha * 255)));
            if (bx < 0 || bx > W) continue;

            RadialGradient bubbleShader = new RadialGradient(
                    bx, by, b.radius,
                    new int[]{ Color.argb(alpha, 0, 255, 65), Color.TRANSPARENT },
                    null,
                    Shader.TileMode.CLAMP
            );
            bubblePaint.setShader(bubbleShader);
            canvas.drawCircle(bx, by, b.radius, bubblePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (waveAnim != null) waveAnim.cancel();
        if (fillAnim  != null) fillAnim.cancel();
    }
}
