package com.arponzxc.novaai.automation.whatsapp.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores incoming WhatsApp messages and outgoing automatic replies
 * to maintain conversation memory for the Gemini AI reply engine.
 */
@Entity(
    tableName = "whatsapp_conversations",
    indices = [
        Index(value = ["contactKey"]),
        Index(value = ["timestamp"])
    ]
)
data class WhatsAppConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val contactKey: String,
    val senderName: String,
    val messageText: String,
    val isIncoming: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val language: String = "auto"
)

/**
 * Stores personalized rules per sender (e.g. Boss, Mom, specific numbers).
 * Rules can instruct Gemini with a custom prompt, return a fixed reply, or ignore the sender.
 */
@Entity(
    tableName = "whatsapp_rules",
    indices = [
        Index(value = ["contactPattern"])
    ]
)
data class WhatsAppContactRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val contactPattern: String,
    val ruleType: String = RULE_TYPE_INSTRUCTION, // "INSTRUCTION", "FIXED_REPLY", "IGNORE"
    val customContent: String = "",
    val priority: Int = 1,
    val isEnabled: Boolean = true
) {
    companion object {
        const val RULE_TYPE_INSTRUCTION = "INSTRUCTION"
        const val RULE_TYPE_FIXED_REPLY = "FIXED_REPLY"
        const val RULE_TYPE_IGNORE = "IGNORE"
    }
}

/**
 * Audit log of every incoming WhatsApp notification and automated action.
 */
@Entity(
    tableName = "whatsapp_activity_logs",
    indices = [
        Index(value = ["timestamp"])
    ]
)
data class WhatsAppLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val senderName: String,
    val incomingText: String,
    val replyText: String = "",
    val status: String, // "REPLIED", "PROCESSING", "SKIPPED_RULE", "SKIPPED_SENSITIVE", "FAILED_NO_ACTION", "FAILED_API", "DISABLED"
    val errorMessage: String? = null
)
