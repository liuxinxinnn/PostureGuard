package com.oppo.postureguard.training

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.Toast
import org.json.JSONObject
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
import androidx.webkit.WebViewAssetLoader
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.oppo.postureguard.R
import com.oppo.postureguard.databinding.ActivityCoachTrainingV2Binding
import com.oppo.postureguard.monitor.FrameBitmapUtil
import com.oppo.postureguard.monitor.PoseLandmarkerHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import kotlin.math.sqrt

class CoachTrainingV2Activity : AppCompatActivity(), PoseLandmarkerHelper.Listener {

    private lateinit var binding: ActivityCoachTrainingV2Binding

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseHelper: PoseLandmarkerHelper

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    private val uiHandler = Handler(Looper.getMainLooper())
    private val dtwHandler = Handler(Looper.getMainLooper())

    private lateinit var courseRepo: CourseV2Repository
    private lateinit var course: CourseV2Repository.CourseV2

    private val userWindow = ArrayDeque<PoseFeatureExtractor.PoseFeature>()
    private var lastLandmarks: FloatArray? = null
    private var lastWorldLandmarks: FloatArray? = null
    private var lastFeature: PoseFeatureExtractor.PoseFeature? = null

    private var coachFeatures: List<PoseFeatureExtractor.PoseFeature> = emptyList()
    private var coachLandmarksSeq: List<FloatArray> = emptyList()

    private var coachCursor = 0
    private var passSince = 0L

    // Demo fallback when a course does not ship coach sequences: we build a baseline "standard" pose
    // from the first ~1s of user frames and score against it.
    private var baselineFeature: PoseFeatureExtractor.PoseFeature? = null
    private var baselineLandmarks: FloatArray? = null
    private val baselineSum = FloatArray(PoseFeatureExtractor.FEATURE_SIZE)
    private val baselineCnt = IntArray(PoseFeatureExtractor.FEATURE_SIZE)
    private var baselineFrames = 0

    private var bridge: WebPortBridge? = null
    private var webReady = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, getString(R.string.permission_camera_denied), Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val dtwTick = object : Runnable {
        override fun run() {
            runDtwAndScore()
            val intervalMs = (1000L / course.dtw.updateHz.toLong()).coerceAtLeast(200L)
            dtwHandler.postDelayed(this, intervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCoachTrainingV2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        courseRepo = CourseV2Repository(this)
        val courseId = intent.getStringExtra(EXTRA_COURSE_ID) ?: "demo"
        course = courseRepo.load(courseId)

        binding.statusText.text = course.name
        binding.scoreText.text = "--"

        setupWebView(binding.webView)

        cameraExecutor = Executors.newSingleThreadExecutor()
        poseHelper = PoseLandmarkerHelper(this, this)

        // Load coach sequences (optional; demo can run without them).
        loadCoachAssetsAsync(course)

        requestCameraPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        enableImmersive()
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
        dtwHandler.removeCallbacks(dtwTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        poseHelper.clear()
        cameraExecutor.shutdown()
        bridge?.close()
        bridge = null
    }

    private fun setupWebView(webView: WebView) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Make WebView transparent so the camera preview behind it stays visible.
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        // Dev: avoid stale cached JS/VRM while iterating.
        webView.clearCache(true)

        // Use WebViewAssetLoader so three.js can fetch() the VRM under https://appassets...
        // (fetch does not support file:// in WebView).
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val origin = Uri.parse("https://appassets.androidplatform.net")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val b = WebPortBridge(webView, origin)
                bridge = b
                b.connect(
                    {
                        webReady = true
                        b.postText("{\"type\":\"HELLO\",\"t\":${System.currentTimeMillis()}}")
                    },
                    { msg -> handleWebTextMessage(msg) }
                )
            }
        }

        val ts = System.currentTimeMillis()
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/coach3d/index.html?ts=$ts")
    }
private fun handleWebTextMessage(msg: String) {
        // Keep for future capability negotiation.
        runCatching {
            val o = JSONObject(msg)
            if (o.optString("type") == "CAPS") {
                // v2 bridge is string-only to maximize compatibility.
            }
        }
    }

    private fun loadCoachAssetsAsync(course: CourseV2Repository.CourseV2) {
        // v1: optional demo assets. If absent, fall back to self-coach.
        Executors.newSingleThreadExecutor().execute {
            val features = course.coachFeaturesAsset?.let { CoachSequenceIO.tryLoadFeatures(assets, it) }
            val landmarks = course.coachLandmarksAsset?.let { CoachSequenceIO.tryLoadLandmarks(assets, it) }

            coachFeatures = features ?: emptyList()
            coachLandmarksSeq = landmarks ?: emptyList()

            Log.d(TAG, "coachFeatures=${coachFeatures.size} coachLandmarks=${coachLandmarksSeq.size}")
        }
    }

    private fun requestCameraPermissionIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                // Start DTW tick only after camera pipeline is running.
                dtwHandler.removeCallbacks(dtwTick)
                dtwHandler.postDelayed(dtwTick, 500)
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

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

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
                    val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
                    val frameTime = SystemClock.uptimeMillis()
                    val bitmap = FrameBitmapUtil.toUprightBitmap(imageProxy, isFront)
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    poseHelper.detectLiveStream(mpImage, frameTime)
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, imageAnalysis)
    }

    private fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
    }

    override fun onResults(result: PoseLandmarkerResult) {
        val lm = result.landmarks().firstOrNull()
        if (lm == null || lm.isEmpty()) return

        val arr = FloatArray(lm.size * 4)
        for (i in lm.indices) {
            val p = lm[i]
            arr[i * 4] = safeFloat(p, "x")
            arr[i * 4 + 1] = safeFloat(p, "y")
            arr[i * 4 + 2] = safeFloat(p, "z")
            // visibility/presence varies; use reflection and fall back.
            arr[i * 4 + 3] = safeFloat(p, "visibility", fallback = 1f)
        }
                lastLandmarks = arr

        val wlm = result.worldLandmarks().firstOrNull()
        if (wlm != null && wlm.size == lm.size) {
            val warr = FloatArray(wlm.size * 4)
            for (i in wlm.indices) {
                val p = wlm[i]
                warr[i * 4] = safeFloat(p, "x")
                warr[i * 4 + 1] = safeFloat(p, "y")
                warr[i * 4 + 2] = safeFloat(p, "z")
                warr[i * 4 + 3] = safeFloat(p, "visibility", fallback = 1f)
            }
            lastWorldLandmarks = warr
        } else {
            lastWorldLandmarks = null
        }

        val feature = PoseFeatureExtractor.extract(arr, course.bodyMode)
        lastFeature = feature

        // If course has no coach assets, create a baseline target pose from early frames.
        if (coachFeatures.isEmpty() && baselineFeature == null) {
            accumulateBaseline(feature, arr)
        }
        // Maintain ring buffer.
        val maxFrames = course.dtw.windowSeconds * course.fps
        synchronized(userWindow) {
            userWindow.addLast(feature)
            while (userWindow.size > maxFrames) userWindow.removeFirst()
        }

        // Send pose frame to WebView at ~15fps.
        if (webReady) {
            maybeSendPoseFrame(arr)
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, error)
        uiHandler.post { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    private fun poseFrameBinary(landmarks: FloatArray, tElapsed: Long, bodyMode: BodyMode): ByteArray {
        // ArrayBuffer layout (little-endian):
        // u32 magic 'PGP2'
        // u32 type=1 (POSE_FRAME)
        // u32 bodyMode (0=UPPER,1=FULL)
        // u64 tElapsed
        // u32 floatCount
        // float32[floatCount] landmarks
        val floatCount = landmarks.size
        val headerBytes = 24
        val bb = ByteBuffer.allocate(headerBytes + floatCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x50475032)
        bb.putInt(1)
        bb.putInt(if (bodyMode == BodyMode.FULL_BODY) 1 else 0)
        bb.putLong(tElapsed)
        bb.putInt(floatCount)
        for (f in landmarks) bb.putFloat(f)
        return bb.array()
    }

    private var lastPoseSentAt = 0L
    private fun maybeSendPoseFrame(landmarks: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoseSentAt < 66) return // ~15fps
        lastPoseSentAt = now

        val json = JsonMini.poseFrameJson(landmarks, lastWorldLandmarks, now, course.bodyMode)
        bridge?.postText(json)

        // Also send a coach pose for the right panel when available.
        val coachLm = coachLandmarksSeq.getOrNull(coachCursor) ?: baselineLandmarks
        if (coachLm != null) {
            bridge?.postText(JsonMini.coachPoseJson(coachLm, null, coachCursor))
        }
    }

    private fun accumulateBaseline(feature: PoseFeatureExtractor.PoseFeature, landmarks: FloatArray) {
        baselineFrames++
        for (i in 0 until PoseFeatureExtractor.FEATURE_SIZE) {
            if (!feature.missing.get(i)) {
                baselineSum[i] += feature.vec[i]
                baselineCnt[i] += 1
            }
        }

        val needFrames = course.fps.coerceIn(10, 30)
        if (baselineFrames < needFrames) {
            uiHandler.post {
                binding.statusText.text = "${course.name}（建立基准 $baselineFrames/$needFrames）"
            }
            return
        }

        val avg = FloatArray(PoseFeatureExtractor.FEATURE_SIZE)
        val miss = BitSet(PoseFeatureExtractor.FEATURE_SIZE)
        for (i in 0 until PoseFeatureExtractor.FEATURE_SIZE) {
            val c = baselineCnt[i]
            if (c <= 0) {
                miss.set(i)
                avg[i] = 0f
            } else {
                avg[i] = baselineSum[i] / c.toFloat()
            }
        }

        // Renormalize bone direction vectors (11 vectors * 3 floats = first 33 floats).
        for (v in 0 until 11) {
            val base = v * 3
            if (miss.get(base) || miss.get(base + 1) || miss.get(base + 2)) continue
            val x = avg[base]
            val y = avg[base + 1]
            val z = avg[base + 2]
            val n = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (n < 1e-6f) {
                miss.set(base)
                miss.set(base + 1)
                miss.set(base + 2)
                avg[base] = 0f
                avg[base + 1] = 0f
                avg[base + 2] = 0f
            } else {
                avg[base] = x / n
                avg[base + 1] = y / n
                avg[base + 2] = z / n
            }
        }

        baselineFeature = PoseFeatureExtractor.PoseFeature(avg, miss)
        baselineLandmarks = landmarks.clone()

        uiHandler.post {
            binding.statusText.text = "${course.name}（基准已建立）"
        }
    }
private fun runDtwAndScore() {
        val userSeq: List<PoseFeatureExtractor.PoseFeature> = synchronized(userWindow) { userWindow.toList() }
        val last = lastFeature ?: return

        val coachSeq = coachFeatures
        if (coachSeq.isNotEmpty() && userSeq.size >= 10) {
            // Build coach search window around cursor.
            val searchFrames = course.dtw.searchSeconds * course.fps
            val half = searchFrames / 2
            val start = (coachCursor - half).coerceAtLeast(0)
            val end = (coachCursor + half).coerceAtMost(coachSeq.size - 1)
            val window = coachSeq.subList(start, end + 1)

            val res = FastDtwAligner.alignToBestEnd(userSeq, window, course.bodyMode, radius = 10)
            coachCursor = (start + res.bestCoachIndexInSearch).coerceIn(0, coachSeq.size - 1)
        } else {
            // Fallback: self-coach demo.
            coachCursor = 0
        }

        val coachFeature = coachSeq.getOrNull(coachCursor) ?: baselineFeature ?: last
        val score = PoseSimilarity.score(last, coachFeature, course.bodyMode)

        // Gate: passScore must hold for holdMs.
        val now = SystemClock.elapsedRealtime()
        val pass = score.score0to100 >= course.thresholds.passScore
        if (pass) {
            if (passSince == 0L) passSince = now
        } else {
            passSince = 0L
        }
        val held = passSince != 0L && (now - passSince) >= course.thresholds.holdMs

        uiHandler.post {
            val errs = score.errors.joinToString(" / ") { it.label }
            binding.scoreText.text = score.score0to100.toInt().toString()
            binding.statusText.text = if (held) {
                if (score.errors.isNotEmpty()) {
                    "动作达标，继续（可改进：$errs）"
                } else {
                    "动作达标，继续"
                }
            } else {
                if (score.errors.isNotEmpty()) {
                    "纠正：$errs"
                } else {
                    "对齐中：保持动作"
                }
            }
            binding.feedbackOverlay.alpha = if (!held) 0.18f else 0.0f
            binding.feedbackOverlay.setBackgroundColor(
                ContextCompat.getColor(this, if (!held) R.color.danger else R.color.ok_green)
            )
        }

        if (webReady) {
            bridge?.postText(JsonMini.coachStateJson(coachCursor, score.score0to100, score.errors.map { it.id }, score.errors.map { it.label }))
        }
    }
private fun enableImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.trainingV2Root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun safeFloat(obj: Any, method: String, fallback: Float = 0f): Float {
        return runCatching {
            val m = obj.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }
                ?: obj.javaClass.methods.firstOrNull { it.name == method + "()" }
            val v = m?.invoke(obj)
            when (v) {
                is Number -> v.toFloat()
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    companion object {
        private const val TAG = "CoachTrainingV2"
        const val EXTRA_COURSE_ID = "extra_course_id"
    }
}






