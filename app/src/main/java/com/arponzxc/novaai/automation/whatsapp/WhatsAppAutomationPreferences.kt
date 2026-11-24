package com.arponzxc.novaai.automation.whatsapp

import android.content.Context
import android.content.SharedPreferences

class WhatsAppAutomationPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "nova_whatsapp_automation_prefs"

        private const val KEY_MASTER_ENABLED = "key_wa_master_enabled"
        private const val KEY_GEMINI_BRAIN_ENABLED = "key_wa_gemini_brain_enabled"
        private const val KEY_AUTO_REPLY_ENABLED = "key_wa_auto_reply_enabled"
        private const val KEY_VOICE_ANNOUNCEMENT_ENABLED = "key_wa_voice_announcement_enabled"
        private const val KEY_READ_INCOMING_ENABLED = "key_wa_read_incoming_enabled"
        private const val KEY_REPLY_DELAY_SECONDS = "key_wa_reply_delay_seconds"
        private const val KEY_OPERATOR_NAME = "key_wa_operator_name"
        private const val KEY_INCLUDE_MEMORY_CONTEXT = "key_wa_include_memory_context"
        private const val KEY_TOTAL_REPLIES_COUNT = "key_wa_total_replies_count"
        private const val KEY_CUSTOM_PROMPT = "key_wa_custom_prompt"
    }

    var isMasterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()

    var isGeminiBrainEnabled: Boolean
        get() = prefs.getBoolean(KEY_GEMINI_BRAIN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GEMINI_BRAIN_ENABLED, value).apply()

    var isAutoReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY_ENABLED, value).apply()

    var isVoiceAnnouncementEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ANNOUNCEMENT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ANNOUNCEMENT_ENABLED, value).apply()

    var isReadIncomingEnabled: Boolean
        get() = prefs.getBoolean(KEY_READ_INCOMING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_INCOMING_ENABLED, value).apply()

    var replyDelaySeconds: Int
        get() = prefs.getInt(KEY_REPLY_DELAY_SECONDS, 3).coerceIn(0, 30)
        set(value) = prefs.edit().putInt(KEY_REPLY_DELAY_SECONDS, value.coerceIn(0, 30)).apply()

    var operatorName: String
        get() = prefs.getString(KEY_OPERATOR_NAME, "Arpon") ?: "Arpon"
        set(value) = prefs.edit().putString(KEY_OPERATOR_NAME, value.trim()).apply()

    var includeMemoryContext: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_MEMORY_CONTEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_MEMORY_CONTEXT, value).apply()

    var totalRepliesCount: Int
        get() = prefs.getInt(KEY_TOTAL_REPLIES_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_REPLIES_COUNT, value).apply()

    fun incrementRepliesCount() {
        totalRepliesCount += 1
    }

    var customSystemPrompt: String
        get() = prefs.getString(KEY_CUSTOM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_PROMPT, value.trim()).apply()
}
