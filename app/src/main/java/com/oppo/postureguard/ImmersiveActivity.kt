package com.oppo.postureguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.oppo.postureguard.databinding.ActivityImmersiveBinding
import com.oppo.postureguard.monitor.PoseLandmarkerHelper
import com.oppo.postureguard.monitor.PostureMonitor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImmersiveActivity : AppCompatActivity(), PostureMonitor.Listener, PoseLandmarkerHelper.Listener {
    private lateinit var binding: ActivityImmersiveBinding
    private val handler = Handler(Looper.getMainLooper())

    private var focusStarted = false
    private var sessionStartElapsed = 0L

    private lateinit var monitor: PostureMonitor
    private lateinit var poseHelper: PoseLandmarkerHelper
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var hasCameraPermission = false

    private var quickTest = false
    private var analysisStartElapsed = 0L
    private var frameCount = 0L
    private var lastLandmarks = 0
    private var lastUiUpdateElapsed = 0L
    private var lastHeadRatio = 0f
    private var baselineHeadEma = 0f
    private var baselineSamples = 0

    // Target frame (normalized, relative to overlay view).
    private val targetLeft = 0.18f
    private val targetTop = 0.12f
    private val targetRight = 0.82f
    private val targetBottom = 0.92f

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.permission_camera_denied),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateElapsedTime()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImmersiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        quickTest = intent.getBooleanExtra(EXTRA_QUICK_TEST, false)
        binding.debugText.visibility = if (quickTest) View.VISIBLE else View.GONE

        binding.poseOverlay.setTargetFrameFraction(targetLeft, targetTop, targetRight, targetBottom)
        binding.poseOverlay.setFrameVisible(true)
        binding.poseOverlay.setSkeletonVisible(true)

        cameraExecutor = Executors.newSingleThreadExecutor()
        poseHelper = PoseLandmarkerHelper(this, this)

        val config = PostureMonitor.Config(
            hunchEnabled = intent.getBooleanExtra(EXTRA_HUNCH, true),
            headDownEnabled = intent.getBooleanExtra(EXTRA_HEAD, true),
            hydrationEnabled = intent.getBooleanExtra(EXTRA_WATER, true),
            quickTest = quickTest
        )
        monitor = PostureMonitor(config, this)

        binding.confirmButton.setOnClickListener {
            if (!focusStarted) {
                startFocusMode()
            }
        }

        binding.immersiveRoot.setOnLongClickListener {
            finish()
            true
        }

        requestCameraPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        enableImmersive()
        if (hasCameraPermission) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        stopTimer()
        monitor.stop()
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        poseHelper.clear()
        cameraExecutor.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    override fun onAlert(type: PostureMonitor.AlertType) {
        val text = when (type) {
            PostureMonitor.AlertType.HUNCH -> getString(R.string.alert_hunch)
            PostureMonitor.AlertType.HEAD_DOWN -> getString(R.string.alert_head)
            PostureMonitor.AlertType.HYDRATION -> getString(R.string.alert_water)
        }
        runOnUiThread {
            binding.alertText.text = text
            flashAlert()
        }
        vibrate()
    }

    override fun onResults(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        val now = SystemClock.elapsedRealtime()
        if (analysisStartElapsed == 0L) analysisStartElapsed = now

        frameCount++
        val lm = result.landmarks().firstOrNull()
        lastLandmarks = lm?.size ?: 0

        val points = if (lm != null && lm.isNotEmpty()) {
            FloatArray(lm.size * 2).also { arr ->
                for (i in lm.indices) {
                    arr[i * 2] = lm[i].x()
                    arr[i * 2 + 1] = lm[i].y()
                }
            }
        } else {
            null
        }

        val inFrame = points?.let { isUpperBodyInFrame(it) } ?: false
        lastHeadRatio = points?.let { calcHeadRatio(it) } ?: 0f

        if (!focusStarted && lastHeadRatio > 0f) {
            baselineHeadEma = if (baselineSamples == 0) {
                lastHeadRatio
            } else {
                baselineHeadEma * 0.90f + lastHeadRatio * 0.10f
            }
            baselineSamples = (baselineSamples + 1).coerceAtMost(2000)
        }

        // Only start posture/hydration monitoring after user confirms start.
        if (focusStarted) {
            monitor.onPose(result)
        }

        if (now - lastUiUpdateElapsed >= 100) {
            lastUiUpdateElapsed = now
            runOnUiThread {
                binding.poseOverlay.updatePose(points, inFrame)
                updateSetupUi(inFrame)
                updateDebugUi(now)
            }
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "MediaPipe error: $error")
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSetupUi(inFrame: Boolean) {
        if (focusStarted) return

        val ready = lastLandmarks > 0
        binding.setupStatus.text = if (ready) {
            if (inFrame) getString(R.string.setup_status_ok) else getString(R.string.setup_status_waiting)
        } else {
            getString(R.string.setup_status_waiting)
        }
        binding.confirmButton.isEnabled = ready
    }

    private fun updateDebugUi(nowElapsed: Long) {
        if (!quickTest) return
        val denom = max(1L, nowElapsed - analysisStartElapsed)
        val fps = frameCount * 1000f / denom.toFloat()
        val baseline = if (baselineSamples > 0) baselineHeadEma else 0f
        val text =
            "frames=$frameCount  fps=%.1f\nlandmarks=$lastLandmarks  focus=$focusStarted\nheadRatio=%.2f  base=%.2f".format(
                fps,
                lastHeadRatio,
                baseline
            )
        binding.debugText.text = text
        Log.d(TAG, text.replace('\n', ' '))
    }

    private fun isUpperBodyInFrame(points: FloatArray): Boolean {
        val count = points.size / 2
        fun p(i: Int): Pair<Float, Float>? {
            if (i < 0 || i >= count) return null
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            if (x !in 0f..1f || y !in 0f..1f) return null
            return x to y
        }

        // Upper body only: nose + shoulders.
        val nose = p(0) ?: return false
        val ls = p(11) ?: return false
        val rs = p(12) ?: return false

        val centerX = (ls.first + rs.first) / 2f
        val centerY = (ls.second + rs.second) / 2f
        val shoulderWidth = abs(ls.first - rs.first)

        // Loose sitting alignment.
        val m = 0.28f
        val inside = centerX in (targetLeft - m)..(targetRight + m) &&
            centerY in (targetTop - m)..(targetBottom + m) &&
            nose.first in (targetLeft - m)..(targetRight + m) &&
            nose.second in (targetTop - m)..(targetBottom + m)

        val sizeOk = shoulderWidth >= 0.06f
        return inside && sizeOk
    }

    // Debug-only metric: smaller clearance means head is closer to shoulders.
    private fun calcHeadRatio(points: FloatArray): Float {
        val count = points.size / 2
        fun y(i: Int): Float? {
            if (i < 0 || i >= count) return null
            val v = points[i * 2 + 1]
            return if (v in 0f..1f) v else null
        }
        fun x(i: Int): Float? {
            if (i < 0 || i >= count) return null
            val v = points[i * 2]
            return if (v in 0f..1f) v else null
        }

        val noseY = y(0) ?: return 0f
        val lsY = y(11) ?: return 0f
        val rsY = y(12) ?: return 0f
        val shoulderY = (lsY + rsY) / 2f

        val lsX = x(11) ?: return 0f
        val rsX = x(12) ?: return 0f
        val shoulderWidth = abs(lsX - rsX).coerceAtLeast(1e-3f)

        val clearance = (shoulderY - noseY)
        return clearance / shoulderWidth
    }

    private fun startFocusMode() {
        focusStarted = true

        val baseline = if (baselineSamples > 0) baselineHeadEma else lastHeadRatio
        monitor.setHeadBaseline(baseline)

        // Immersive focus: hide preview visually, keep analysis running in background.
        binding.previewView.alpha = 0f
        binding.setupPanel.visibility = View.GONE

        binding.timerRing.visibility = View.VISIBLE
        sessionStartElapsed = SystemClock.elapsedRealtime()
        handler.post(tickRunnable)

        // Hide alignment frame in focus; keep skeleton on for testing.
        binding.poseOverlay.setFrameVisible(false)
        binding.poseOverlay.setSkeletonVisible(true)

        monitor.start()
    }

    private fun flashAlert() {
        binding.flashOverlay.alpha = 0f
        binding.flashOverlay.animate()
            .alpha(0.35f)
            .setDuration(80)
            .withEndAction {
                binding.flashOverlay.animate().alpha(0f).setDuration(200).start()
            }
            .start()

        binding.alertText.alpha = 0f
        binding.alertText.animate()
            .alpha(1f)
            .setDuration(120)
            .withEndAction {
                binding.alertText.animate()
                    .alpha(0f)
                    .setStartDelay(900)
                    .setDuration(250)
                    .start()
            }
            .start()
    }

    private fun enableImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.immersiveRoot)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestCameraPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = granted
        if (!granted) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        lensFacing = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    poseHelper.detectLiveStream(
                        imageProxy,
                        lensFacing == CameraSelector.LENS_FACING_FRONT
                    )
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, imageAnalysis)
    }

    private fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
    }

    private fun stopTimer() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun updateElapsedTime() {
        if (!focusStarted) return
        val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsed
        binding.timerText.text = formatElapsed(elapsed)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(180)
        }
    }

    companion object {
        private const val TAG = "PostureGuard"
        const val EXTRA_HUNCH = "extra_hunch"
        const val EXTRA_HEAD = "extra_head"
        const val EXTRA_WATER = "extra_water"
        const val EXTRA_QUICK_TEST = "extra_quick_test"
    }
}

