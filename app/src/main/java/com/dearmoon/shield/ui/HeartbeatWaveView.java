package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

public class HeartbeatWaveView extends View {
    private Paint wavePaint;
    private Path wavePath;
    private float phase = 0f;
    private ValueAnimator animator;

    private static final int WAVE_COLOR = 0xFF10B981; // Emerald green
    private static final float WAVE_HEIGHT = 40f;
    private static final float WAVE_FREQUENCY = 0.02f;

    public HeartbeatWaveView(Context context) {
        super(context);
        init();
    }

    public HeartbeatWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HeartbeatWaveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(3f);
        wavePaint.setColor(WAVE_COLOR);

        wavePath = new Path();

        // Start animation
        animator = ValueAnimator.ofFloat(0f, 1000f);
        animator.setDuration(2000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;

        wavePath.reset();
        wavePath.moveTo(0, centerY);

        // Create heartbeat pattern
        for (int x = 0; x < width; x++) {
            float normalizedX = (x + phase) % width;
            float y;

            // Create ECG-like heartbeat pattern
            if (normalizedX < width * 0.3f) {
                y = centerY;
            } else if (normalizedX < width * 0.35f) {
                // Small dip
                y = centerY + WAVE_HEIGHT * 0.3f;
            } else if (normalizedX < width * 0.4f) {
                // Sharp spike
                y = centerY - WAVE_HEIGHT * 1.5f;
            } else if (normalizedX < width * 0.45f) {
                // Deep valley
                y = centerY + WAVE_HEIGHT * 0.8f;
            } else if (normalizedX < width * 0.5f) {
                // Recovery spike
                y = centerY - WAVE_HEIGHT * 0.4f;
            } else {
                y = centerY;
            }

            wavePath.lineTo(x, y);
        }

        canvas.drawPath(wavePath, wavePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
        }
    }
}
