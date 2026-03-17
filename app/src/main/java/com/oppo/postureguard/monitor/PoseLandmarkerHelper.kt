package com.oppo.postureguard.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onResults(result: PoseLandmarkerResult)
        fun onError(error: String)
    }

    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupPoseLandmarker(context)
    }

    fun clear() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val landmarker = poseLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        val frameTime = SystemClock.uptimeMillis()

        // For RGBA_8888 output, rowStride may include padding. Copy into a padded bitmap then crop.
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)

        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)
        imageProxy.close()

        val bitmapBuffer = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, width.toFloat(), height.toFloat())
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        landmarker.detectAsync(mpImage, frameTime)
    }

    private fun setupPoseLandmarker(context: Context) {
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MODEL_PATH)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> listener.onResults(result) }
            .setErrorListener { error -> listener.onError(error.message ?: "MediaPipe error") }
            .build()

        poseLandmarker = try {
            PoseLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            listener.onError(e.message ?: "MediaPipe init failed")
            null
        }
    }

    companion object {
        const val MODEL_PATH = "pose_landmarker_lite.task"
    }
}

