package com.arponzxc.novaai.automation.whatsapp

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class WhatsAppVoiceAnnouncer(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "WhatsAppVoiceAnnounce"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS for WhatsApp announcements: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.language = Locale.getDefault()
            Log.d(TAG, "WhatsApp TTS engine initialized successfully.")
        } else {
            isTtsReady = false
            Log.w(TAG, "WhatsApp TTS engine initialization failed with status $status")
        }
    }

    /**
     * Checks if the device is currently in a voice call or active communication.
     */
    fun isPhoneCallActive(): Boolean {
        val am = audioManager ?: return false
        val mode = am.mode
        return mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
    }

    /**
     * Announces an incoming WhatsApp message out loud if enabled and not on a call.
     */
    fun announceIncomingMessage(senderName: String, messageText: String) {
        if (isPhoneCallActive()) {
            Log.d(TAG, "Phone call active. Suppressing voice announcement.")
            return
        }

        val speech = "WhatsApp message from $senderName: ${messageText.take(100)}"
        speak(speech)
    }

    /**
     * Announces a sent automated reply out loud if enabled and not on a call.
     */
    fun announceSentReply(senderName: String, replyText: String) {
        if (isPhoneCallActive()) {
            Log.d(TAG, "Phone call active. Suppressing voice announcement.")
            return
        }

        val speech = "Auto-replied to $senderName: ${replyText.take(100)}"
        speak(speech)
    }

    private fun speak(text: String) {
        if (!isTtsReady || tts == null) return
        try {
            // Select appropriate locale if Bengali or Hindi text detected
            val isBengali = text.any { it in '\u0980'..'\u09FF' }
            val isHindi = text.any { it in '\u0900'..'\u097F' }

            val targetLocale = when {
                isBengali -> Locale("bn", "BD")
                isHindi -> Locale("hi", "IN")
                else -> Locale.getDefault()
            }

            tts?.language = targetLocale
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "wa_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text: ${e.message}", e)
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (_: Exception) {}
    }
}
