package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class SonarDotView extends View {
    private Paint dotPaint;
    private Paint pulsePaint;
    private float pulseRadius = 0f;
    private ValueAnimator pulseAnimator;

    private static final int DOT_COLOR = 0xFF10B981; // Emerald green
    private static final float DOT_RADIUS = 12f;

    public SonarDotView(Context context) {
        super(context);
        init();
    }

    public SonarDotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SonarDotView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(DOT_COLOR);

        pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pulsePaint.setStyle(Paint.Style.FILL);

        // Start pulse animation
        pulseAnimator = ValueAnimator.ofFloat(0f, 30f);
        pulseAnimator.setDuration(1500);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(animation -> {
            pulseRadius = (float) animation.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        // Draw pulsing ring
        if (pulseRadius > 0) {
            float alpha = 1f - (pulseRadius / 30f);
            int pulseColor = DOT_COLOR & 0x00FFFFFF | ((int) (alpha * 128) << 24);

            pulsePaint.setShader(new RadialGradient(
                    centerX, centerY, pulseRadius,
                    new int[] { pulseColor, 0x00000000 },
                    new float[] { 0.5f, 1f },
                    Shader.TileMode.CLAMP));

            canvas.drawCircle(centerX, centerY, pulseRadius, pulsePaint);
        }

        // Draw solid dot
        canvas.drawCircle(centerX, centerY, DOT_RADIUS, dotPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
    }
}
