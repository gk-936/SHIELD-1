package com.dearmoon.shield.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;
import androidx.appcompat.widget.AppCompatTextView;
import java.util.Random;

public class GlitchTextView extends AppCompatTextView {
    private Paint scanBeamPaint;
    private float scanBeamPosition = -1f;
    private boolean isScanning = false;

    private Paint glitchPaint;
    private boolean isGlitching = false;
    private float glitchOffsetX = 0f;
    private float glitchOffsetY = 0f;
    private int glitchColor = 0;
    private Random random = new Random();

    private ValueAnimator glitchAnimator;
    private ValueAnimator scanAnimator;

    private boolean showCursor = false;
    private boolean cursorVisible = true;
    private ValueAnimator cursorAnimator;

    public GlitchTextView(Context context) {
        super(context);
        init();
    }

    public GlitchTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GlitchTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Scan beam paint
        scanBeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scanBeamPaint.setStyle(Paint.Style.FILL);

        // Glitch paint
        glitchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glitchPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw glitch effect if active
        if (isGlitching && glitchOffsetX != 0) {
            canvas.save();
            canvas.translate(glitchOffsetX, glitchOffsetY);

            // Draw chromatic aberration layers
            int originalColor = getCurrentTextColor();

            // Red channel offset
            setTextColor(0xFFFF0000);
            canvas.save();
            canvas.translate(-2, 0);
            super.onDraw(canvas);
            canvas.restore();

            // Cyan channel offset
            setTextColor(0xFF00FFFF);
            canvas.save();
            canvas.translate(2, 0);
            super.onDraw(canvas);
            canvas.restore();

            // Restore original color
            setTextColor(originalColor);
            canvas.restore();
        }

        // Draw main text
        super.onDraw(canvas);

        // Draw cursor if enabled
        if (showCursor && cursorVisible) {
            Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cursorPaint.setColor(getCurrentTextColor());
            cursorPaint.setStyle(Paint.Style.FILL);

            float textWidth = getPaint().measureText(getText().toString());
            float cursorX = (getWidth() - textWidth) / 2 + textWidth + 10;
            float cursorY = getHeight() / 2 - getTextSize() / 2;
            float cursorHeight = getTextSize();

            canvas.drawRect(cursorX, cursorY, cursorX + 8, cursorY + cursorHeight, cursorPaint);
        }

        // Draw scan beam if active
        if (isScanning && scanBeamPosition >= 0) {
            int beamColor = 0x8810B981; // Semi-transparent green
            scanBeamPaint.setShader(new LinearGradient(
                    0, scanBeamPosition - 50,
                    0, scanBeamPosition + 50,
                    new int[] { 0x00000000, beamColor, 0x00000000 },
                    new float[] { 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP));

            canvas.drawRect(0, 0, getWidth(), getHeight(), scanBeamPaint);
        }
    }

    public void startGlitchEffect() {
        if (glitchAnimator != null) {
            glitchAnimator.cancel();
        }

        isGlitching = true;
        glitchAnimator = ValueAnimator.ofFloat(0f, 1f);
        glitchAnimator.setDuration(100);
        glitchAnimator.setRepeatCount(ValueAnimator.INFINITE);
        glitchAnimator.setRepeatMode(ValueAnimator.RESTART);

        glitchAnimator.addUpdateListener(animation -> {
            // Random glitch every few frames
            if (random.nextFloat() > 0.7f) {
                glitchOffsetX = (random.nextFloat() - 0.5f) * 6;
                glitchOffsetY = (random.nextFloat() - 0.5f) * 4;
            } else {
                glitchOffsetX = 0;
                glitchOffsetY = 0;
            }
            invalidate();
        });

        glitchAnimator.start();
    }

    public void stopGlitchEffect() {
        isGlitching = false;
        if (glitchAnimator != null) {
            glitchAnimator.cancel();
        }
        glitchOffsetX = 0;
        glitchOffsetY = 0;
        invalidate();
    }

    public void startScanBeam(final Runnable onComplete) {
        if (scanAnimator != null) {
            scanAnimator.cancel();
        }

        isScanning = true;
        scanBeamPosition = 0;

        scanAnimator = ValueAnimator.ofFloat(0f, getHeight());
        scanAnimator.setDuration(800);
        scanAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

        scanAnimator.addUpdateListener(animation -> {
            scanBeamPosition = (float) animation.getAnimatedValue();
            invalidate();
        });

        scanAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isScanning = false;
                scanBeamPosition = -1f;
                invalidate();
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });

        scanAnimator.start();
    }

    public void startCursorBlink() {
        showCursor = true;

        if (cursorAnimator != null) {
            cursorAnimator.cancel();
        }

        cursorAnimator = ValueAnimator.ofFloat(0f, 1f);
        cursorAnimator.setDuration(530);
        cursorAnimator.setRepeatCount(ValueAnimator.INFINITE);
        cursorAnimator.setRepeatMode(ValueAnimator.REVERSE);

        cursorAnimator.addUpdateListener(animation -> {
            cursorVisible = animation.getAnimatedFraction() > 0.5f;
            invalidate();
        });

        cursorAnimator.start();
    }

    public void stopCursorBlink() {
        showCursor = false;
        if (cursorAnimator != null) {
            cursorAnimator.cancel();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (glitchAnimator != null) {
            glitchAnimator.cancel();
        }
        if (scanAnimator != null) {
            scanAnimator.cancel();
        }
        if (cursorAnimator != null) {
            cursorAnimator.cancel();
        }
    }
}
