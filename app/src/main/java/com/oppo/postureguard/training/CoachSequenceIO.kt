package com.oppo.postureguard.training

import android.content.res.AssetManager
import java.io.DataInputStream
import java.io.InputStream

/**
 * Reads optional coach sequences from assets.
 *
 * Binary format v1:
 * - int32 frames
 * - int32 stride
 * - float32[frames*stride]
 */
object CoachSequenceIO {

    fun tryLoadFeatures(assets: AssetManager, assetPath: String): List<PoseFeatureExtractor.PoseFeature>? {
        return runCatching {
            assets.open(assetPath).use { input ->
                DataInputStream(input).use { dis ->
                    val frames = dis.readInt().coerceAtLeast(0)
                    val stride = dis.readInt().coerceAtLeast(0)
                    if (frames <= 0 || stride != PoseFeatureExtractor.FEATURE_SIZE) return emptyList()

                    val list = ArrayList<PoseFeatureExtractor.PoseFeature>(frames)
                    for (i in 0 until frames) {
                        val vec = FloatArray(stride)
                        for (j in 0 until stride) {
                            vec[j] = dis.readFloat()
                        }
                        // No missing mask for precomputed features; assume all present.
                        list += PoseFeatureExtractor.PoseFeature(vec, java.util.BitSet())
                    }
                    list
                }
            }
        }.getOrNull()
    }

    fun tryLoadLandmarks(assets: AssetManager, assetPath: String): List<FloatArray>? {
        return runCatching {
            assets.open(assetPath).use { input ->
                DataInputStream(input).use { dis ->
                    val frames = dis.readInt().coerceAtLeast(0)
                    val stride = dis.readInt().coerceAtLeast(0)
                    val expected = 33 * 4
                    if (frames <= 0 || stride != expected) return emptyList()

                    val list = ArrayList<FloatArray>(frames)
                    for (i in 0 until frames) {
                        val arr = FloatArray(stride)
                        for (j in 0 until stride) {
                            arr[j] = dis.readFloat()
                        }
                        list += arr
                    }
                    list
                }
            }
        }.getOrNull()
    }
}