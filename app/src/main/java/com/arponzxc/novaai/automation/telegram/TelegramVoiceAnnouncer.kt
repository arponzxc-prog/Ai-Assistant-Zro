package com.arponzxc.novaai.automation.telegram

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Spoken announcements for Telegram (independent TTS instance from WhatsApp's announcer). */
class TelegramVoiceAnnouncer(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TelegramVoiceAnnounce"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise TTS: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        isTtsReady = status == TextToSpeech.SUCCESS
        if (!isTtsReady) Log.w(TAG, "TTS init failed with status $status")
    }

    private fun isPhoneCallActive(): Boolean {
        val mode = audioManager?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    fun announceIncomingMessage(chatName: String, messageText: String) {
        if (isPhoneCallActive()) return
        speak("Telegram message from $chatName: ${messageText.take(100)}")
    }

    fun announceSentReply(chatName: String, replyText: String) {
        if (isPhoneCallActive()) return
        speak("Auto-replied to $chatName on Telegram: ${replyText.take(100)}")
    }

    private fun speak(text: String) {
        val engine = tts ?: return
        if (!isTtsReady) return
        try {
            val locale = when {
                text.any { it in '\u0980'..'\u09FF' } -> Locale("bn", "BD")
                text.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
                else -> Locale.getDefault()
            }
            engine.language = locale
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "tg_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking: ${e.message}", e)
        }
    }

    fun stop() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        } finally {
            tts = null
            isTtsReady = false
        }
    }
}
