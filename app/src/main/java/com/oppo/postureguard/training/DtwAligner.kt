package com.oppo.postureguard.training

import kotlin.math.abs
import kotlin.math.min

/**
 * Lightweight matrix-based DTW suitable for small windows on-device.
 *
 * We align user feature window (N) to a coach search window (M) with a band constraint.
 */
object DtwAligner {

    data class Result(
        val bestCoachIndexInSearch: Int,
        val cost: Float
    )

    /**
     * @param userSeq size N, ordered oldest->newest
     * @param coachSeq size M, ordered oldest->newest (search window)
     * @param band Sakoe-Chiba band radius in indices; if <=0, uses full matrix.
     */
    fun alignToBestEnd(
        userSeq: List<PoseFeatureExtractor.PoseFeature>,
        coachSeq: List<PoseFeatureExtractor.PoseFeature>,
        bodyMode: BodyMode,
        band: Int = 40
    ): Result {
        val n = userSeq.size
        val m = coachSeq.size
        if (n == 0 || m == 0) return Result(0, 1f)

        // Use two rows to save memory.
        val inf = 1e9f
        var prev = FloatArray(m) { inf }
        var cur = FloatArray(m) { inf }

        fun dist(i: Int, j: Int): Float {
            return PoseSimilarity.distance(userSeq[i], coachSeq[j], bodyMode)
        }

        for (i in 0 until n) {
            java.util.Arrays.fill(cur, inf)
            val jStart = if (band > 0) maxOf(0, i - band) else 0
            val jEnd = if (band > 0) min(m - 1, i + band) else (m - 1)

            for (j in jStart..jEnd) {
                val d = dist(i, j)
                if (i == 0 && j == 0) {
                    cur[j] = d
                    continue
                }
                val bestPrev = minOf(
                    if (j > 0) cur[j - 1] else inf,
                    prev[j],
                    if (j > 0) prev[j - 1] else inf
                )
                cur[j] = d + bestPrev
            }

            val tmp = prev
            prev = cur
            cur = tmp
        }

        // Choose best ending j for i=n-1.
        var bestJ = 0
        var best = prev[0]
        for (j in 1 until m) {
            val v = prev[j]
            if (v < best) {
                best = v
                bestJ = j
            }
        }

        // Normalize cost roughly by path length.
        val norm = best / (n + m).toFloat().coerceAtLeast(1f)
        return Result(bestJ, norm)
    }
}