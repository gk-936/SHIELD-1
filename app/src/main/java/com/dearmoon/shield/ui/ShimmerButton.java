package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.widget.AppCompatButton;

public class ShimmerButton extends AppCompatButton {
    private Paint shimmerPaint;
    private float shimmerPosition = -1f;
    private ValueAnimator shimmerAnimator;
    private boolean shimmerEnabled = false;

    public ShimmerButton(Context context) {
        super(context);
        init();
    }

    public ShimmerButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ShimmerButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (shimmerEnabled && shimmerPosition >= 0 && shimmerPosition <= getWidth()) {
            int shimmerWidth = 100;

            shimmerPaint.setShader(new LinearGradient(
                    shimmerPosition - shimmerWidth, 0,
                    shimmerPosition + shimmerWidth, 0,
                    new int[] { 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF },
                    new float[] { 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP));

            canvas.drawRect(0, 0, getWidth(), getHeight(), shimmerPaint);
        }
    }

    public void startShimmer() {
        shimmerEnabled = true;

        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
        }

        shimmerAnimator = ValueAnimator.ofFloat(-100f, getWidth() + 100f);
        shimmerAnimator.setDuration(1500);
        shimmerAnimator.setStartDelay(5000);
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
        shimmerAnimator.setInterpolator(new LinearInterpolator());

        shimmerAnimator.addUpdateListener(animation -> {
            shimmerPosition = (float) animation.getAnimatedValue();
            invalidate();
        });

        shimmerAnimator.start();
    }

    public void stopShimmer() {
        shimmerEnabled = false;
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
        }
    }
}
