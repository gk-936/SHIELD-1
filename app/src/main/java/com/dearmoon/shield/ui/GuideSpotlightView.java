package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Draws a semi-transparent dark overlay over the screen plus an animated
 * pulsing neon glow RING around a target rectangle (no box / no cutout).
 *
 * The ring pulses outward and fades, giving a radar-sweep / spotlight feel
 * without the look of a plain rectangle box.
 */
public class GuideSpotlightView extends View {

    // dim overlay
    private final Paint dimPaint = new Paint();

    // inner glowing ring (solid neon border)
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // outer expanding pulse ring
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RectF targetRect;          // the button to highlight
    private float cornerRadius = 24f;
    private float pulseExpand  = 0f;   // animated 0 → MAX_EXPAND
    private float pulseAlpha   = 1f;

    private static final float MAX_EXPAND = 24f; // dp

    private ValueAnimator pulseAnim;

    public GuideSpotlightView(Context context) {
        super(context);
        init();
    }

    public GuideSpotlightView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        // Dark overlay — NOT a full blackout, so real UI is visible but dimmed
        dimPaint.setColor(0xBB000000);
        dimPaint.setStyle(Paint.Style.FILL);

        // Solid neon cyan inner ring
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f);
        ringPaint.setColor(0xFF00E5FF);
        ringPaint.setMaskFilter(new android.graphics.BlurMaskFilter(8f,
                android.graphics.BlurMaskFilter.Blur.OUTER));

        // Expanding pulse ring (purple)
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(4f);
        pulsePaint.setColor(0xFFD500F9);

        startPulseAnimation();
    }

    private void startPulseAnimation() {
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(1000);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setInterpolator(new LinearInterpolator());
        pulseAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            pulseExpand = t * MAX_EXPAND * getResources().getDisplayMetrics().density;
            pulseAlpha  = 1f - t;   // fades as it expands
            invalidate();
        });
    }

    /** Set the target button's rectangle (in this view's own coord space). */
    public void setTargetRect(RectF rectInViewSpace, float corner) {
        this.targetRect = rectInViewSpace;
        this.cornerRadius = corner;
        if (pulseAnim != null && !pulseAnim.isRunning()) {
            pulseAnim.start();
        } else if (pulseAnim != null) {
            pulseAnim.start(); // restart for new target
        }
        invalidate();
    }

    public void clearTarget() {
        targetRect = null;
        if (pulseAnim != null) pulseAnim.cancel();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Full dim overlay
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

        if (targetRect == null) return;

        // ── Inner solid neon ring tight around the target ──────────
        canvas.drawRoundRect(targetRect, cornerRadius, cornerRadius, ringPaint);

        // ── Expanding pulse ring that grows outward and fades ───────
        pulsePaint.setAlpha((int) (255 * pulseAlpha * 0.8f));
        RectF pulseRect = new RectF(
                targetRect.left   - pulseExpand,
                targetRect.top    - pulseExpand,
                targetRect.right  + pulseExpand,
                targetRect.bottom + pulseExpand
        );
        canvas.drawRoundRect(pulseRect, cornerRadius + pulseExpand,
                cornerRadius + pulseExpand, pulsePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnim != null) pulseAnim.cancel();
    }
}
