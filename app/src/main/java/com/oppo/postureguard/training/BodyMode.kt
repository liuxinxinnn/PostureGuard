package com.oppo.postureguard.training

enum class BodyMode {
    UPPER_BODY,
    FULL_BODY;

    companion object {
        fun fromString(raw: String?): BodyMode {
            return when (raw?.trim()?.uppercase()) {
                "FULL", "FULL_BODY", "WHOLE" -> FULL_BODY
                "UPPER", "UPPER_BODY", "HALF" -> UPPER_BODY
                else -> UPPER_BODY
            }
        }
    }
}
