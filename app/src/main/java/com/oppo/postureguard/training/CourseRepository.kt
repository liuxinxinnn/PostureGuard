package com.oppo.postureguard.training

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class CourseRepository(private val context: Context) {
    fun loadCourse(courseId: String): Course {
        val jsonPath = "courses/$courseId/course.json"
        val json = runCatching { readAssetText(jsonPath) }.getOrNull()
            ?: return Course.demoRuntime(courseId)

        return parseCourse(courseId, json)
    }

    fun loadKeyframeAssets(course: Course): Course {
        val updated = course.keyframes.map { kf ->
            val pose = kf.poseAssetPath?.let { safeLoadPose(it) }
            val mask = kf.maskAssetPath?.let { safeLoadMask(it) }
            kf.copy(poseXY = pose, maskBitmap = mask)
        }
        return course.copy(keyframes = updated)
    }

    private fun parseCourse(courseId: String, json: String): Course {
        val root = JSONObject(json)
        val name = root.optString("name", "课程")
        val bodyMode = BodyMode.fromString(root.optString("body_mode", null))

        val frameObj = root.optJSONObject("frame")
        val frame = if (frameObj != null) {
            FrameRect(
                left = frameObj.optDouble("left", 0.18).toFloat(),
                top = frameObj.optDouble("top", 0.12).toFloat(),
                right = frameObj.optDouble("right", 0.82).toFloat(),
                bottom = frameObj.optDouble("bottom", 0.92).toFloat(),
            )
        } else {
            // Default: upper body is a bit smaller; full body more generous.
            if (bodyMode == BodyMode.FULL_BODY) FrameRect(0.12f, 0.06f, 0.88f, 0.98f)
            else FrameRect(0.18f, 0.12f, 0.82f, 0.92f)
        }

        val video = root.optString("video", null)?.takeIf { it.isNotBlank() }
        val videoPath = video?.let { "courses/$courseId/$it" }

        val keyframesArr = root.optJSONArray("keyframes") ?: JSONArray()
        val keyframes = ArrayList<Keyframe>(keyframesArr.length())
        for (i in 0 until keyframesArr.length()) {
            val o = keyframesArr.getJSONObject(i)
            val tMs = o.optLong("t_ms", 0L)
            val poseRel = o.optString("pose", null)?.takeIf { it.isNotBlank() }
            val maskRel = o.optString("mask", null)?.takeIf { it.isNotBlank() }

            val thresholdObj = o.optJSONObject("threshold")
            val threshold = Threshold(
                iou = thresholdObj?.optDouble("iou", 0.55)?.toFloat() ?: 0.55f,
                err = thresholdObj?.optDouble("err", 0.10)?.toFloat() ?: 0.10f,
                holdMs = thresholdObj?.optLong("hold_ms", 800L) ?: 800L
            )

            val kfBodyMode = BodyMode.fromString(o.optString("body_mode", null)).let {
                // If keyframe doesn't specify, fromString returns UPPER_BODY by default; preserve null.
                if (o.has("body_mode")) it else null
            }

            keyframes += Keyframe(
                tMs = tMs,
                poseAssetPath = poseRel?.let { "courses/$courseId/$it" },
                maskAssetPath = maskRel?.let { "courses/$courseId/$it" },
                bodyModeOverride = kfBodyMode,
                threshold = threshold
            )
        }

        // Ensure at least 1 keyframe for gating demo.
        val nonEmpty = if (keyframes.isNotEmpty()) keyframes else {
            listOf(Keyframe(tMs = 3000L, poseAssetPath = null, maskAssetPath = null, bodyModeOverride = null, threshold = Threshold(0.55f, 0.10f, 800L)))
        }

        return Course(
            id = courseId,
            name = name,
            bodyMode = bodyMode,
            videoAssetPath = videoPath,
            frame = frame,
            keyframes = nonEmpty
        )
    }

    private fun safeLoadPose(assetPath: String): FloatArray? {
        return runCatching { loadPoseXY(assetPath) }
            .onFailure { Log.w(TAG, "loadPose failed: $assetPath", it) }
            .getOrNull()
    }

    private fun safeLoadMask(assetPath: String): Bitmap? {
        return runCatching {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }
            .onFailure { Log.w(TAG, "loadMask failed: $assetPath", it) }
            .getOrNull()
            ?.let { src ->
                // Small render buffer for overlay/scoring.
                Bitmap.createScaledBitmap(src, 256, 256, true)
            }
    }

    private fun loadPoseXY(assetPath: String): FloatArray {
        val json = readAssetText(assetPath)
        val root = JSONObject(json)

        val flat = root.optJSONArray("landmarks_xy")
            ?: root.optJSONArray("landmarks")
            ?: JSONArray()

        // Case A: array of numbers [x0,y0,x1,y1,...]
        if (flat.length() > 0 && flat.opt(0) is Number) {
            val n = flat.length()
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = flat.getDouble(i).toFloat()
            return out
        }

        // Case B: array of objects [{x:...,y:...}, ...]
        val list = ArrayList<Float>(flat.length() * 2)
        for (i in 0 until flat.length()) {
            val o = flat.getJSONObject(i)
            list += o.optDouble("x", 0.0).toFloat()
            list += o.optDouble("y", 0.0).toFloat()
        }
        return list.toFloatArray()
    }

    private fun readAssetText(assetPath: String): String {
        context.assets.open(assetPath).use { input ->
            return input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    companion object {
        private const val TAG = "CourseRepo"
    }
}

data class Course(
    val id: String,
    val name: String,
    val bodyMode: BodyMode,
    val videoAssetPath: String?,
    val frame: FrameRect,
    val keyframes: List<Keyframe>
) {
    companion object {
        fun demoRuntime(courseId: String): Course {
            return Course(
                id = courseId,
                name = "对齐测试课程",
                bodyMode = BodyMode.UPPER_BODY,
                videoAssetPath = null,
                frame = FrameRect(0.18f, 0.12f, 0.82f, 0.92f),
                keyframes = listOf(
                    Keyframe(
                        tMs = 2000L,
                        poseAssetPath = null,
                        maskAssetPath = null,
                        bodyModeOverride = null,
                        threshold = Threshold(iou = 0.0f, err = 0.08f, holdMs = 700L)
                    )
                )
            )
        }
    }
}

data class FrameRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class Threshold(
    val iou: Float,
    val err: Float,
    val holdMs: Long
)

data class Keyframe(
    val tMs: Long,
    val poseAssetPath: String?,
    val maskAssetPath: String?,
    val bodyModeOverride: BodyMode?,
    val threshold: Threshold,
    val poseXY: FloatArray? = null,
    val maskBitmap: Bitmap? = null
)
