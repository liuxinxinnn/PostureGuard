package com.oppo.postureguard.monitor

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
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

    /** For callers that already have an [MPImage] (e.g. shared with other tasks). */
    fun detectLiveStream(mpImage: MPImage, frameTime: Long) {
        val landmarker = poseLandmarker ?: return
        landmarker.detectAsync(mpImage, frameTime)
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val landmarker = poseLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()
        val uprightBitmap = FrameBitmapUtil.toUprightBitmap(imageProxy, isFrontCamera)
        val mpImage = BitmapImageBuilder(uprightBitmap).build()
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
