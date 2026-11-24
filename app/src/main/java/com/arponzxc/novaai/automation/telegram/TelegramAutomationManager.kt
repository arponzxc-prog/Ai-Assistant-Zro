package com.arponzxc.novaai.automation.telegram

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.arponzxc.novaai.automation.common.NotificationDeduplicator
import com.arponzxc.novaai.automation.telegram.db.TelegramChatStatsEntity
import com.arponzxc.novaai.automation.telegram.db.TelegramDatabase
import com.arponzxc.novaai.automation.telegram.db.TelegramErrorEntity
import com.arponzxc.novaai.automation.telegram.db.TelegramLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates Telegram automation: notification -> parse -> dedupe -> safety -> rules ->
 * limits -> (Gemini) reply -> inline-reply send -> logging.
 *
 * Fully independent from [com.arponzxc.novaai.automation.whatsapp.WhatsAppAutomationManager].
 * Uses only data contained in the notification; chat history is kept in memory only (short-lived).
 */
class TelegramAutomationManager(
    private val context: Context,
    val preferences: TelegramAutomationPreferences = TelegramAutomationPreferences(context),
    val database: TelegramDatabase = TelegramDatabase.getInstance(context),
    apiKeyProvider: () -> String,
    modelProvider: () -> String = { "gemini-2.5-flash" },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val TAG = "TelegramAutoManager"
        private const val HOUR_MS = 3_600_000L
        private const val HISTORY_TTL_MS = 30 * 60_000L
        private const val OWN_REPLY_TTL_MS = 10 * 60_000L
        private const val MAX_HISTORY_PER_CHAT = 8
        private const val MAX_TRACKED_CHATS = 60
        private const val LOG_KEEP = 200
        private const val LOG_RETENTION_MS = 7 * 24 * HOUR_MS

        fun isTelegramPackage(pkg: String?) = TelegramMessageProcessor.isTelegramPackage(pkg)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processor = TelegramMessageProcessor()
    private val replySender = TelegramReplySender(context)
    val replyEngine = GeminiTelegramReplyEngine(apiKeyProvider, modelProvider)
    // TTS is created lazily so an idle/disabled Telegram automation never binds a speech engine.
    @Volatile private var voiceAnnouncerInstance: TelegramVoiceAnnouncer? = null
    private val voiceAnnouncer: TelegramVoiceAnnouncer
        get() = voiceAnnouncerInstance ?: synchronized(this) {
            voiceAnnouncerInstance ?: TelegramVoiceAnnouncer(context).also { voiceAnnouncerInstance = it }
        }
    private val deduplicator = NotificationDeduplicator()

    private val chatJobs = ConcurrentHashMap<String, Job>()

    private data class HistoryItem(val fromContact: Boolean, val text: String, val time: Long)
    private val history = ConcurrentHashMap<String, ArrayDeque<HistoryItem>>()
    private val ownReplies = ConcurrentHashMap<String, ArrayDeque<Pair<String, Long>>>()
    private val globalReplyTimes = ArrayDeque<Long>()
    private val chatReplyTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    data class AutomationState(
        val isServiceConnected: Boolean = false,
        val isMasterEnabled: Boolean = false,
        val isAutoReplyEnabled: Boolean = false,
        val lastProcessedChat: String = "",
        val lastProcessedTime: Long = 0L
    )

    private val _state = MutableStateFlow(
        AutomationState(
            isMasterEnabled = preferences.isMasterEnabled,
            isAutoReplyEnabled = preferences.isAutoReplyEnabled
        )
    )
    val automationState: StateFlow<AutomationState> = _state.asStateFlow()

    fun updateServiceConnected(connected: Boolean) {
        _state.value = _state.value.copy(isServiceConnected = connected)
        if (!connected) cancelPendingReplies()
    }

    /** Master switch. Turning it OFF cancels everything that is still waiting to be sent. */
    fun setMasterEnabled(enabled: Boolean) {
        preferences.isMasterEnabled = enabled
        _state.value = _state.value.copy(isMasterEnabled = enabled)
        if (!enabled) {
            cancelPendingReplies()
            voiceAnnouncerInstance?.stop()
            deduplicator.clear()
        }
    }

    fun setAutoReplyEnabled(enabled: Boolean) {
        preferences.isAutoReplyEnabled = enabled
        _state.value = _state.value.copy(isAutoReplyEnabled = enabled)
        if (!enabled) cancelPendingReplies()
    }

    fun cancelPendingReplies() {
        chatJobs.values.forEach { it.cancel() }
        chatJobs.clear()
    }

    /**
     * Entry point from the notification listener. [isStillPosted] lets the manager confirm,
     * just before sending, that the notification (and therefore its chat) is still active.
     * It returns null when that cannot be determined.
     */
    fun onNotificationReceived(sbn: StatusBarNotification, isStillPosted: (String) -> Boolean?) {
        if (!preferences.isMasterEnabled) return
        if (!isTelegramPackage(sbn.packageName)) return

        val raw = try {
            processor.toRaw(sbn)
        } catch (e: Exception) {
            recordError("Parser", "Could not read notification: ${e.javaClass.simpleName}")
            null
        } ?: return

        val rawText = raw.latestMessageText?.takeIf { it.isNotBlank() } ?: raw.text
        if (!deduplicator.firstSeen("raw|${raw.key}|${raw.postTime}|${rawText.hashCode()}")) return

        when (val result = processor.parse(raw)) {
            is TelegramMessageProcessor.ParseResult.Skipped -> {
                if (result.reason == TelegramMessageProcessor.SkipReason.UNSUPPORTED_MESSAGE_TYPE) {
                    val name = raw.conversationTitle?.takeIf { it.isNotBlank() } ?: raw.title.ifBlank { "Telegram" }
                    scope.launch {
                        log(TelegramLogEntity.SKIPPED_UNSUPPORTED, name, null,
                            detail = "Media / non-text message type is not answered automatically.")
                    }
                }
            }
            is TelegramMessageProcessor.ParseResult.Accepted -> {
                val msg = result.message
                if (!deduplicator.firstSeen("msg|" + processor.dedupKey(raw, msg))) return

                val availability = replySender.checkAvailability(sbn)
                // A newer message from the same chat replaces any reply still waiting for this chat.
                chatJobs.remove(msg.chatKey)?.cancel()
                val job = scope.launch {
                    try {
                        processMessage(msg, availability, sbn.key ?: raw.key, isStillPosted)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Unhandled error processing Telegram message: ${e.message}", e)
                        recordError("Manager", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
                    }
                }
                chatJobs[msg.chatKey] = job
                job.invokeOnCompletion { chatJobs.remove(msg.chatKey, job) }
            }
        }
    }

    private suspend fun processMessage(
        msg: TelegramMessageProcessor.ParsedTelegramMessage,
        availability: TelegramReplySender.Availability,
        notificationKey: String,
        isStillPosted: (String) -> Boolean?
    ) {
        val now = clock()
        val chatName = msg.chatName
        touchProcessed(msg, now)

        if (preferences.isVoiceAnnouncementEnabled && preferences.isReadIncomingEnabled) {
            voiceAnnouncer.announceIncomingMessage(chatName, msg.text)
        }

        // 1. Group / unknown-sender policy
        if (msg.isGroup && preferences.ignoreGroupMessages) {
            log(TelegramLogEntity.SKIPPED_GROUP, chatName, msg.text, detail = "Group automation is OFF.")
            return
        }
        if (msg.isUnknownSender && preferences.ignoreUnknownSenders) {
            log(TelegramLogEntity.SKIPPED_UNKNOWN_SENDER, chatName, msg.text, detail = "Sender is not a saved contact name.")
            return
        }

        // 2. Echo of something we sent ourselves
        if (isOwnRecentReply(msg.chatKey, msg.text, now)) return

        // 3. Sensitive content never goes to AI or gets a reply
        val safety = TelegramSafetyFilter.inspectIncomingMessage(msg.text)
        if (!safety.isSafe) {
            log(TelegramLogEntity.SKIPPED_SENSITIVE, chatName, msg.text, detail = safety.reason, allowPreview = false)
            return
        }

        // 4. Chat rules
        val rules = database.ruleDao().getActiveRules()
        val outcome = TelegramContactRuleEngine.evaluate(
            msg.chatKey, chatName, msg.text, rules, preferences.allowlistOnly
        )
        when (outcome.action) {
            TelegramContactRuleEngine.Action.BLOCK ->
                return log(TelegramLogEntity.SKIPPED_RULE, chatName, msg.text, detail = "Chat is on the blocklist.")
            TelegramContactRuleEngine.Action.NOT_ALLOWLISTED ->
                return log(TelegramLogEntity.SKIPPED_RULE, chatName, msg.text, detail = "Chat is not on the allowlist.")
            TelegramContactRuleEngine.Action.IGNORE_KEYWORD ->
                return log(TelegramLogEntity.SKIPPED_RULE, chatName, msg.text, detail = "Matched an ignore keyword rule.")
            else -> Unit
        }

        // 5. Auto reply must be explicitly enabled
        if (!preferences.isAutoReplyEnabled) {
            log(TelegramLogEntity.AUTO_REPLY_OFF, chatName, null, detail = "Auto reply is turned OFF; nothing was sent.")
            return
        }

        // 6. Cooldown and rate limits (before spending an AI call)
        val cooldownSec = outcome.cooldownSeconds ?: preferences.defaultCooldownSeconds
        val stats = database.chatStatsDao().get(msg.chatKey)
        if (cooldownSec > 0 && stats != null && now - stats.lastReplyTime < cooldownSec * 1000L) {
            log(TelegramLogEntity.SKIPPED_COOLDOWN, chatName, msg.text, detail = "Cooldown of ${cooldownSec}s still active.")
            return
        }
        rateLimitReason(msg.chatKey, now)?.let {
            log(TelegramLogEntity.SKIPPED_RATE_LIMIT, chatName, msg.text, detail = it)
            return
        }

        // 7. Reply action must exist (and be bound to this very notification / chat)
        val holder = when (availability) {
            is TelegramReplySender.Availability.Available -> availability.holder
            is TelegramReplySender.Availability.Unavailable -> {
                log(TelegramLogEntity.NO_REPLY_ACTION, chatName, msg.text, detail = availability.reason)
                return
            }
        }

        // 8. Compose the reply
        val replyText: String
        var detail: String? = null
        val fixed = outcome.fixedReply
        if (fixed != null) {
            replyText = TelegramSafetyFilter.sanitizeOutgoingReply(fixed, 900)
            detail = "Rule reply"
        } else {
            if (!outcome.aiEnabled) {
                log(TelegramLogEntity.SKIPPED_AI_DISABLED, chatName, msg.text, detail = "AI replies are disabled for this chat.")
                return
            }
            val category = TelegramMessageClassifier.classify(msg.text)
            if (preferences.isGeminiEnabled) {
                val generated = replyEngine.generateReply(
                    GeminiTelegramReplyEngine.Request(
                        senderName = msg.senderName,
                        incomingMessage = msg.text,
                        category = category,
                        history = if (preferences.includeConversationContext) historyFor(msg.chatKey, now) else emptyList(),
                        chatInstruction = outcome.instruction,
                        operatorName = preferences.operatorName,
                        mode = preferences.replyMode,
                        language = preferences.languageMode,
                        personality = preferences.aiPersonality,
                        customPrompt = preferences.customPrompt
                    )
                )
                replyText = generated.text
                if (generated.source == GeminiTelegramReplyEngine.Source.OFFLINE_FALLBACK) {
                    detail = "Offline fallback: ${generated.error ?: "Gemini unavailable"}"
                    recordError("Gemini", generated.error ?: "Gemini unavailable")
                } else {
                    detail = "Gemini ${category.name}"
                }
            } else {
                replyText = replyEngine.generateOfflineFallback(msg.text, preferences.operatorName, category, preferences.languageMode)
                detail = "Gemini OFF (template reply)"
            }
        }
        if (replyText.isBlank()) {
            recordError("Manager", "Generated reply was empty; nothing sent.")
            return
        }
        rememberHistory(msg.chatKey, fromContact = true, text = msg.text, now = now)

        // 9. Human-like delay (cancellable by a newer message or by Master OFF)
        val delaySec = preferences.replyDelaySeconds
        if (delaySec > 0) delay(delaySec * 1000L)

        // 10. Re-validate right before sending
        if (!preferences.isMasterEnabled) return
        if (!preferences.isAutoReplyEnabled) {
            log(TelegramLogEntity.AUTO_REPLY_OFF, chatName, null, detail = "Auto reply was turned OFF before sending.")
            return
        }
        if (isStillPosted(notificationKey) == false) {
            log(TelegramLogEntity.SUPERSEDED, chatName, null,
                detail = "Notification was dismissed or answered before the reply was sent; nothing sent.")
            return
        }

        val sendResult = replySender.sendReply(holder, replyText)
        withContext(NonCancellable) {
            val sentAt = clock()
            when (sendResult) {
                is TelegramReplySender.SendResult.Success -> {
                    val current = database.chatStatsDao().get(msg.chatKey)
                    database.chatStatsDao().upsert(
                        TelegramChatStatsEntity(
                            chatKey = msg.chatKey,
                            chatName = chatName,
                            replyCount = (current?.replyCount ?: 0) + 1,
                            lastProcessedTime = sentAt,
                            lastReplyTime = sentAt
                        )
                    )
                    recordReplyForLimits(msg.chatKey, sentAt)
                    rememberOwnReply(msg.chatKey, replyText, sentAt)
                    rememberHistory(msg.chatKey, fromContact = false, text = replyText, now = sentAt)
                    log(TelegramLogEntity.REPLIED, chatName, msg.text, reply = replyText, detail = detail)
                    if (preferences.isVoiceAnnouncementEnabled) voiceAnnouncer.announceSentReply(chatName, replyText)
                }
                is TelegramReplySender.SendResult.NoReplyAction ->
                    log(TelegramLogEntity.NO_REPLY_ACTION, chatName, msg.text, detail = sendResult.reason)
                is TelegramReplySender.SendResult.Failure -> {
                    recordError("Sender", sendResult.error)
                    log(TelegramLogEntity.FAILED_SEND, chatName, msg.text, detail = sendResult.error)
                }
            }
            Unit
        }
    }

    /** Result of a dry run used by the Test dialog. Nothing is sent or counted. */
    sealed class PreviewResult {
        data class Reply(val text: String, val viaAi: Boolean, val note: String?) : PreviewResult()
        data class Blocked(val reason: String) : PreviewResult()
    }

    suspend fun previewReply(chatName: String, message: String): PreviewResult {
        val safety = TelegramSafetyFilter.inspectIncomingMessage(message)
        if (!safety.isSafe) return PreviewResult.Blocked("Security filter: ${safety.reason}")

        val outcome = TelegramContactRuleEngine.evaluate(
            "name:${chatName.lowercase()}", chatName, message,
            database.ruleDao().getActiveRules(), preferences.allowlistOnly
        )
        when (outcome.action) {
            TelegramContactRuleEngine.Action.BLOCK -> return PreviewResult.Blocked("Chat is blocklisted.")
            TelegramContactRuleEngine.Action.NOT_ALLOWLISTED -> return PreviewResult.Blocked("Chat is not on the allowlist.")
            TelegramContactRuleEngine.Action.IGNORE_KEYWORD -> return PreviewResult.Blocked("Ignore-keyword rule matched.")
            else -> Unit
        }
        outcome.fixedReply?.let { return PreviewResult.Reply(it, viaAi = false, note = "Fixed reply from rule") }
        if (!outcome.aiEnabled) return PreviewResult.Blocked("AI replies are disabled for this chat.")

        val category = TelegramMessageClassifier.classify(message)
        if (!preferences.isGeminiEnabled) {
            return PreviewResult.Reply(
                replyEngine.generateOfflineFallback(message, preferences.operatorName, category, preferences.languageMode),
                viaAi = false, note = "Gemini is OFF - template reply"
            )
        }
        val generated = replyEngine.generateReply(
            GeminiTelegramReplyEngine.Request(
                senderName = chatName,
                incomingMessage = message,
                category = category,
                chatInstruction = outcome.instruction,
                operatorName = preferences.operatorName,
                mode = preferences.replyMode,
                language = preferences.languageMode,
                personality = preferences.aiPersonality,
                customPrompt = preferences.customPrompt
            )
        )
        val viaAi = generated.source == GeminiTelegramReplyEngine.Source.GEMINI
        return PreviewResult.Reply(generated.text, viaAi,
            if (viaAi) "Category: ${category.name}" else "Offline fallback used: ${generated.error}")
    }

    // ---- limits / memory helpers -------------------------------------------------------------

    private fun rateLimitReason(chatKey: String, now: Long): String? {
        synchronized(globalReplyTimes) {
            while (globalReplyTimes.isNotEmpty() && now - globalReplyTimes.first() > HOUR_MS) globalReplyTimes.removeFirst()
            if (globalReplyTimes.size >= preferences.maxRepliesPerHour) {
                return "Hourly limit of ${preferences.maxRepliesPerHour} replies reached."
            }
        }
        val perChat = chatReplyTimes[chatKey]
        if (perChat != null) synchronized(perChat) {
            while (perChat.isNotEmpty() && now - perChat.first() > HOUR_MS) perChat.removeFirst()
            if (perChat.size >= preferences.maxRepliesPerChatPerHour) {
                return "Per-chat limit of ${preferences.maxRepliesPerChatPerHour} replies/hour reached."
            }
        }
        return null
    }

    private fun recordReplyForLimits(chatKey: String, time: Long) {
        synchronized(globalReplyTimes) { globalReplyTimes.addLast(time) }
        val q = chatReplyTimes.getOrPut(chatKey) { ArrayDeque() }
        synchronized(q) { q.addLast(time) }
    }

    private fun rememberOwnReply(chatKey: String, text: String, time: Long) {
        val q = ownReplies.getOrPut(chatKey) { ArrayDeque() }
        synchronized(q) {
            q.addLast(text.trim() to time)
            while (q.size > 5) q.removeFirst()
        }
    }

    private fun isOwnRecentReply(chatKey: String, text: String, now: Long): Boolean {
        val q = ownReplies[chatKey] ?: return false
        synchronized(q) {
            return q.any { (t, at) -> now - at < OWN_REPLY_TTL_MS && t == text.trim() }
        }
    }

    private fun rememberHistory(chatKey: String, fromContact: Boolean, text: String, now: Long) {
        if (history.size > MAX_TRACKED_CHATS && !history.containsKey(chatKey)) {
            history.entries.minByOrNull { it.value.lastOrNull()?.time ?: 0L }?.let { history.remove(it.key) }
        }
        val q = history.getOrPut(chatKey) { ArrayDeque() }
        synchronized(q) {
            q.addLast(HistoryItem(fromContact, text.take(300), now))
            while (q.size > MAX_HISTORY_PER_CHAT) q.removeFirst()
        }
    }

    private fun historyFor(chatKey: String, now: Long): List<GeminiTelegramReplyEngine.HistoryTurn> {
        val q = history[chatKey] ?: return emptyList()
        synchronized(q) {
            return q.filter { now - it.time < HISTORY_TTL_MS }
                .map { GeminiTelegramReplyEngine.HistoryTurn(it.fromContact, it.text) }
        }
    }

    // ---- persistence helpers -----------------------------------------------------------------

    private suspend fun touchProcessed(msg: TelegramMessageProcessor.ParsedTelegramMessage, now: Long) {
        try {
            val dao = database.chatStatsDao()
            val current = dao.get(msg.chatKey)
            dao.upsert(
                (current ?: TelegramChatStatsEntity(msg.chatKey, msg.chatName))
                    .copy(chatName = msg.chatName, lastProcessedTime = now)
            )
            _state.value = _state.value.copy(lastProcessedChat = msg.chatName, lastProcessedTime = now)
        } catch (e: Exception) {
            recordError("Database", "Stats update failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun log(
        status: String,
        chatName: String,
        incoming: String?,
        reply: String = "",
        detail: String? = null,
        allowPreview: Boolean = true
    ) {
        try {
            val showText = preferences.logMessagePreviews && allowPreview
            database.logDao().insertLog(
                TelegramLogEntity(
                    timestamp = clock(),
                    chatName = chatName,
                    incomingPreview = if (showText && incoming != null) TelegramSafetyFilter.redactForAi(incoming).take(80) else "",
                    replyText = if (showText) reply.take(160) else "",
                    status = status,
                    detail = detail?.take(200)
                )
            )
            database.logDao().prune(clock() - LOG_RETENTION_MS, LOG_KEEP)
        } catch (e: Exception) {
            Log.e(TAG, "Could not write log: ${e.message}")
        }
    }

    private fun recordError(component: String, message: String) {
        Log.w(TAG, "[$component] $message")
        scope.launch {
            try {
                database.errorDao().insertError(TelegramErrorEntity(timestamp = clock(), component = component, message = message.take(300)))
                database.errorDao().prune(clock() - LOG_RETENTION_MS, 100)
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        cancelPendingReplies()
        scope.coroutineContext.cancelChildren()
        voiceAnnouncerInstance?.shutdown()
        voiceAnnouncerInstance = null
    }
}
