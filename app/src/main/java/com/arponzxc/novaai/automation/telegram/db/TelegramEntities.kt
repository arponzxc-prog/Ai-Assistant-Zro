package com.arponzxc.novaai.automation.telegram.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-chat rule. Matching is done on the chat identifier / chat name taken from the
 * notification (exact, case-insensitive, or with explicit `*` wildcards) - never fuzzy.
 */
@Entity(
    tableName = "telegram_rules",
    indices = [Index(value = ["chatPattern"])]
)
data class TelegramRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatPattern: String,
    val ruleType: String = RULE_INSTRUCTION,
    /** Comma separated keywords, used by KEYWORD_* rule types. */
    val keywords: String = "",
    /** Fixed reply text, or AI instruction, depending on [ruleType]. */
    val customContent: String = "",
    /** If false, Gemini is never used for this chat (only fixed replies are sent). */
    val aiEnabled: Boolean = true,
    /** 0 = use the global default cooldown. */
    val cooldownSeconds: Int = 0,
    val priority: Int = 10,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val RULE_ALLOW = "ALLOW"
        const val RULE_BLOCK = "BLOCK"
        const val RULE_FIXED_REPLY = "FIXED_REPLY"
        const val RULE_INSTRUCTION = "INSTRUCTION"
        const val RULE_KEYWORD_REPLY = "KEYWORD_REPLY"
        const val RULE_KEYWORD_IGNORE = "KEYWORD_IGNORE"
    }
}

/** Audit log. Message text is only stored when the user enables previews. */
@Entity(
    tableName = "telegram_logs",
    indices = [Index(value = ["timestamp"])]
)
data class TelegramLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val chatName: String,
    val incomingPreview: String = "",
    val replyText: String = "",
    val status: String,
    val detail: String? = null
) {
    companion object {
        const val REPLIED = "REPLIED"
        const val SKIPPED_RULE = "SKIPPED_RULE"
        const val SKIPPED_SENSITIVE = "SKIPPED_SENSITIVE"
        const val SKIPPED_GROUP = "SKIPPED_GROUP"
        const val SKIPPED_UNKNOWN_SENDER = "SKIPPED_UNKNOWN_SENDER"
        const val SKIPPED_COOLDOWN = "SKIPPED_COOLDOWN"
        const val SKIPPED_RATE_LIMIT = "SKIPPED_RATE_LIMIT"
        const val SKIPPED_UNSUPPORTED = "SKIPPED_UNSUPPORTED"
        const val SKIPPED_AI_DISABLED = "SKIPPED_AI_DISABLED"
        const val SUPERSEDED = "SUPERSEDED"
        const val AUTO_REPLY_OFF = "AUTO_REPLY_OFF"
        const val NO_REPLY_ACTION = "NO_REPLY_ACTION"
        const val FAILED_SEND = "FAILED_SEND"
    }
}

/** Per chat counters: reply count, last processed time and last reply time (cooldown source). */
@Entity(tableName = "telegram_chat_stats")
data class TelegramChatStatsEntity(
    @PrimaryKey val chatKey: String,
    val chatName: String,
    val replyCount: Int = 0,
    val lastProcessedTime: Long = 0L,
    val lastReplyTime: Long = 0L
)

@Entity(
    tableName = "telegram_errors",
    indices = [Index(value = ["timestamp"])]
)
data class TelegramErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val component: String,
    val message: String
)
