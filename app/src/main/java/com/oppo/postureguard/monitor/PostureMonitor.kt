package com.oppo.postureguard.monitor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.abs
import kotlin.math.max

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

    private val postureIntervalMs = if (config.quickTest) 2500L else 12_000L
    private val hydrationIntervalMs = if (config.quickTest) 10_000L else 45 * 60 * 1000L

    // Head-down detection uses a per-user baseline captured at focus start.
    // We compute headRatio = (shoulderY - noseY) / shoulderWidth.
    // Smaller ratio => nose closer to shoulders => more likely "head down".
    private var headBaseline = 0.30f
    private var headEma = 0.30f
    private var headBelowSince = 0L
    private var headWarmupUntil = 0L

    private val headFactor = if (config.quickTest) 0.70f else 0.65f
    private val headHoldMs = if (config.quickTest) 700L else 1200L
    private val minShoulderWidth = 0.06f

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

    fun setHeadBaseline(ratio: Float) {
        val clamped = ratio.coerceIn(0.10f, 0.80f)
        headBaseline = clamped
        headEma = clamped
        headBelowSince = 0L
        headWarmupUntil = SystemClock.elapsedRealtime() + 1000L
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

        val torsoLen = hipY - shoulderY
        val shoulderWidth = abs(leftShoulder.x() - rightShoulder.x())
        val scale = max(torsoLen, shoulderWidth)
        if (scale <= 0f) return
        if (shoulderWidth < minShoulderWidth) return

        val now = SystemClock.elapsedRealtime()

        // Smaller clearance => head closer to shoulders => likely head down.
        val headClearance = shoulderY - noseY
        val headRatio = (headClearance / shoulderWidth).coerceIn(-2f, 2f)

        // Smooth ratio to avoid jitter.
        headEma = headEma * 0.85f + headRatio * 0.15f
        val headThreshold = (headBaseline * headFactor).coerceIn(0.08f, 0.55f)
        val headDown = headEma < headThreshold

        // Very rough hunch heuristic for demo: torso appears "compressed".
        val hunch = torsoLen in 0f..(if (config.quickTest) 0.18f else 0.20f)

        if (now - lastAlertTime < postureIntervalMs) return

        if (config.headDownEnabled) {
            if (now < headWarmupUntil) {
                headBelowSince = 0L
            } else if (headDown) {
                if (headBelowSince == 0L) headBelowSince = now
                if (now - headBelowSince >= headHoldMs) {
                    lastAlertTime = now
                    headBelowSince = 0L
                    notify(AlertType.HEAD_DOWN)
                    return
                }
            } else {
                headBelowSince = 0L
            }
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

