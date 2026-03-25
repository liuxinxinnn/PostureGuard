package com.oppo.postureguard.monitor

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import java.nio.ByteOrder

class ImageSegmenterHelper(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onMask(maskBitmap: Bitmap)
        fun onError(error: String)
    }

    private var segmenter: ImageSegmenter? = null
    private var lastRunElapsed = 0L

    // Segmentation is heavier; v1 throttles to reduce heat.
    var maxFps: Int = 12

    init {
        setup(context)
    }

    fun clear() {
        segmenter?.close()
        segmenter = null
    }

    fun segmentLiveStream(mpImage: MPImage, frameTime: Long = SystemClock.uptimeMillis()) {
        val seg = segmenter ?: return

        val now = SystemClock.elapsedRealtime()
        val minIntervalMs = (1000f / maxFps.toFloat()).toLong().coerceAtLeast(1)
        if (now - lastRunElapsed < minIntervalMs) return
        lastRunElapsed = now

        seg.segmentAsync(mpImage, frameTime)
    }

    private fun setup(context: Context) {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_PATH)
            .build()

        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(true)
            .setResultListener { result: ImageSegmenterResult, _ ->
                val mask = resultToAlphaMask(result)
                if (mask != null) listener.onMask(mask)
            }
            .setErrorListener { e ->
                listener.onError(e.message ?: "MediaPipe segmentation error")
            }
            .build()

        segmenter = try {
            ImageSegmenter.createFromOptions(context, options)
        } catch (e: Exception) {
            listener.onError(e.message ?: "Segmentation init failed")
            null
        }
    }

    private fun resultToAlphaMask(result: ImageSegmenterResult): Bitmap? {
        // 1. 处理 Confidence Masks (返回的是 Optional<MutableList<MPImage!>!>)
        val confidenceOptional = result.confidenceMasks()

        // 使用 isPresent 检查 Optional 中是否有值，并且包含的 List 大小至少为 2
        if (confidenceOptional != null && confidenceOptional.isPresent) {
            val confidenceList = confidenceOptional.get()
            if (confidenceList.size >= 2) {
                val personMask = confidenceList[1] // 获取人的 mask (索引 1)
                val w = personMask.width
                val h = personMask.height

                // 注意：ByteBufferExtractor 提取的是底层 ByteBuffer
                val buf = ByteBufferExtractor.extract(personMask)
                buf.order(ByteOrder.nativeOrder())
                val fb = buf.asFloatBuffer()

                if (fb.remaining() < w * h) return null

                val pixels = IntArray(w * h)
                for (i in 0 until w * h) {
                    val p = fb.get().coerceIn(0f, 1f)
                    val a = (p * 255f).toInt().coerceIn(0, 255)
                    // (a shl 24) 将 alpha 通道移到最高 8 位，后面保留 RGB 为全白 (0x00FFFFFF)
                    pixels[i] = (a shl 24) or 0x00FFFFFF
                }
                return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
        }

        // 2. 如果 Confidence Masks 不可用，回退处理 Category Mask (返回的是 Optional<MPImage!>)
        val categoryOptional = result.categoryMask()

        if (categoryOptional != null && categoryOptional.isPresent) {
            val cat = categoryOptional.get()
            val w = cat.width
            val h = cat.height

            val buf = ByteBufferExtractor.extract(cat)
            buf.rewind()

            val pixels = IntArray(w * h)
            for (i in 0 until w * h) {
                // 读取 ByteBuffer 里的字节，并转换为无符号整数 0-255
                val v = buf.get().toInt() and 0xFF
                // 假设类别 0 是背景，其他类别（非 0）是前景
                val a = if (v == 0) 0 else 255
                pixels[i] = (a shl 24) or 0x00FFFFFF
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        // 如果两种 mask 都没有拿到，返回 null
        return null
    }

    companion object {
        // 确保你的 assets 文件夹里有这个模型文件！
        const val MODEL_PATH = "selfie_segmenter.tflite"
    }
}