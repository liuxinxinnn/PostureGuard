package com.oppo.postureguard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.oppo.postureguard.R
import com.oppo.postureguard.training.FrameRect
import com.oppo.postureguard.training.PoseAligner

class HoloOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var targetFrame = FrameRect(0.18f, 0.12f, 0.82f, 0.92f)
    private var showFrame = true
    private var inFrame = false
    private var showSkeleton = true

    private var userPoints: FloatArray? = null
    private var coachPoints: FloatArray? = null

    private var userMask: android.graphics.Bitmap? = null
    private var coachMask: android.graphics.Bitmap? = null
    private var coachTf: PoseAligner.SimilarityTransform? = null

    private var waitingAlign = false
    private var passAlign = false

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

    private val frameDangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = ContextCompat.getColor(context, R.color.danger)
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = ContextCompat.getColor(context, android.R.color.white)
        alpha = 180
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent)
        alpha = 220
    }

    fun setTargetFrameFraction(left: Float, top: Float, right: Float, bottom: Float) {
        targetFrame = FrameRect(left, top, right, bottom)
        invalidate()
    }

    fun setTargetFrame(frame: FrameRect) {
        targetFrame = frame
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

    fun setAlignState(waiting: Boolean, pass: Boolean) {
        waitingAlign = waiting
        passAlign = pass
        invalidate()
    }

    fun updateUser(pointsNorm: FloatArray?, inFrame: Boolean, mask: android.graphics.Bitmap?) {
        userPoints = pointsNorm
        this.inFrame = inFrame
        userMask = mask
        invalidate()
    }

    fun updateCoach(pointsNorm: FloatArray?, mask: android.graphics.Bitmap?, tf: PoseAligner.SimilarityTransform?) {
        coachPoints = pointsNorm
        coachMask = mask
        coachTf = tf
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        // Coach silhouette (aligned) first.
        drawCoachMask(canvas, w, h)

        // User silhouette.
        userMask?.let { um ->
            canvas.drawBitmap(um, null, RectF(0f, 0f, w, h), maskPaint)
        }

        // Frame for setup.
        if (showFrame) {
            val rect = PoseAligner.frameRectPx(targetFrame, w, h)
            val paint = when {
                waitingAlign && !passAlign -> frameDangerPaint
                waitingAlign && passAlign -> frameOkPaint
                else -> if (inFrame) frameOkPaint else framePaint
            }
            canvas.drawRoundRect(rect, 24f, 24f, paint)
        }

        if (showSkeleton) {
            userPoints?.let { drawSkeleton(canvas, it, w, h) }

            // Draw coach keyframe skeleton faintly for debugging.
            coachPoints?.let { pts ->
                val prevAlpha = bonePaint.alpha
                bonePaint.alpha = 90
                drawSkeleton(canvas, pts, w, h)
                bonePaint.alpha = prevAlpha
            }
        }
    }

    private fun drawCoachMask(canvas: Canvas, w: Float, h: Float) {
        val cm = coachMask ?: return
        val tf = coachTf ?: return

        // Map coach mask pixels -> view pixels using the normalized similarity transform.
        val mw = cm.width.toFloat().coerceAtLeast(1f)
        val mh = cm.height.toFloat().coerceAtLeast(1f)

        val a = tf.scale * tf.cos * (w / mw)
        val b = -tf.scale * tf.sin * (w / mh)
        val c = tf.scale * tf.sin * (h / mw)
        val d = tf.scale * tf.cos * (h / mh)
        val tx = tf.tx * w
        val ty = tf.ty * h

        val m = Matrix()
        m.setValues(
            floatArrayOf(
                a, b, tx,
                c, d, ty,
                0f, 0f, 1f
            )
        )

        canvas.save()
        canvas.concat(m)
        canvas.drawBitmap(cm, 0f, 0f, maskPaint)
        canvas.restore()
    }

    private fun drawSkeleton(canvas: Canvas, pts: FloatArray, w: Float, h: Float) {
        if (pts.size < 2) return
        val count = pts.size / 2

        val px: (Int) -> Float = { i -> pts[i * 2] * w }
        val py: (Int) -> Float = { i -> pts[i * 2 + 1] * h }

        fun bone(a: Int, b: Int) {
            if (a >= count || b >= count) return
            val ax = pts[a * 2]
            val ay = pts[a * 2 + 1]
            val bx = pts[b * 2]
            val by = pts[b * 2 + 1]
            if (ax !in 0f..1f || ay !in 0f..1f || bx !in 0f..1f || by !in 0f..1f) return
            canvas.drawLine(px(a), py(a), px(b), py(b), bonePaint)
        }

        bone(11, 12)
        bone(11, 13)
        bone(13, 15)
        bone(12, 14)
        bone(14, 16)
        bone(11, 23)
        bone(12, 24)
        bone(23, 24)
        bone(23, 25)
        bone(25, 27)
        bone(24, 26)
        bone(26, 28)
        bone(27, 28)
        bone(0, 11)
        bone(0, 12)

        val jointRadius = 6f
        for (i in 0 until count) {
            val cx = pts[i * 2]
            val cy = pts[i * 2 + 1]
            if (cx !in 0f..1f || cy !in 0f..1f) continue
            canvas.drawCircle(cx * w, cy * h, jointRadius, jointPaint)
        }
    }
}
