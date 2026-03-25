package com.oppo.postureguard.training

import kotlin.math.abs
import kotlin.math.min

/**
 * Lightweight FastDTW-style aligner.
 *
 * For our window sizes (e.g. 120 x 160) a banded DTW is already fast enough,
 * but this implementation keeps the "FastDTW" story: we recursively downsample,
 * then refine within an expanded window.
 */
object FastDtwAligner {

    data class Result(
        val bestCoachIndexInSearch: Int,
        val cost: Float
    )

    fun alignToBestEnd(
        user: List<PoseFeatureExtractor.PoseFeature>,
        coachSearch: List<PoseFeatureExtractor.PoseFeature>,
        bodyMode: BodyMode,
        radius: Int = 10
    ): Result {
        if (user.isEmpty() || coachSearch.isEmpty()) return Result(0, Float.POSITIVE_INFINITY)

        // Small enough: do a direct banded DTW.
        val minSize = 32
        if (min(user.size, coachSearch.size) <= minSize) {
            return dtwBestEndBanded(user, coachSearch, bodyMode, band = radius * 2)
        }

        // Downsample by 2.
        val userCoarse = downsample(user)
        val coachCoarse = downsample(coachSearch)

        // Recurse to get a coarse path.
        val coarse = alignToBestEnd(userCoarse, coachCoarse, bodyMode, radius)

        // Build a narrow band around the projected best end.
        // We don't need the full path for our online cursor update; a centered band is enough.
        val projectedEnd = coarse.bestCoachIndexInSearch * 2
        val band = maxOf(10, radius * 4)

        return dtwBestEndBanded(user, coachSearch, bodyMode, band = band, centerJ = projectedEnd)
    }

    private fun downsample(seq: List<PoseFeatureExtractor.PoseFeature>): List<PoseFeatureExtractor.PoseFeature> {
        if (seq.size <= 1) return seq
        val out = ArrayList<PoseFeatureExtractor.PoseFeature>((seq.size + 1) / 2)
        var i = 0
        while (i < seq.size) {
            out.add(seq[i])
            i += 2
        }
        return out
    }

    private fun dtwBestEndBanded(
        user: List<PoseFeatureExtractor.PoseFeature>,
        coach: List<PoseFeatureExtractor.PoseFeature>,
        bodyMode: BodyMode,
        band: Int,
        centerJ: Int? = null
    ): Result {
        val n = user.size
        val m = coach.size

        // dp[i][j] = best cost up to (i,j)
        val inf = 1e9f
        val dp = Array(n) { FloatArray(m) { inf } }

        fun inBand(i: Int, j: Int): Boolean {
            val cj = centerJ
            return if (cj == null) {
                abs(i - j) <= band
            } else {
                // Center the band around a coach index guess.
                abs(j - (cj + (i - (n - 1)))) <= band
            }
        }

        // init
        for (j in 0 until m) {
            if (!inBand(0, j)) continue
            val d = PoseSimilarity.distance(user[0], coach[j], bodyMode)
            dp[0][j] = d
            if (j > 0 && dp[0][j - 1] < inf) {
                dp[0][j] = min(dp[0][j], dp[0][j - 1] + d)
            }
        }

        for (i in 1 until n) {
            for (j in 0 until m) {
                if (!inBand(i, j)) continue
                val d = PoseSimilarity.distance(user[i], coach[j], bodyMode)
                var bestPrev = dp[i - 1][j]
                if (j > 0) bestPrev = min(bestPrev, dp[i][j - 1])
                if (j > 0) bestPrev = min(bestPrev, dp[i - 1][j - 1])
                if (bestPrev >= inf) continue
                dp[i][j] = bestPrev + d
            }
        }

        // Best coach index for the user's last frame.
        val lastI = n - 1
        var bestJ = 0
        var bestCost = inf
        for (j in 0 until m) {
            val c = dp[lastI][j]
            if (c < bestCost) {
                bestCost = c
                bestJ = j
            }
        }

        return Result(bestCoachIndexInSearch = bestJ, cost = bestCost)
    }
}