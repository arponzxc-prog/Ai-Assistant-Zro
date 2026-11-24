package com.arponzxc.novaai.audio

enum class NoiseSensitivity(
    val label: String,
    val minThreshold: Float,
    val noiseMultiplier: Float,
    val speechBandRatioMin: Float
) {
    LOW("Low Filtering (Sensitive Mic)", 0.032f, 1.75f, 0.25f),
    BALANCED("Balanced (Recommended)", 0.048f, 2.0f, 0.34f),
    HIGH("High Noise Rejection", 0.068f, 2.35f, 0.42f);

    companion object {
        fun fromPref(raw: String?): NoiseSensitivity {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BALANCED
        }
    }
}

data class VoiceListeningConfig(
    val continuousModeEnabled: Boolean = true,
    val autoResumeAfterReply: Boolean = true,
    val silenceTimeoutMs: Long = 1100L,
    val noiseSensitivity: NoiseSensitivity = NoiseSensitivity.BALANCED,
    val backgroundListeningEnabled: Boolean = true,
    val voiceRecognitionLanguage: String = "Auto",
    val voiceStartFrames: Int = 5,
    val minUtteranceFrames: Int = 10
) {
    fun clampedSilenceTimeoutMs(): Long = silenceTimeoutMs.coerceIn(500L, 3500L)
}

enum class VadFrameDecision {
    NOISE_OR_SILENCE,
    SPEECH_STARTED,
    SPEECH_CONTINUING,
    SPEECH_PAUSED_HOLD,
    SPEECH_ENDED
}

/**
 * Pure Kotlin adaptive Voice Activity Detection (VAD) and silence/noise controller.
 * Supports long Bengali & English voice commands with natural mid-sentence pauses
 * without prematurely cutting off the user's speech.
 */
class ContinuousVoiceVadEngine(
    initialConfig: VoiceListeningConfig = VoiceListeningConfig()
) {
    @Volatile
    var config: VoiceListeningConfig = initialConfig
        private set

    var ambientNoiseFloor: Float = 0.03f
        private set
    var consecutiveVoicedFrames: Int = 0
        private set
    var totalVoicedFramesInUtterance: Int = 0
        private set
    var hasSpeechInCurrentUtterance: Boolean = false
        private set
    var lastSpeechTimestampMs: Long = 0L
        private set

    fun updateConfig(newConfig: VoiceListeningConfig) {
        config = newConfig
    }

    fun resetUtterance() {
        consecutiveVoicedFrames = 0
        totalVoicedFramesInUtterance = 0
        hasSpeechInCurrentUtterance = false
    }

    fun resetAll(nowMs: Long = 0L) {
        resetUtterance()
        ambientNoiseFloor = 0.03f
        lastSpeechTimestampMs = nowMs
    }

    fun evaluateFrame(
        amplitude: Float,
        frequencyData: AudioFrequencyData,
        nowMs: Long
    ): VadFrameDecision {
        val currentConfig = config
        val sensitivity = currentConfig.noiseSensitivity

        if (amplitude < 0.10f) {
            ambientNoiseFloor = (ambientNoiseFloor * 0.97f + amplitude * 0.03f)
                .coerceIn(0.015f, 0.11f)
        }

        val threshold = maxOf(
            sensitivity.minThreshold,
            ambientNoiseFloor * sensitivity.noiseMultiplier
        )

        val bandTotal = (frequencyData.bass + frequencyData.mid + frequencyData.high)
        val speechBandRatio = if (bandTotal > 0.001f) {
            (frequencyData.mid + frequencyData.high) / bandTotal
        } else {
            1f
        }

        // Reject steady low-frequency rumble (e.g. ceiling fan/AC hum) when amplitude is borderline
        val passesSpectralGate = amplitude > threshold * 1.45f || speechBandRatio >= sensitivity.speechBandRatioMin
        val isVoiced = amplitude > threshold && passesSpectralGate

        if (isVoiced) {
            consecutiveVoicedFrames++
            lastSpeechTimestampMs = nowMs
            if (consecutiveVoicedFrames >= currentConfig.voiceStartFrames) {
                val startedNow = !hasSpeechInCurrentUtterance
                hasSpeechInCurrentUtterance = true
                totalVoicedFramesInUtterance++
                return if (startedNow) VadFrameDecision.SPEECH_STARTED else VadFrameDecision.SPEECH_CONTINUING
            }
            return if (hasSpeechInCurrentUtterance) {
                VadFrameDecision.SPEECH_CONTINUING
            } else {
                VadFrameDecision.NOISE_OR_SILENCE
            }
        } else {
            if (consecutiveVoicedFrames > 0) {
                consecutiveVoicedFrames--
            }
            if (!hasSpeechInCurrentUtterance) {
                return VadFrameDecision.NOISE_OR_SILENCE
            }
            val silentDuration = (nowMs - lastSpeechTimestampMs).coerceAtLeast(0L)
            return if (silentDuration >= currentConfig.clampedSilenceTimeoutMs()) {
                VadFrameDecision.SPEECH_ENDED
            } else {
                VadFrameDecision.SPEECH_PAUSED_HOLD
            }
        }
    }

    fun hasValidUtterance(): Boolean {
        return totalVoicedFramesInUtterance >= config.minUtteranceFrames
    }
}
