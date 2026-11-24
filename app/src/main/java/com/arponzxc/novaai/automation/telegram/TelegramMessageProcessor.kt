package com.arponzxc.novaai.automation.telegram

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Turns a raw Telegram notification into a validated [ParsedTelegramMessage] or a skip reason.
 *
 * Only data that is present in the notification itself is used. Nothing else about the chat
 * is read, and nothing is persisted here.
 */
class TelegramMessageProcessor {

    /** Plain-data snapshot of a notification (keeps parsing unit-testable without Android). */
    data class RawNotification(
        val packageName: String,
        val key: String,
        val notificationId: Int,
        val postTime: Long,
        val title: String,
        val text: String,
        val conversationTitle: String?,
        val isGroupFlag: Boolean?,
        val latestSenderName: String?,
        val latestMessageText: String?,
        val latestMessageTimestamp: Long?,
        val latestFromSelf: Boolean,
        val shortcutId: String?,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
        val isProgress: Boolean
    )

    data class ParsedTelegramMessage(
        val chatKey: String,
        val chatName: String,
        val senderName: String,
        val text: String,
        val isGroup: Boolean,
        val isUnknownSender: Boolean,
        val messageTimestamp: Long
    )

    enum class SkipReason {
        SUMMARY, ONGOING, EMPTY_TEXT, OUTGOING_ECHO, SERVICE_CHAT, UNSUPPORTED_MESSAGE_TYPE, HIDDEN_PREVIEW
    }

    sealed class ParseResult {
        data class Accepted(val message: ParsedTelegramMessage) : ParseResult()
        data class Skipped(val reason: SkipReason) : ParseResult()
    }

    companion object {
        /** Official Telegram Android clients: Google Play build and the direct-download (web) build. */
        val TELEGRAM_PACKAGES: Set<String> = setOf("org.telegram.messenger", "org.telegram.messenger.web")

        fun isTelegramPackage(pkg: String?): Boolean = pkg != null && pkg in TELEGRAM_PACKAGES

        private val MEDIA_PREFIXES = listOf(
            "📷", "🖼", "📸", "🎥", "📹", "🎬", "🎤", "🎙", "🎧", "🎵", "📎", "📄", "📁", "📍", "🗺",
            "📊", "🎮", "👤", "🎁", "🎭", "📞", "☎"
        )
        private val MEDIA_LABELS = listOf(
            "photo", "video", "voice message", "video message", "sticker", "gif", "document", "file",
            "audio", "location", "live location", "contact", "poll", "game", "invoice", "story",
            "round video", "music"
        )
        private val HIDDEN_PREVIEW_TEXTS = listOf(
            "new message", "you have a new message", "you have new messages", "new messages"
        )
        private val ACTIVITY_MARKERS = listOf(
            "pinned a message", "pinned «", "reacted to your", "joined telegram", "is typing"
        )
        private val NUMBER_ONLY_TITLE = Regex("^\\+?[\\d\\s\\-()]{6,}$")
        private val COUNT_SUFFIX = Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$", RegexOption.IGNORE_CASE)
    }

    /** Android-bound extraction. Everything is read from the notification the user's device already shows. */
    fun toRaw(sbn: StatusBarNotification): RawNotification? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        var conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        var isGroup: Boolean? =
            if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION))
                extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else null

        var latestSender: String? = null
        var latestText: String? = null
        var latestTs: Long? = null
        var fromSelf = false

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (style != null) {
            style.conversationTitle?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { conversationTitle = it }
            if (isGroup == null) isGroup = style.isGroupConversation
            val latest = style.messages.lastOrNull()
            if (latest != null) {
                latestSender = latest.person?.name?.toString()?.trim()
                latestText = latest.text?.toString()?.trim()
                if (latest.timestamp > 0L) latestTs = latest.timestamp
                val person = latest.person
                // Only an explicit match with the device user counts as "self". A missing person is NOT
                // treated as self, otherwise real incoming messages would be dropped; replies we sent
                // ourselves are caught separately by the manager's own-reply memory.
                fromSelf = person != null &&
                    ((person.key != null && person.key == style.user.key) ||
                        (!style.user.name.isNullOrBlank() &&
                            person.name?.toString()?.trim() == style.user.name?.toString()?.trim()))
            }
        }

        val shortcut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.shortcutId else null

        return RawNotification(
            packageName = sbn.packageName,
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}:${sbn.tag}",
            notificationId = sbn.id,
            postTime = sbn.postTime,
            title = title,
            text = text,
            conversationTitle = conversationTitle,
            isGroupFlag = isGroup,
            latestSenderName = latestSender,
            latestMessageText = latestText,
            latestMessageTimestamp = latestTs,
            latestFromSelf = style != null && style.messages.isNotEmpty() && fromSelf,
            shortcutId = shortcut,
            isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            isProgress = notification.category == Notification.CATEGORY_PROGRESS
        )
    }

    fun parse(raw: RawNotification): ParseResult {
        if (raw.isGroupSummary) return ParseResult.Skipped(SkipReason.SUMMARY)
        if (raw.isOngoing || raw.isProgress) return ParseResult.Skipped(SkipReason.ONGOING)

        val text = (raw.latestMessageText?.takeIf { it.isNotBlank() } ?: raw.text).trim()
        if (text.isBlank()) return ParseResult.Skipped(SkipReason.EMPTY_TEXT)

        if (raw.latestFromSelf) return ParseResult.Skipped(SkipReason.OUTGOING_ECHO)

        val cleanTitle = raw.title.replace(COUNT_SUFFIX, "").trim()
        // Telegram's own service chat (login codes etc.) and generic "Telegram" system notifications.
        if (cleanTitle.equals("Telegram", ignoreCase = true) && raw.conversationTitle.isNullOrBlank()) {
            return ParseResult.Skipped(SkipReason.SERVICE_CHAT)
        }

        if (isOutgoingEcho(text)) return ParseResult.Skipped(SkipReason.OUTGOING_ECHO)
        if (isHiddenPreview(text)) return ParseResult.Skipped(SkipReason.HIDDEN_PREVIEW)
        if (isUnsupportedType(text)) return ParseResult.Skipped(SkipReason.UNSUPPORTED_MESSAGE_TYPE)

        val sender = (raw.latestSenderName?.takeIf { it.isNotBlank() } ?: cleanTitle).ifBlank { "Telegram Contact" }
        val convoTitle = raw.conversationTitle?.takeIf { it.isNotBlank() }

        val isGroup = raw.isGroupFlag == true ||
            (convoTitle != null && !convoTitle.equals(sender, ignoreCase = true))

        val chatName = (convoTitle ?: cleanTitle.ifBlank { sender }).trim()
        val chatKey = when {
            !raw.shortcutId.isNullOrBlank() -> "sc:${raw.shortcutId}"
            else -> "name:${chatName.lowercase()}"
        }

        val unknown = isUnknownSender(sender)

        return ParseResult.Accepted(
            ParsedTelegramMessage(
                chatKey = chatKey,
                chatName = chatName,
                senderName = sender,
                text = text,
                isGroup = isGroup,
                isUnknownSender = unknown,
                messageTimestamp = raw.latestMessageTimestamp ?: raw.postTime
            )
        )
    }

    /** Stable key used for notification de-duplication. */
    fun dedupKey(raw: RawNotification, parsed: ParsedTelegramMessage): String =
        "${parsed.chatKey}|${parsed.text.hashCode()}|${parsed.messageTimestamp}"

    fun isUnknownSender(sender: String): Boolean {
        val s = sender.trim()
        return s.isBlank() ||
            s.equals("Telegram Contact", ignoreCase = true) ||
            s.equals("Unknown", ignoreCase = true) ||
            s.equals("Deleted Account", ignoreCase = true) ||
            NUMBER_ONLY_TITLE.matches(s)
    }

    private fun isOutgoingEcho(text: String): Boolean =
        text.startsWith("You: ") || text.startsWith("You:\u00A0")

    private fun isHiddenPreview(text: String): Boolean =
        HIDDEN_PREVIEW_TEXTS.any { text.equals(it, ignoreCase = true) }

    fun isUnsupportedType(text: String): Boolean {
        val t = text.trim()
        if (MEDIA_PREFIXES.any { t.startsWith(it) }) return true
        val lower = t.lowercase()
        if (ACTIVITY_MARKERS.any { lower.contains(it) } && t.length < 80) return true
        // "Photo", "Voice message", "Photo, caption..." etc. (label alone or label followed by separator)
        return MEDIA_LABELS.any { label ->
            lower == label || lower.startsWith("$label,") || lower.startsWith("$label:")
        }
    }
}
