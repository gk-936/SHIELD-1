package com.dearmoon.shield.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;
import androidx.appcompat.widget.AppCompatTextView;

public class DataLeakTextView extends AppCompatTextView {
    private ValueAnimator breathingAnimator;

    private static final float MIN_LETTER_SPACING = 0.1f; // 4px equivalent
    private static final float MAX_LETTER_SPACING = 0.2f; // 8px equivalent
    private static final float MIN_OPACITY = 0.6f;
    private static final float MAX_OPACITY = 1.0f;

    public DataLeakTextView(Context context) {
        super(context);
        init();
    }

    public DataLeakTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DataLeakTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Set initial state
        setLetterSpacing(MIN_LETTER_SPACING);
        setAlpha(MIN_OPACITY);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startBreathingAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
        }
    }

    private void startBreathingAnimation() {
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
        }

        breathingAnimator = ValueAnimator.ofFloat(0f, 1f);
        breathingAnimator.setDuration(6000); // 6 seconds for full cycle
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);

        // Cubic-bezier(0.4, 0, 0.2, 1) - Material Design standard easing
        breathingAnimator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));

        breathingAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();

            // Animate letter spacing from 4px to 8px
            float letterSpacing = MIN_LETTER_SPACING + (fraction * (MAX_LETTER_SPACING - MIN_LETTER_SPACING));
            setLetterSpacing(letterSpacing);

            // Animate opacity from 0.6 to 1.0
            float opacity = MIN_OPACITY + (fraction * (MAX_OPACITY - MIN_OPACITY));
            setAlpha(opacity);
        });

        breathingAnimator.start();
    }
}
