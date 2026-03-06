package com.dearmoon.shield.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * OrnamentsHeaderView — draws a decorative curved line with a centre diamond dot.
 * Two bezier arcs curve upward from centre, meeting at a white circle.
 * Height: 24dp. Width: match_parent.
 */
class OrnamentsHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8892A4")
        style = Paint.Style.STROKE
        strokeWidth = 1f.dpToPx()
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        val spread = 100f.dpToPx()
        val gap    = 20f.dpToPx()
        val lift   = 8f.dpToPx()

        // Left curved arc
        val leftPath = Path().apply {
            moveTo(cx - gap, cy)
            quadTo(cx - 60f.dpToPx(), cy - lift, cx - spread, cy)
        }
        canvas.drawPath(leftPath, linePaint)

        // Right curved arc (mirror)
        val rightPath = Path().apply {
            moveTo(cx + gap, cy)
            quadTo(cx + 60f.dpToPx(), cy - lift, cx + spread, cy)
        }
        canvas.drawPath(rightPath, linePaint)

        // Centre diamond/dot
        canvas.drawCircle(cx, cy, 3f.dpToPx(), dotPaint)
    }

    private fun Float.dpToPx(): Float =
        this * resources.displayMetrics.density
}
