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

class ImmersiveActivity : AppCompatActivity(), PostureMonitor.Listener, PoseLandmarkerHelper.Listener {
    private lateinit var binding: ActivityImmersiveBinding
    private val handler = Handler(Looper.getMainLooper())
    private var startElapsed = 0L
    private lateinit var monitor: PostureMonitor
    private lateinit var poseHelper: PoseLandmarkerHelper
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var hasCameraPermission = false

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

        cameraExecutor = Executors.newSingleThreadExecutor()
        poseHelper = PoseLandmarkerHelper(this, this)

        val config = PostureMonitor.Config(
            hunchEnabled = intent.getBooleanExtra(EXTRA_HUNCH, true),
            headDownEnabled = intent.getBooleanExtra(EXTRA_HEAD, true),
            hydrationEnabled = intent.getBooleanExtra(EXTRA_WATER, true),
            quickTest = intent.getBooleanExtra(EXTRA_QUICK_TEST, false)
        )
        monitor = PostureMonitor(config, this)

        binding.immersiveRoot.setOnLongClickListener {
            finish()
            true
        }

        requestCameraPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        enableImmersive()
        startTimer()
        monitor.start()
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
        if (hasFocus) {
            enableImmersive()
        }
    }

    override fun onAlert(type: PostureMonitor.AlertType) {
        vibrate()
    }

    override fun onResults(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        monitor.onPose(result)
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
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

    private fun startTimer() {
        startElapsed = SystemClock.elapsedRealtime()
        handler.post(tickRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun updateElapsedTime() {
        val elapsed = SystemClock.elapsedRealtime() - startElapsed
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
        const val EXTRA_HUNCH = "extra_hunch"
        const val EXTRA_HEAD = "extra_head"
        const val EXTRA_WATER = "extra_water"
        const val EXTRA_QUICK_TEST = "extra_quick_test"
    }
}
