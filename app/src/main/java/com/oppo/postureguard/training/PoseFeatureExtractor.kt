package com.oppo.postureguard.training

import java.util.BitSet
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Extracts a fixed-length feature vector from MediaPipe Pose landmarks.
 *
 * We avoid comparing raw coordinates. Instead we compare:
 * - Bone direction unit vectors (3D)
 * - Joint angles (radians)
 */
object PoseFeatureExtractor {

    // Landmark indices.
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

    // Feature layout (fixed for both modes):
    // 11 bone vectors * 3 = 33 floats
    // 7 angles = 7 floats
    // Total = 40 floats
    const val FEATURE_SIZE = 40

    enum class FeatureId(val idx: Int) {
        // Bone unit vectors (each consumes 3 floats)
        L_UPPER_ARM(0),
        R_UPPER_ARM(1),
        L_FOREARM(2),
        R_FOREARM(3),
        TORSO(4),
        SHOULDER_LINE(5),
        L_THIGH(6),
        R_THIGH(7),
        L_SHIN(8),
        R_SHIN(9),
        HIP_LINE(10),

        // Angles (each consumes 1 float) base offset 33
        L_ELBOW_ANGLE(33),
        R_ELBOW_ANGLE(34),
        L_SHOULDER_ELEV(35),
        R_SHOULDER_ELEV(36),
        L_KNEE_ANGLE(37),
        R_KNEE_ANGLE(38),
        HIP_HINGE(39)
    }

    data class PoseFeature(
        val vec: FloatArray,
        val missing: BitSet
    )

    /**
     * @param landmarks FloatArray sized 33*4: [x,y,z,vis] repeated.
     */
    fun extract(landmarks: FloatArray, bodyMode: BodyMode): PoseFeature {
        val out = FloatArray(FEATURE_SIZE)
        val miss = BitSet(FEATURE_SIZE)

        // Helper to read landmarks.
        fun lm(i: Int): FloatArray? {
            val base = i * 4
            if (base + 3 >= landmarks.size) return null
            val x = landmarks[base]
            val y = landmarks[base + 1]
            val z = landmarks[base + 2]
            val v = landmarks[base + 3]
            val ok = x in 0f..1f && y in 0f..1f && v >= 0.5f
            if (!ok) return null
            return floatArrayOf(x, y, z)
        }

        fun center(a: Int, b: Int): FloatArray? {
            val la = lm(a) ?: return null
            val lb = lm(b) ?: return null
            return floatArrayOf(
                (la[0] + lb[0]) * 0.5f,
                (la[1] + lb[1]) * 0.5f,
                (la[2] + lb[2]) * 0.5f
            )
        }

        fun unit(from: Int, to: Int, featureIndex: Int) {
            val a = lm(from)
            val b = lm(to)
            if (a == null || b == null) {
                miss.set(featureIndex * 3)
                miss.set(featureIndex * 3 + 1)
                miss.set(featureIndex * 3 + 2)
                return
            }
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val dz = b[2] - a[2]
            val n = sqrt(dx * dx + dy * dy + dz * dz)
            if (n < 1e-5f) {
                miss.set(featureIndex * 3)
                miss.set(featureIndex * 3 + 1)
                miss.set(featureIndex * 3 + 2)
                return
            }
            out[featureIndex * 3] = dx / n
            out[featureIndex * 3 + 1] = dy / n
            out[featureIndex * 3 + 2] = dz / n
        }

        fun unitFromPoints(a: FloatArray?, b: FloatArray?, featureIndex: Int) {
            if (a == null || b == null) {
                miss.set(featureIndex * 3)
                miss.set(featureIndex * 3 + 1)
                miss.set(featureIndex * 3 + 2)
                return
            }
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val dz = b[2] - a[2]
            val n = sqrt(dx * dx + dy * dy + dz * dz)
            if (n < 1e-5f) {
                miss.set(featureIndex * 3)
                miss.set(featureIndex * 3 + 1)
                miss.set(featureIndex * 3 + 2)
                return
            }
            out[featureIndex * 3] = dx / n
            out[featureIndex * 3 + 1] = dy / n
            out[featureIndex * 3 + 2] = dz / n
        }

        fun angle(a: Int, b: Int, c: Int, outIndex: Int) {
            // Angle at b: between (a-b) and (c-b)
            val pa = lm(a)
            val pb = lm(b)
            val pc = lm(c)
            if (pa == null || pb == null || pc == null) {
                miss.set(outIndex)
                return
            }
            val v1x = pa[0] - pb[0]
            val v1y = pa[1] - pb[1]
            val v1z = pa[2] - pb[2]
            val v2x = pc[0] - pb[0]
            val v2y = pc[1] - pb[1]
            val v2z = pc[2] - pb[2]
            val n1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
            val n2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
            if (n1 < 1e-5f || n2 < 1e-5f) {
                miss.set(outIndex)
                return
            }
            val dot = (v1x * v2x + v1y * v2y + v1z * v2z) / (n1 * n2)
            val clamped = dot.coerceIn(-1f, 1f)
            out[outIndex] = acos(clamped)
        }

        // Bone vectors.
        unit(L_SHOULDER, L_ELBOW, FeatureId.L_UPPER_ARM.idx)
        unit(R_SHOULDER, R_ELBOW, FeatureId.R_UPPER_ARM.idx)
        unit(L_ELBOW, L_WRIST, FeatureId.L_FOREARM.idx)
        unit(R_ELBOW, R_WRIST, FeatureId.R_FOREARM.idx)

        val hipC = center(L_HIP, R_HIP)
        val shC = center(L_SHOULDER, R_SHOULDER)
        unitFromPoints(hipC, shC, FeatureId.TORSO.idx)
        unit(L_SHOULDER, R_SHOULDER, FeatureId.SHOULDER_LINE.idx)

        // Legs.
        unit(L_HIP, L_KNEE, FeatureId.L_THIGH.idx)
        unit(R_HIP, R_KNEE, FeatureId.R_THIGH.idx)
        unit(L_KNEE, L_ANKLE, FeatureId.L_SHIN.idx)
        unit(R_KNEE, R_ANKLE, FeatureId.R_SHIN.idx)
        unit(L_HIP, R_HIP, FeatureId.HIP_LINE.idx)

        // Angles.
        angle(L_SHOULDER, L_ELBOW, L_WRIST, FeatureId.L_ELBOW_ANGLE.idx)
        angle(R_SHOULDER, R_ELBOW, R_WRIST, FeatureId.R_ELBOW_ANGLE.idx)
        // Shoulder elevation: angle at shoulder between torso direction and upper arm.
        // We approximate by projecting to 2D plane (x,y) for stability.
        out[FeatureId.L_SHOULDER_ELEV.idx] = shoulderElevation2D(landmarks, isLeft = true) ?: run { miss.set(FeatureId.L_SHOULDER_ELEV.idx); 0f }
        out[FeatureId.R_SHOULDER_ELEV.idx] = shoulderElevation2D(landmarks, isLeft = false) ?: run { miss.set(FeatureId.R_SHOULDER_ELEV.idx); 0f }

        angle(L_HIP, L_KNEE, L_ANKLE, FeatureId.L_KNEE_ANGLE.idx)
        angle(R_HIP, R_KNEE, R_ANKLE, FeatureId.R_KNEE_ANGLE.idx)
        out[FeatureId.HIP_HINGE.idx] = hipHinge2D(landmarks) ?: run { miss.set(FeatureId.HIP_HINGE.idx); 0f }

        // If upper body mode, mark leg-related features as missing so they won't affect scoring.
        if (bodyMode == BodyMode.UPPER_BODY) {
            markVectorMissing(miss, FeatureId.L_THIGH.idx)
            markVectorMissing(miss, FeatureId.R_THIGH.idx)
            markVectorMissing(miss, FeatureId.L_SHIN.idx)
            markVectorMissing(miss, FeatureId.R_SHIN.idx)
            markVectorMissing(miss, FeatureId.HIP_LINE.idx)
            miss.set(FeatureId.L_KNEE_ANGLE.idx)
            miss.set(FeatureId.R_KNEE_ANGLE.idx)
            miss.set(FeatureId.HIP_HINGE.idx)
        }

        return PoseFeature(out, miss)
    }

    private fun markVectorMissing(miss: BitSet, vectorIndex: Int) {
        miss.set(vectorIndex * 3)
        miss.set(vectorIndex * 3 + 1)
        miss.set(vectorIndex * 3 + 2)
    }

    private fun shoulderElevation2D(landmarks: FloatArray, isLeft: Boolean): Float? {
        val shoulder = if (isLeft) L_SHOULDER else R_SHOULDER
        val elbow = if (isLeft) L_ELBOW else R_ELBOW
        val hip = if (isLeft) L_HIP else R_HIP

        fun p(i: Int): Pair<Float, Float>? {
            val base = i * 4
            if (base + 3 >= landmarks.size) return null
            val x = landmarks[base]
            val y = landmarks[base + 1]
            val v = landmarks[base + 3]
            if (x !in 0f..1f || y !in 0f..1f || v < 0.5f) return null
            return x to y
        }

        val s = p(shoulder) ?: return null
        val e = p(elbow) ?: return null
        val h = p(hip) ?: return null

        val torsoX = s.first - h.first
        val torsoY = s.second - h.second
        val armX = e.first - s.first
        val armY = e.second - s.second

        val nt = sqrt(torsoX * torsoX + torsoY * torsoY)
        val na = sqrt(armX * armX + armY * armY)
        if (nt < 1e-5f || na < 1e-5f) return null

        val dot = (torsoX * armX + torsoY * armY) / (nt * na)
        return acos(dot.coerceIn(-1f, 1f))
    }

    private fun hipHinge2D(landmarks: FloatArray): Float? {
        fun p(i: Int): Pair<Float, Float>? {
            val base = i * 4
            if (base + 3 >= landmarks.size) return null
            val x = landmarks[base]
            val y = landmarks[base + 1]
            val v = landmarks[base + 3]
            if (x !in 0f..1f || y !in 0f..1f || v < 0.5f) return null
            return x to y
        }
        val lh = p(L_HIP) ?: return null
        val rh = p(R_HIP) ?: return null
        val ls = p(L_SHOULDER) ?: return null
        val rs = p(R_SHOULDER) ?: return null
        val lk = p(L_KNEE) ?: return null
        val rk = p(R_KNEE) ?: return null

        val hipC = ((lh.first + rh.first) * 0.5f) to ((lh.second + rh.second) * 0.5f)
        val shC = ((ls.first + rs.first) * 0.5f) to ((ls.second + rs.second) * 0.5f)
        val kneeC = ((lk.first + rk.first) * 0.5f) to ((lk.second + rk.second) * 0.5f)

        val torsoX = shC.first - hipC.first
        val torsoY = shC.second - hipC.second
        val thighX = kneeC.first - hipC.first
        val thighY = kneeC.second - hipC.second

        val nt = sqrt(torsoX * torsoX + torsoY * torsoY)
        val na = sqrt(thighX * thighX + thighY * thighY)
        if (nt < 1e-5f || na < 1e-5f) return null

        val dot = (torsoX * thighX + torsoY * thighY) / (nt * na)
        return acos(dot.coerceIn(-1f, 1f))
    }
}