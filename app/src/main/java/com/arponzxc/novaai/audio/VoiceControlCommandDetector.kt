package com.arponzxc.novaai.audio

import java.util.Locale

enum class VoiceControlIntent {
    START_LISTENING,
    STOP_LISTENING,
    CONTINUE_LISTENING,
    CANCEL
}

object VoiceControlCommandDetector {

    private val wakePrefixRegex = Regex(
        "^(?:hey\\s+nova|hi\\s+nova|hello\\s+nova|ok\\s+nova|nova|নোভা|হেই\\s+নোভা|ওকে\\s+নোভা)[,\\s:-]*",
        RegexOption.IGNORE_CASE
    )

    fun detect(rawInput: String): VoiceControlIntent? {
        val normalized = rawInput
            .trim()
            .replace(wakePrefixRegex, "")
            .replace(Regex("[.,!?:;।\"'\\s]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)

        if (normalized.isBlank()) return null

        // Continue listening commands
        if (normalized in setOf(
                "continue listening",
                "keep listening",
                "stay listening",
                "resume listening",
                "listen continuously",
                "শুনতে থাকো",
                "শোনা চালিয়ে যাও",
                "শোনা চালিয়ে যাও",
                "শুনতে থাক"
            )
        ) {
            return VoiceControlIntent.CONTINUE_LISTENING
        }

        // Start listening commands
        if (normalized in setOf(
                "start listening",
                "begin listening",
                "listen now",
                "turn on mic",
                "turn on microphone",
                "unmute",
                "শোনা শুরু করো",
                "শোনা শুরু কর",
                "শোনো",
                "মাইক চালু করো"
            )
        ) {
            return VoiceControlIntent.START_LISTENING
        }

        // Stop listening commands
        if (normalized in setOf(
                "stop listening",
                "pause listening",
                "mute mic",
                "mute microphone",
                "turn off mic",
                "turn off microphone",
                "don't listen",
                "do not listen",
                "শোনা বন্ধ করো",
                "শোনা বন্ধ কর",
                "শোনা থামাও",
                "মাইক বন্ধ করো"
            )
        ) {
            return VoiceControlIntent.STOP_LISTENING
        }

        // Cancel commands
        if (normalized in setOf(
                "cancel",
                "cancel that",
                "cancel command",
                "cancel task",
                "abort",
                "never mind",
                "বাতিল",
                "বাতিল করো",
                "বাতিল কর",
                "বাদ দাও",
                "থামো"
            )
        ) {
            return VoiceControlIntent.CANCEL
        }

        return null
    }
}
