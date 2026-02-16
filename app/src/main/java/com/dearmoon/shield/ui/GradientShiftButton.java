package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.widget.AppCompatButton;

public class GradientShiftButton extends AppCompatButton {
    private Paint gradientPaint;
    private float gradientOffset = 0f;
    private ValueAnimator gradientAnimator;

    private static final int[] GRADIENT_COLORS = {
            0xFF8C72C1, // Deep purple
            0xFF6366F1, // Indigo
            0xFF3B82F6, // Blue
            0xFF6366F1, // Indigo
            0xFF8C72C1 // Deep purple
    };

    public GradientShiftButton(Context context) {
        super(context);
        init();
    }

    public GradientShiftButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GradientShiftButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gradientPaint.setStyle(Paint.Style.FILL);
    }

    public void startGradientShift() {
        if (gradientAnimator != null) {
            gradientAnimator.cancel();
        }

        gradientAnimator = ValueAnimator.ofFloat(0f, getWidth() * 2);
        gradientAnimator.setDuration(4000);
        gradientAnimator.setRepeatCount(ValueAnimator.INFINITE);
        gradientAnimator.setInterpolator(new LinearInterpolator());

        gradientAnimator.addUpdateListener(animation -> {
            gradientOffset = (float) animation.getAnimatedValue();
            invalidate();
        });

        gradientAnimator.start();
    }

    public void stopGradientShift() {
        if (gradientAnimator != null) {
            gradientAnimator.cancel();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            startGradientShift();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() > 0) {
            gradientPaint.setShader(new LinearGradient(
                    -gradientOffset, 0,
                    getWidth() - gradientOffset, 0,
                    GRADIENT_COLORS,
                    null,
                    Shader.TileMode.MIRROR));

            float radius = 16 * getResources().getDisplayMetrics().density;
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, gradientPaint);
        }

        super.onDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (gradientAnimator != null) {
            gradientAnimator.cancel();
        }
    }
}
