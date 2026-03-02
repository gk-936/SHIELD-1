package com.dearmoon.shield.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.animation.BounceInterpolator;
import androidx.appcompat.widget.AppCompatTextView;

import java.util.Random;

public class GlitchTextView extends AppCompatTextView {

    private boolean isGlitching = false;
    private boolean isFailingNeon = false;
    private float glitchOffsetX = 0f;
    private float glitchOffsetY = 0f;

    private int cyanColor;
    private int magentaColor;
    private Random random = new Random();
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable glitchRunnable;
    private Runnable flickerRunnable;

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
        cyanColor = Color.parseColor("#00f3ff");
        magentaColor = Color.parseColor("#ff00ff");

        glitchRunnable = new Runnable() {
            @Override
            public void run() {
                if (isGlitching) {
                    // Random offset for Chromatic Aberration
                    glitchOffsetX = (random.nextFloat() - 0.5f) * 20f;
                    glitchOffsetY = (random.nextFloat() - 0.5f) * 10f;
                    invalidate();

                    // Phase 3: the glitch offset lasts for ~0.2s (200ms)
                    handler.postDelayed(() -> {
                        glitchOffsetX = 0;
                        glitchOffsetY = 0;
                        invalidate();
                    }, 200);

                    // Random intervals between next glitch
                    handler.postDelayed(this, 800 + random.nextInt(2000));
                }
            }
        };

        flickerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFailingNeon) {
                    // Phase 2: random opacity jumps between 80% (0.8) and 100% (1.0)
                    float alpha = 0.8f + (random.nextFloat() * 0.2f);
                    setAlpha(alpha);
                    // Flicker very fast, every 40-100ms
                    handler.postDelayed(this, 40 + random.nextInt(60));
                }
            }
        };
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (glitchOffsetX != 0 || glitchOffsetY != 0) {
            int originalColor = getCurrentTextColor();
            
            // Draw Cyan layer (shifted)
            canvas.save();
            canvas.translate(-glitchOffsetX, -glitchOffsetY);
            setTextColor(cyanColor);
            super.onDraw(canvas);
            canvas.restore();

            // Draw Magenta layer (shifted)
            canvas.save();
            canvas.translate(glitchOffsetX, glitchOffsetY);
            setTextColor(magentaColor);
            super.onDraw(canvas);
            canvas.restore();

            // Restore original text color for the main layer
            setTextColor(originalColor);
        }

        // Draw normal text on top
        super.onDraw(canvas);
    }

    // ─── Public API for protection-status display (used by MainActivity) ─────

    /** Phase 1+2+3 reveal: drop + elastic bounce + neon flicker + chromatic glitch */
    public void startRevealAnimation() {
        stopAnimations();
        setVisibility(VISIBLE);
        setAlpha(1f);

        setTranslationY(-500f);

        ObjectAnimator dropAnim = ObjectAnimator.ofFloat(this, "translationY", -500f, 0f);
        dropAnim.setDuration(1200);
        dropAnim.setInterpolator(new BounceInterpolator());

        isGlitching = true;
        glitchOffsetX = 15f;
        glitchOffsetY = 15f;

        dropAnim.start();

        handler.postDelayed(() -> {
            glitchOffsetX = 0;
            glitchOffsetY = 0;
            invalidate();

            handler.postDelayed(glitchRunnable, 200);

            isFailingNeon = true;
            handler.post(flickerRunnable);

        }, 1200);
    }

    /** Start the periodic chromatic-glitch loop (used on Protection Inactive). */
    public void startGlitchEffect() {
        if (isGlitching) return;
        isGlitching = true;
        handler.post(glitchRunnable);
    }

    /** Stop the chromatic-glitch loop. */
    public void stopGlitchEffect() {
        isGlitching = false;
        handler.removeCallbacks(glitchRunnable);
        glitchOffsetX = 0f;
        glitchOffsetY = 0f;
        invalidate();
    }

    /**
     * Play a brief "scan beam" animation (text sweeps from dim → bright),
     * then invokes {@code onComplete} so callers can chain the next effect.
     */
    public void startScanBeam(Runnable onComplete) {
        setAlpha(0.4f);
        ObjectAnimator scanAnim = ObjectAnimator.ofFloat(this, "alpha", 0.4f, 1f);
        scanAnim.setDuration(600);
        scanAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        scanAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (onComplete != null) onComplete.run();
            }
        });
        scanAnim.start();
    }

    /** Start a slow alpha pulse that mimics a cursor blink (active state). */
    public void startCursorBlink() {
        if (isFailingNeon) return;
        isFailingNeon = true;
        handler.post(flickerRunnable);
    }

    /** Stop the cursor blink / neon flicker. */
    public void stopCursorBlink() {
        isFailingNeon = false;
        handler.removeCallbacks(flickerRunnable);
        setAlpha(1f);
    }

    /** Stop all running animations and reset to neutral state. */
    public void stopAnimations() {
        isGlitching = false;
        isFailingNeon = false;
        if (handler != null) {
            handler.removeCallbacks(glitchRunnable);
            handler.removeCallbacks(flickerRunnable);
            handler.removeCallbacksAndMessages(null);
        }
        setAlpha(1f);
        setTranslationY(0f);
        glitchOffsetX = 0f;
        glitchOffsetY = 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimations();
    }
}

