package com.arponzxc.novaai.ui.camerapill

/**
 * Full set of synchronized Camera Pill assistant states.
 */
enum class CameraPillAiState(val badgeLabel: String) {
    IDLE("Ready"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    INTERRUPTED("Interrupted"),
    ERROR("Attention"),
    OFFLINE("Offline")
}

/**
 * Controls when the Camera Pill overlay stays visible over other apps.
 */
enum class CameraPillDisplayMode(val prefValue: String, val label: String) {
    ONLY_WHILE_TALKING("only_while_talking", "Only While Talking"),
    ALWAYS_ON("always_on", "Always On");

    companion object {
        fun fromPref(value: String?): CameraPillDisplayMode =
            entries.firstOrNull { it.prefValue == value } ?: ONLY_WHILE_TALKING
    }
}

/**
 * Cutout alignment reported by [CutoutLocator].
 */
enum class CutoutAlignment {
    LEFT,
    CENTER,
    RIGHT
}

/**
 * Persisted user configuration for Camera Pill (AI Live Avatar).
 */
data class CameraPillConfig(
    val enabled: Boolean = false,
    val displayMode: CameraPillDisplayMode = CameraPillDisplayMode.ONLY_WHILE_TALKING,
    val idleHideDelayMs: Long = 3500L,
    val avatarAnimationEnabled: Boolean = true,
    val audioVisualizerEnabled: Boolean = true,
    val showThinkingAnimation: Boolean = true,
    val showSpeakingAnimation: Boolean = true,
    val avatarSizeScale: Float = 1.0f,
    val animationIntensity: Float = 1.0f
)

/**
 * Live UI state observed by both the floating WindowManager overlay and Settings preview.
 */
data class CameraPillUiState(
    val config: CameraPillConfig = CameraPillConfig(),
    val aiState: CameraPillAiState = CameraPillAiState.IDLE,
    val userAmplitude: Float = 0f,
    val aiAmplitude: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
    val isHappyResponse: Boolean = false,
    val isTtsSpeaking: Boolean = false,
    val isSessionActive: Boolean = false,
    val isStandbyActive: Boolean = false,
    val isPreviewActive: Boolean = false,
    val isExpanded: Boolean = false,
    val isMinimized: Boolean = false,
    val hasCustomPosition: Boolean = false,
    val statusText: String = "NOVA AI Ready",
    val errorDetail: String? = null,
    val shouldShowOverlay: Boolean = false
)
