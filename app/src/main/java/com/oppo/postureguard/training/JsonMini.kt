package com.oppo.postureguard.training

object JsonMini {

    fun poseFrameJson(
        landmarks: FloatArray,
        worldLandmarks: FloatArray?,
        tElapsed: Long,
        bodyMode: BodyMode,
    ): String {
        val sb = StringBuilder(96 + (landmarks.size + (worldLandmarks?.size ?: 0)) * 6)
        sb.append('{')
        sb.append("\"type\":\"POSE_FRAME\",\"t\":").append(tElapsed).append(',')
        sb.append("\"bodyMode\":\"").append(bodyMode.name).append("\",")
        sb.append("\"landmarks\":[")
        for (i in landmarks.indices) {
            if (i != 0) sb.append(',')
            sb.append(trimFloat(landmarks[i]))
        }
        sb.append(']')

        if (worldLandmarks != null) {
            sb.append(",\"world\":[")
            for (i in worldLandmarks.indices) {
                if (i != 0) sb.append(',')
                sb.append(trimFloat(worldLandmarks[i]))
            }
            sb.append(']')
        }

        sb.append('}')
        return sb.toString()
    }

    fun coachStateJson(
        coachIndex: Int,
        score: Float,
        errorIds: List<String>,
        errorLabels: List<String>,
    ): String {
        val sb = StringBuilder(192)
        sb.append('{')
        sb.append("\"type\":\"COACH_STATE\",\"coachIndex\":").append(coachIndex).append(',')
        sb.append("\"score\":").append(trimFloat(score)).append(',')

        // For stable highlighting on the Web side.
        sb.append("\"errorIds\":[")
        for (i in errorIds.indices) {
            if (i != 0) sb.append(',')
            sb.append('"').append(escape(errorIds[i])).append('"')
        }
        sb.append("],")

        // Human readable (Chinese) for HUD.
        sb.append("\"errorLabels\":[")
        for (i in errorLabels.indices) {
            if (i != 0) sb.append(',')
            sb.append('"').append(escape(errorLabels[i])).append('"')
        }
        sb.append(']')

        // Backward compatible alias.
        sb.append(",\"errors\":[")
        for (i in errorIds.indices) {
            if (i != 0) sb.append(',')
            sb.append('"').append(escape(errorIds[i])).append('"')
        }
        sb.append("]}")

        return sb.toString()
    }

    fun coachPoseJson(
        landmarks: FloatArray,
        worldLandmarks: FloatArray?,
        coachIndex: Int,
    ): String {
        val sb = StringBuilder(96 + (landmarks.size + (worldLandmarks?.size ?: 0)) * 6)
        sb.append('{')
        sb.append("\"type\":\"COACH_POSE\",\"coachIndex\":").append(coachIndex).append(',')
        sb.append("\"landmarks\":[")
        for (i in landmarks.indices) {
            if (i != 0) sb.append(',')
            sb.append(trimFloat(landmarks[i]))
        }
        sb.append(']')

        if (worldLandmarks != null) {
            sb.append(",\"world\":[")
            for (i in worldLandmarks.indices) {
                if (i != 0) sb.append(',')
                sb.append(trimFloat(worldLandmarks[i]))
            }
            sb.append(']')
        }

        sb.append('}')
        return sb.toString()
    }

    private fun trimFloat(v: Float): String {
        return String.format(java.util.Locale.US, "%.4f", v)
    }

    private fun escape(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
