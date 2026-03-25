package com.oppo.postureguard.training

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

object PoseSimilarity {

    data class ErrorItem(
        val id: String,
        val label: String,
    )

    data class Score(
        val score0to100: Float,
        val errors: List<ErrorItem>,
    )

    private data class PartContribution(
        val id: String,
        val label: String,
        val dist: Float,
    )

    private const val SIGMA_ANGLE = 0.35f

    fun score(
        user: PoseFeatureExtractor.PoseFeature,
        coach: PoseFeatureExtractor.PoseFeature,
        bodyMode: BodyMode,
    ): Score {
        val u = user.vec
        val c = coach.vec
        val um = user.missing
        val cm = coach.missing

        val contrib = ArrayList<PartContribution>(16)

        fun vecSim(vecIndex: Int, weight: Float, id: String, label: String) {
            val base = vecIndex * 3
            if (um.get(base) || cm.get(base)) return
            val ux = u[base]
            val uy = u[base + 1]
            val uz = u[base + 2]
            val cx = c[base]
            val cy = c[base + 1]
            val cz = c[base + 2]
            val dot = ux * cx + uy * cy + uz * cz
            val sim = ((dot.coerceIn(-1f, 1f)) + 1f) * 0.5f
            val dist = (1f - sim) * weight
            contrib += PartContribution(id, label, dist)
        }

        fun angleScore(idx: Int, weight: Float, id: String, label: String) {
            if (um.get(idx) || cm.get(idx)) return
            val du = u[idx]
            val dc = c[idx]
            val d = abs(du - dc)
            val s = exp((-d / SIGMA_ANGLE).toDouble()).toFloat().coerceIn(0f, 1f)
            val dist = (1f - s) * weight
            contrib += PartContribution(id, label, dist)
        }

        // Upper body weights (normalize later).
        vecSim(PoseFeatureExtractor.FeatureId.L_UPPER_ARM.idx, 0.18f, "L_UPPER_ARM", "左上臂")
        vecSim(PoseFeatureExtractor.FeatureId.R_UPPER_ARM.idx, 0.18f, "R_UPPER_ARM", "右上臂")
        vecSim(PoseFeatureExtractor.FeatureId.L_FOREARM.idx, 0.18f, "L_FOREARM", "左前臂")
        vecSim(PoseFeatureExtractor.FeatureId.R_FOREARM.idx, 0.18f, "R_FOREARM", "右前臂")
        vecSim(PoseFeatureExtractor.FeatureId.TORSO.idx, 0.18f, "TORSO", "躯干")
        vecSim(PoseFeatureExtractor.FeatureId.SHOULDER_LINE.idx, 0.10f, "SHOULDER_LINE", "肩线")
        angleScore(PoseFeatureExtractor.FeatureId.L_ELBOW_ANGLE.idx, 0.09f, "L_ELBOW", "左肘")
        angleScore(PoseFeatureExtractor.FeatureId.R_ELBOW_ANGLE.idx, 0.09f, "R_ELBOW", "右肘")
        angleScore(PoseFeatureExtractor.FeatureId.L_SHOULDER_ELEV.idx, 0.07f, "L_SHOULDER", "左肩")
        angleScore(PoseFeatureExtractor.FeatureId.R_SHOULDER_ELEV.idx, 0.07f, "R_SHOULDER", "右肩")

        if (bodyMode == BodyMode.FULL_BODY) {
            vecSim(PoseFeatureExtractor.FeatureId.L_THIGH.idx, 0.10f, "L_THIGH", "左大腿")
            vecSim(PoseFeatureExtractor.FeatureId.R_THIGH.idx, 0.10f, "R_THIGH", "右大腿")
            vecSim(PoseFeatureExtractor.FeatureId.L_SHIN.idx, 0.08f, "L_SHIN", "左小腿")
            vecSim(PoseFeatureExtractor.FeatureId.R_SHIN.idx, 0.08f, "R_SHIN", "右小腿")
            vecSim(PoseFeatureExtractor.FeatureId.HIP_LINE.idx, 0.06f, "HIP_LINE", "髋线")
            angleScore(PoseFeatureExtractor.FeatureId.L_KNEE_ANGLE.idx, 0.05f, "L_KNEE", "左膝")
            angleScore(PoseFeatureExtractor.FeatureId.R_KNEE_ANGLE.idx, 0.05f, "R_KNEE", "右膝")
            angleScore(PoseFeatureExtractor.FeatureId.HIP_HINGE.idx, 0.05f, "HIP_HINGE", "髋")
        }

        val rawDist = contrib.sumOf { it.dist.toDouble() }.toFloat()
        val maxDist = maxPossibleDist(bodyMode)
        val normDist = (rawDist / maxDist).coerceIn(0f, 1f)
        val score = (1f - normDist) * 100f

        val top = contrib.sortedByDescending { it.dist }.take(3).map { ErrorItem(it.id, it.label) }
        return Score(score, top)
    }

    private fun maxPossibleDist(bodyMode: BodyMode): Float {
        // Sum of weights in score() above.
        var sum = 0.18f * 4 + 0.18f + 0.10f + 0.09f * 2 + 0.07f * 2
        if (bodyMode == BodyMode.FULL_BODY) {
            sum += 0.10f * 2 + 0.08f * 2 + 0.06f + 0.05f * 3
        }
        return sum.coerceAtLeast(1e-4f)
    }

    /** DTW distance between two features (0..1-ish). */
    fun distance(
        user: PoseFeatureExtractor.PoseFeature,
        coach: PoseFeatureExtractor.PoseFeature,
        bodyMode: BodyMode,
    ): Float {
        // Use 1 - (score/100) as a consistent metric.
        val s = score(user, coach, bodyMode).score0to100
        return (1f - (s / 100f)).coerceIn(0f, 1f)
    }
}
