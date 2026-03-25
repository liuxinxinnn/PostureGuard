package com.oppo.postureguard.training

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object PoseAligner {
    // MediaPipe Pose landmark indices.
    private const val NOSE = 0
    private const val L_SHOULDER = 11
    private const val R_SHOULDER = 12
    private const val L_ELBOW = 13
    private const val R_ELBOW = 14
    private const val L_WRIST = 15
    private const val R_WRIST = 16
    private const val L_HIP = 23
    private const val R_HIP = 24
    private const val L_KNEE = 25
    private const val R_KNEE = 26
    private const val L_ANKLE = 27
    private const val R_ANKLE = 28

    data class SimilarityTransform(
        val scale: Float,
        val cos: Float,
        val sin: Float,
        val tx: Float,
        val ty: Float
    ) {
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val nx = scale * (cos * x - sin * y) + tx
            val ny = scale * (sin * x + cos * y) + ty
            return nx to ny
        }
    }

    fun isInFrame(points: FloatArray, frame: FrameRect, bodyMode: BodyMode): Boolean {
        val count = points.size / 2
        fun p(i: Int): Pair<Float, Float>? {
            if (i < 0 || i >= count) return null
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            if (x !in 0f..1f || y !in 0f..1f) return null
            return x to y
        }

        val margin = if (bodyMode == BodyMode.FULL_BODY) 0.18f else 0.28f
        fun inside(pt: Pair<Float, Float>): Boolean {
            return pt.first in (frame.left - margin)..(frame.right + margin) &&
                pt.second in (frame.top - margin)..(frame.bottom + margin)
        }

        val nose = p(NOSE) ?: return false
        val ls = p(L_SHOULDER) ?: return false
        val rs = p(R_SHOULDER) ?: return false
        if (!inside(nose) || !inside(ls) || !inside(rs)) return false

        val shoulderWidth = abs(ls.first - rs.first)
        if (shoulderWidth < 0.06f) return false

        if (bodyMode == BodyMode.UPPER_BODY) return true

        // FULL_BODY: require hips at least, knees/ankles best-effort.
        val lh = p(L_HIP) ?: return false
        val rh = p(R_HIP) ?: return false
        if (!inside(lh) || !inside(rh)) return false

        val lk = p(L_KNEE)
        val rk = p(R_KNEE)
        val la = p(L_ANKLE)
        val ra = p(R_ANKLE)
        // Make it lenient: if they exist, they should be roughly in frame.
        val extrasOk = listOfNotNull(lk, rk, la, ra).all { inside(it) }
        return extrasOk
    }

    fun computeTransform(user: FloatArray, coach: FloatArray, bodyMode: BodyMode): SimilarityTransform? {
        fun get(arr: FloatArray, i: Int): Pair<Float, Float>? {
            val count = arr.size / 2
            if (i < 0 || i >= count) return null
            val x = arr[i * 2]
            val y = arr[i * 2 + 1]
            if (x !in 0f..1f || y !in 0f..1f) return null
            return x to y
        }

        val uLS = get(user, L_SHOULDER) ?: return null
        val uRS = get(user, R_SHOULDER) ?: return null
        val cLS = get(coach, L_SHOULDER) ?: return null
        val cRS = get(coach, R_SHOULDER) ?: return null

        val uCx = (uLS.first + uRS.first) * 0.5f
        val uCy = (uLS.second + uRS.second) * 0.5f
        val cCx = (cLS.first + cRS.first) * 0.5f
        val cCy = (cLS.second + cRS.second) * 0.5f

        val uVx = (uRS.first - uLS.first)
        val uVy = (uRS.second - uLS.second)
        val cVx = (cRS.first - cLS.first)
        val cVy = (cRS.second - cLS.second)

        val uLen = hypot(uVx, uVy).coerceAtLeast(1e-4f)
        val cLen = hypot(cVx, cVy).coerceAtLeast(1e-4f)

        val uAngle = atan2(uVy, uVx)
        val cAngle = atan2(cVy, cVx)
        val theta = uAngle - cAngle
        val ct = cos(theta).toFloat()
        val st = sin(theta).toFloat()

        var scale = (uLen / cLen).coerceIn(0.4f, 3.0f)

        if (bodyMode == BodyMode.FULL_BODY) {
            val uLH = get(user, L_HIP)
            val uRH = get(user, R_HIP)
            val cLH = get(coach, L_HIP)
            val cRH = get(coach, R_HIP)
            if (uLH != null && uRH != null && cLH != null && cRH != null) {
                val uHx = (uLH.first + uRH.first) * 0.5f
                val uHy = (uLH.second + uRH.second) * 0.5f
                val cHx = (cLH.first + cRH.first) * 0.5f
                val cHy = (cLH.second + cRH.second) * 0.5f
                val uTorso = hypot(uHx - uCx, uHy - uCy).coerceAtLeast(1e-4f)
                val cTorso = hypot(cHx - cCx, cHy - cCy).coerceAtLeast(1e-4f)
                val torsoScale = (uTorso / cTorso).coerceIn(0.4f, 3.0f)
                scale = (scale * 0.6f + torsoScale * 0.4f)
            }
        }

        // Translation that maps coach shoulder center to user shoulder center.
        val mappedCCx = scale * (ct * cCx - st * cCy)
        val mappedCCy = scale * (st * cCx + ct * cCy)
        val tx = uCx - mappedCCx
        val ty = uCy - mappedCCy

        return SimilarityTransform(scale, ct, st, tx, ty)
    }

    fun computePoseError(user: FloatArray, coach: FloatArray, tf: SimilarityTransform, bodyMode: BodyMode): Float {
        val joints = when (bodyMode) {
            BodyMode.UPPER_BODY -> intArrayOf(NOSE, L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP)
            BodyMode.FULL_BODY -> intArrayOf(NOSE, L_SHOULDER, R_SHOULDER, L_ELBOW, R_ELBOW, L_WRIST, R_WRIST, L_HIP, R_HIP, L_KNEE, R_KNEE, L_ANKLE, R_ANKLE)
        }

        fun get(arr: FloatArray, i: Int): Pair<Float, Float>? {
            val count = arr.size / 2
            if (i < 0 || i >= count) return null
            val x = arr[i * 2]
            val y = arr[i * 2 + 1]
            if (x !in 0f..1f || y !in 0f..1f) return null
            return x to y
        }

        val ls = get(user, L_SHOULDER)
        val rs = get(user, R_SHOULDER)
        val norm = if (ls != null && rs != null) {
            hypot(ls.first - rs.first, ls.second - rs.second).coerceAtLeast(1e-3f)
        } else {
            0.25f
        }

        var sum = 0f
        var n = 0
        for (idx in joints) {
            val u = get(user, idx) ?: continue
            val c = get(coach, idx) ?: continue
            val (cx, cy) = tf.map(c.first, c.second)
            val d = hypot(u.first - cx, u.second - cy)
            sum += d
            n++
        }
        if (n == 0) return 999f
        return (sum / n.toFloat()) / norm
    }

    fun deviationHint(user: FloatArray, coach: FloatArray, tf: SimilarityTransform): String {
        fun get(arr: FloatArray, i: Int): Pair<Float, Float>? {
            val count = arr.size / 2
            if (i < 0 || i >= count) return null
            val x = arr[i * 2]
            val y = arr[i * 2 + 1]
            if (x !in 0f..1f || y !in 0f..1f) return null
            return x to y
        }

        // Use shoulders for a stable & intuitive hint.
        val uLS = get(user, L_SHOULDER) ?: return ""
        val uRS = get(user, R_SHOULDER) ?: return ""
        val cLS = get(coach, L_SHOULDER) ?: return ""
        val cRS = get(coach, R_SHOULDER) ?: return ""

        val (cLSx, cLSy) = tf.map(cLS.first, cLS.second)
        val (cRSx, cRSy) = tf.map(cRS.first, cRS.second)

        val dLy = uLS.second - cLSy
        val dRy = uRS.second - cRSy

        val upDown = when {
            dLy < -0.03f && dRy < -0.03f -> "双肩偏高"
            dLy > 0.03f && dRy > 0.03f -> "双肩偏低"
            abs(dLy - dRy) > 0.05f -> if (dLy < dRy) "左肩偏高" else "右肩偏高"
            else -> ""
        }
        return upDown
    }

    fun poseToSilhouette(points: FloatArray, size: Int = 256, alpha: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0x00000000)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.035f).coerceAtLeast(10f)
            color = 0xFFFFFFFF.toInt()
            this.alpha = alpha
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val count = points.size / 2
        fun x(i: Int) = points[i * 2] * size.toFloat()
        fun y(i: Int) = points[i * 2 + 1] * size.toFloat()
        fun ok(i: Int) = i in 0 until count && points[i * 2] in 0f..1f && points[i * 2 + 1] in 0f..1f

        val bones = arrayOf(
            intArrayOf(L_SHOULDER, R_SHOULDER),
            intArrayOf(L_SHOULDER, L_ELBOW),
            intArrayOf(L_ELBOW, L_WRIST),
            intArrayOf(R_SHOULDER, R_ELBOW),
            intArrayOf(R_ELBOW, R_WRIST),
            intArrayOf(L_SHOULDER, L_HIP),
            intArrayOf(R_SHOULDER, R_HIP),
            intArrayOf(L_HIP, R_HIP),
            intArrayOf(L_HIP, L_KNEE),
            intArrayOf(L_KNEE, L_ANKLE),
            intArrayOf(R_HIP, R_KNEE),
            intArrayOf(R_KNEE, R_ANKLE),
            intArrayOf(NOSE, L_SHOULDER),
            intArrayOf(NOSE, R_SHOULDER)
        )
        for (b in bones) {
            val a = b[0]
            val c = b[1]
            if (!ok(a) || !ok(c)) continue
            canvas.drawLine(x(a), y(a), x(c), y(c), paint)
        }

        // Thicken by drawing once more with slight alpha.
        paint.alpha = (alpha * 0.75f).toInt().coerceIn(0, 255)
        paint.strokeWidth = paint.strokeWidth * 0.85f
        for (b in bones) {
            val a = b[0]
            val c = b[1]
            if (!ok(a) || !ok(c)) continue
            canvas.drawLine(x(a), y(a), x(c), y(c), paint)
        }

        return bmp
    }

    fun tintMask(src: Bitmap, color: Int, alpha: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.alpha = alpha
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, 0f, 0f, p)
        return out
    }

    fun frameRectPx(frame: FrameRect, w: Float, h: Float): RectF {
        return RectF(frame.left * w, frame.top * h, frame.right * w, frame.bottom * h)
    }

    fun clamp01(v: Float): Float = min(1f, max(0f, v))

    fun computeMaskIoU(
        userMask: Bitmap,
        coachMask: Bitmap,
        tf: SimilarityTransform,
        alphaThreshold: Int = 32
    ): Float {
        val size = min(userMask.width, userMask.height).coerceAtLeast(1)
        if (size <= 1) return 0f

        val coach = if (coachMask.width != size || coachMask.height != size) {
            Bitmap.createScaledBitmap(coachMask, size, size, true)
        } else coachMask
        val user = if (userMask.width != size || userMask.height != size) {
            Bitmap.createScaledBitmap(userMask, size, size, true)
        } else userMask

        val warped = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(warped)
        canvas.drawColor(0x00000000)

        val a = tf.scale * tf.cos
        val b = -tf.scale * tf.sin
        val c = tf.scale * tf.sin
        val d = tf.scale * tf.cos
        val tx = tf.tx * size.toFloat()
        val ty = tf.ty * size.toFloat()

        val m = android.graphics.Matrix()
        m.setValues(floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f))

        canvas.save()
        canvas.concat(m)
        canvas.drawBitmap(coach, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.restore()

        val userPx = IntArray(size * size)
        val coachPx = IntArray(size * size)
        user.getPixels(userPx, 0, size, 0, 0, size, size)
        warped.getPixels(coachPx, 0, size, 0, 0, size, size)

        var inter = 0
        var union = 0
        for (i in 0 until size * size) {
            val ua = (userPx[i] ushr 24) and 0xFF
            val ca = (coachPx[i] ushr 24) and 0xFF
            val uOn = ua >= alphaThreshold
            val cOn = ca >= alphaThreshold
            if (uOn && cOn) inter++
            if (uOn || cOn) union++
        }
        if (union == 0) return 0f
        return inter.toFloat() / union.toFloat()
    }
}
