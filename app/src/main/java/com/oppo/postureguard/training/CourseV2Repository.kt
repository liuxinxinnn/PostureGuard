package com.oppo.postureguard.training

import android.content.Context
import org.json.JSONObject

class CourseV2Repository(private val context: Context) {

    data class DtwConfig(
        val windowSeconds: Int,
        val searchSeconds: Int,
        val updateHz: Int,
        val fps: Int
    )

    data class Thresholds(
        val passScore: Float,
        val holdMs: Long
    )

    data class CourseV2(
        val id: String,
        val name: String,
        val bodyMode: BodyMode,
        val fps: Int,
        val mirror: Boolean,
        val dtw: DtwConfig,
        val thresholds: Thresholds,
        val coachFeaturesAsset: String?,
        val coachLandmarksAsset: String?
    )

    fun load(courseId: String): CourseV2 {
        val path = "courses/$courseId/course_v2.json"
        val json = runCatching { readAssetText(path) }.getOrNull()
        if (json == null) {
            return demo(courseId)
        }
        return runCatching { parse(courseId, json) }.getOrElse { demo(courseId) }
    }

    private fun demo(courseId: String): CourseV2 {
        val fps = 20
        return CourseV2(
            id = courseId,
            name = "影子跟练 v2（演示）",
            bodyMode = BodyMode.UPPER_BODY,
            fps = fps,
            mirror = true,
            dtw = DtwConfig(windowSeconds = 6, searchSeconds = 8, updateHz = 2, fps = fps),
            thresholds = Thresholds(passScore = 75f, holdMs = 600L),
            coachFeaturesAsset = null,
            coachLandmarksAsset = null
        )
    }

    private fun parse(courseId: String, json: String): CourseV2 {
        val root = JSONObject(json)
        val name = root.optString("name", "影子跟练 v2")
        val bodyMode = BodyMode.fromString(root.optString("body_mode", "UPPER_BODY"))
        val fps = root.optInt("fps", 20).coerceIn(10, 30)
        val mirror = root.optBoolean("mirror", true)

        val dtwObj = root.optJSONObject("dtw")
        val windowSeconds = dtwObj?.optInt("window_seconds", 6) ?: 6
        val searchSeconds = dtwObj?.optInt("search_seconds", 8) ?: 8
        val updateHz = dtwObj?.optInt("update_hz", 2) ?: 2

        val thObj = root.optJSONObject("thresholds")
        val passScore = (thObj?.optDouble("pass_score", 75.0) ?: 75.0).toFloat()
        val holdMs = thObj?.optLong("hold_ms", 600L) ?: 600L

        val coachFeatures = root.optString("coach_features", "").takeIf { it.isNotBlank() }
            ?.let { "courses/$courseId/$it" }
        val coachLandmarks = root.optString("coach_landmarks", "").takeIf { it.isNotBlank() }
            ?.let { "courses/$courseId/$it" }

        return CourseV2(
            id = courseId,
            name = name,
            bodyMode = bodyMode,
            fps = fps,
            mirror = mirror,
            dtw = DtwConfig(windowSeconds, searchSeconds, updateHz, fps),
            thresholds = Thresholds(passScore, holdMs),
            coachFeaturesAsset = coachFeatures,
            coachLandmarksAsset = coachLandmarks
        )
    }

    private fun readAssetText(assetPath: String): String {
        context.assets.open(assetPath).use { input ->
            return input.bufferedReader(Charsets.UTF_8).readText()
        }
    }
}