package com.oppo.postureguard.monitor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PostureMonitor(
    private val config: Config,
    private val listener: Listener
) {
    data class Config(
        val hunchEnabled: Boolean,
        val headDownEnabled: Boolean,
        val hydrationEnabled: Boolean,
        val quickTest: Boolean
    )

    enum class AlertType {
        HUNCH,
        HEAD_DOWN,
        HYDRATION
    }

    interface Listener {
        fun onAlert(type: AlertType)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hydrationHandler = Handler(Looper.getMainLooper())
    private var lastAlertTime = 0L

    private val postureIntervalMs = if (config.quickTest) 4000L else 15000L
    private val hydrationIntervalMs = if (config.quickTest) 15000L else 45 * 60 * 1000L
    private val headDownThreshold = if (config.quickTest) 0.07f else 0.10f
    private val hunchDepthThreshold = if (config.quickTest) 0.06f else 0.10f

    private val hydrationRunnable = object : Runnable {
        override fun run() {
            if (config.hydrationEnabled) {
                notify(AlertType.HYDRATION)
                hydrationHandler.postDelayed(this, hydrationIntervalMs)
            }
        }
    }

    fun start() {
        stop()
        if (config.hydrationEnabled) {
            hydrationHandler.postDelayed(hydrationRunnable, hydrationIntervalMs)
        }
    }

    fun stop() {
        hydrationHandler.removeCallbacks(hydrationRunnable)
    }

    fun onPose(result: PoseLandmarkerResult) {
        if (!config.hunchEnabled && !config.headDownEnabled) return

        val landmarks = result.landmarks().firstOrNull() ?: return
        if (landmarks.size <= RIGHT_HIP) return

        val nose = landmarks[NOSE]
        val leftShoulder = landmarks[LEFT_SHOULDER]
        val rightShoulder = landmarks[RIGHT_SHOULDER]
        val leftHip = landmarks[LEFT_HIP]
        val rightHip = landmarks[RIGHT_HIP]

        val shoulderY = (leftShoulder.y() + rightShoulder.y()) / 2f
        val hipY = (leftHip.y() + rightHip.y()) / 2f
        val noseY = nose.y()

        val shoulderZ = (leftShoulder.z() + rightShoulder.z()) / 2f
        val hipZ = (leftHip.z() + rightHip.z()) / 2f

        val headDown = (noseY - shoulderY) > headDownThreshold
        val hunch = (hipZ - shoulderZ) > hunchDepthThreshold && (hipY - shoulderY) > 0.12f

        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertTime < postureIntervalMs) return

        if (config.headDownEnabled && headDown) {
            lastAlertTime = now
            notify(AlertType.HEAD_DOWN)
            return
        }
        if (config.hunchEnabled && hunch) {
            lastAlertTime = now
            notify(AlertType.HUNCH)
        }
    }

    private fun notify(type: AlertType) {
        mainHandler.post { listener.onAlert(type) }
    }

    companion object {
        private const val NOSE = 0
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24
    }
}
