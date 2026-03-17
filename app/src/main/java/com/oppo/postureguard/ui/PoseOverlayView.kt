package com.oppo.postureguard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.oppo.postureguard.R

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val frameRect = RectF()
    private var showFrame = true
    private var inFrame = false
    private var showSkeleton = true
    private var points: FloatArray? = null

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.outline)
    }

    private val frameOkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ContextCompat.getColor(context, android.R.color.white)
        alpha = 180
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 220
    }

    fun setTargetFrameFraction(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        frameRect.set(left, top, right, bottom)
        invalidate()
    }

    fun setFrameVisible(visible: Boolean) {
        showFrame = visible
        invalidate()
    }

    fun setSkeletonVisible(visible: Boolean) {
        showSkeleton = visible
        invalidate()
    }

    fun updatePose(pointsNorm: FloatArray?, inFrame: Boolean) {
        this.points = pointsNorm
        this.inFrame = inFrame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (showFrame) {
            val rect = RectF(
                frameRect.left * w,
                frameRect.top * h,
                frameRect.right * w,
                frameRect.bottom * h
            )
            canvas.drawRoundRect(rect, 24f, 24f, if (inFrame) frameOkPaint else framePaint)
        }

        if (!showSkeleton) return

        val pts = points ?: return
        if (pts.size < 2) return

        // Avoid name clash with View.x/View.y (Float properties).
        val px: (Int) -> Float = { i -> pts[i * 2] * w }
        val py: (Int) -> Float = { i -> pts[i * 2 + 1] * h }

        // Connections (subset) for readable skeleton.
        drawBone(canvas, px, py, 11, 12)
        drawBone(canvas, px, py, 11, 13)
        drawBone(canvas, px, py, 13, 15)
        drawBone(canvas, px, py, 12, 14)
        drawBone(canvas, px, py, 14, 16)
        drawBone(canvas, px, py, 11, 23)
        drawBone(canvas, px, py, 12, 24)
        drawBone(canvas, px, py, 23, 24)
        drawBone(canvas, px, py, 23, 25)
        drawBone(canvas, px, py, 25, 27)
        drawBone(canvas, px, py, 24, 26)
        drawBone(canvas, px, py, 26, 28)
        drawBone(canvas, px, py, 27, 28)
        drawBone(canvas, px, py, 0, 11)
        drawBone(canvas, px, py, 0, 12)

        // Draw joints.
        val jointRadius = 7f
        val count = pts.size / 2
        for (i in 0 until count) {
            val cx = pts[i * 2] * w
            val cy = pts[i * 2 + 1] * h
            canvas.drawCircle(cx, cy, jointRadius, jointPaint)
        }
    }

    private fun drawBone(
        canvas: Canvas,
        x: (Int) -> Float,
        y: (Int) -> Float,
        a: Int,
        b: Int
    ) {
        // Some devices may not return full landmark count; guard indices.
        val pts = points ?: return
        val count = pts.size / 2
        if (a >= count || b >= count) return
        canvas.drawLine(x(a), y(a), x(b), y(b), bonePaint)
    }
}

