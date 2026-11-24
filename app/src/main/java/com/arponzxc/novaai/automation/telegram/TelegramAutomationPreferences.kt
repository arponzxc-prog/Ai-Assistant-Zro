package com.arponzxc.novaai.automation.telegram

import android.content.Context
import android.content.SharedPreferences

/**
 * Independent settings store for Telegram automation.
 * Uses its own SharedPreferences file so it can never collide with WhatsApp settings.
 *
 * Safety defaults: master and auto-reply are OFF until the user explicitly enables them,
 * group messages are ignored, and message previews are not written to the log.
 */
class TelegramAutomationPreferences(context: Context) {

    enum class ReplyMode { SHORT, DETAILED, PROFESSIONAL;
        companion object {
            fun fromName(name: String?): ReplyMode =
                values().firstOrNull { it.name == name } ?: SHORT
        }
    }

    enum class LanguageMode { AUTO, BENGALI, ENGLISH;
        companion object {
            fun fromName(name: String?): LanguageMode =
                values().firstOrNull { it.name == name } ?: AUTO
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "nova_telegram_automation_prefs"

        private const val KEY_MASTER = "key_tg_master_enabled"
        private const val KEY_AUTO_REPLY = "key_tg_auto_reply_enabled"
        private const val KEY_GEMINI = "key_tg_gemini_enabled"
        private const val KEY_VOICE = "key_tg_voice_announcement_enabled"
        private const val KEY_READ_INCOMING = "key_tg_read_incoming_enabled"
        private const val KEY_DELAY = "key_tg_reply_delay_seconds"
        private const val KEY_OPERATOR = "key_tg_operator_name"
        private const val KEY_CONTEXT = "key_tg_include_context"
        private const val KEY_REPLY_MODE = "key_tg_reply_mode"
        private const val KEY_LANGUAGE = "key_tg_language_mode"
        private const val KEY_PERSONALITY = "key_tg_personality"
        private const val KEY_CUSTOM_PROMPT = "key_tg_custom_prompt"
        private const val KEY_IGNORE_GROUPS = "key_tg_ignore_groups"
        private const val KEY_IGNORE_UNKNOWN = "key_tg_ignore_unknown"
        private const val KEY_ALLOWLIST_ONLY = "key_tg_allowlist_only"
        private const val KEY_DEFAULT_COOLDOWN = "key_tg_default_cooldown_seconds"
        private const val KEY_MAX_PER_HOUR = "key_tg_max_replies_per_hour"
        private const val KEY_MAX_PER_CHAT_HOUR = "key_tg_max_replies_per_chat_hour"
        private const val KEY_LOG_PREVIEWS = "key_tg_log_previews"
    }

    var isMasterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, false)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    /** Must be switched on explicitly by the user; default OFF. */
    var isAutoReplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPLY, value).apply()

    var isGeminiEnabled: Boolean
        get() = prefs.getBoolean(KEY_GEMINI, true)
        set(value) = prefs.edit().putBoolean(KEY_GEMINI, value).apply()

    var isVoiceAnnouncementEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var isReadIncomingEnabled: Boolean
        get() = prefs.getBoolean(KEY_READ_INCOMING, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_INCOMING, value).apply()

    var replyDelaySeconds: Int
        get() = prefs.getInt(KEY_DELAY, 3).coerceIn(0, 30)
        set(value) = prefs.edit().putInt(KEY_DELAY, value.coerceIn(0, 30)).apply()

    var operatorName: String
        get() = prefs.getString(KEY_OPERATOR, "Arpon") ?: "Arpon"
        set(value) = prefs.edit().putString(KEY_OPERATOR, value.trim()).apply()

    var includeConversationContext: Boolean
        get() = prefs.getBoolean(KEY_CONTEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTEXT, value).apply()

    var replyMode: ReplyMode
        get() = ReplyMode.fromName(prefs.getString(KEY_REPLY_MODE, null))
        set(value) = prefs.edit().putString(KEY_REPLY_MODE, value.name).apply()

    var languageMode: LanguageMode
        get() = LanguageMode.fromName(prefs.getString(KEY_LANGUAGE, null))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.name).apply()

    /** Free-text AI personality, e.g. "friendly and humorous". */
    var aiPersonality: String
        get() = prefs.getString(KEY_PERSONALITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value.trim().take(300)).apply()

    var customPrompt: String
        get() = prefs.getString(KEY_CUSTOM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_PROMPT, value.trim().take(1000)).apply()

    /** Group automation is OFF by default (groups are ignored). */
    var ignoreGroupMessages: Boolean
        get() = prefs.getBoolean(KEY_IGNORE_GROUPS, true)
        set(value) = prefs.edit().putBoolean(KEY_IGNORE_GROUPS, value).apply()

    var ignoreUnknownSenders: Boolean
        get() = prefs.getBoolean(KEY_IGNORE_UNKNOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_IGNORE_UNKNOWN, value).apply()

    /** When true, only chats with an enabled ALLOW rule receive automatic replies. */
    var allowlistOnly: Boolean
        get() = prefs.getBoolean(KEY_ALLOWLIST_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOWLIST_ONLY, value).apply()

    var defaultCooldownSeconds: Int
        get() = prefs.getInt(KEY_DEFAULT_COOLDOWN, 60).coerceIn(0, 3600)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_COOLDOWN, value.coerceIn(0, 3600)).apply()

    var maxRepliesPerHour: Int
        get() = prefs.getInt(KEY_MAX_PER_HOUR, 20).coerceIn(1, 200)
        set(value) = prefs.edit().putInt(KEY_MAX_PER_HOUR, value.coerceIn(1, 200)).apply()

    var maxRepliesPerChatPerHour: Int
        get() = prefs.getInt(KEY_MAX_PER_CHAT_HOUR, 5).coerceIn(1, 60)
        set(value) = prefs.edit().putInt(KEY_MAX_PER_CHAT_HOUR, value.coerceIn(1, 60)).apply()

    /** Off by default: incoming message text is not written to the activity log. */
    var logMessagePreviews: Boolean
        get() = prefs.getBoolean(KEY_LOG_PREVIEWS, false)
        set(value) = prefs.edit().putBoolean(KEY_LOG_PREVIEWS, value).apply()
}
