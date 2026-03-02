package com.dearmoon.shield.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a full-screen dark overlay with a transparent rounded-rect "spotlight"
 * cut out over the highlighted view, plus a glowing neon border around it.
 */
public class HighlightOverlayView extends View {

    private final Paint dimPaint = new Paint();
    private final Paint clearPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF spotRect;
    private float cornerRadius = 24f;

    public HighlightOverlayView(Context context) {
        super(context);
        init();
    }

    public HighlightOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        // Dark overlay
        dimPaint.setColor(0xCC000000);
        dimPaint.setStyle(Paint.Style.FILL);

        // Punch hole
        clearPaint.setColor(Color.TRANSPARENT);
        clearPaint.setStyle(Paint.Style.FILL);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        // Neon cyan inner border
        borderPaint.setColor(0xFF00E5FF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);

        // Neon purple outer glow
        borderGlowPaint.setColor(0xAAD500F9);
        borderGlowPaint.setStyle(Paint.Style.STROKE);
        borderGlowPaint.setStrokeWidth(12f);
        borderGlowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(18f, android.graphics.BlurMaskFilter.Blur.OUTER));
    }

    /** Highlight position given in the view's own coordinate space (already mapped by caller). */
    public void setSpotRect(RectF rectInThisViewSpace, float corner) {
        this.spotRect = rectInThisViewSpace;
        this.cornerRadius = corner;
        invalidate();
    }

    public void clearSpot() {
        spotRect = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Full dim
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);

        if (spotRect != null) {
            // Punch clear hole
            canvas.drawRoundRect(spotRect, cornerRadius, cornerRadius, clearPaint);
            // Glow border
            canvas.drawRoundRect(spotRect, cornerRadius, cornerRadius, borderGlowPaint);
            // Sharp neon border
            canvas.drawRoundRect(spotRect, cornerRadius, cornerRadius, borderPaint);
        }
    }
}
