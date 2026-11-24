package com.arponzxc.novaai.automation.whatsapp

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppConversationEntity
import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppDatabase
import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashMap

class WhatsAppAutomationManager(
    private val context: Context,
    val preferences: WhatsAppAutomationPreferences = WhatsAppAutomationPreferences(context),
    val database: WhatsAppDatabase = WhatsAppDatabase.getInstance(context),
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gemini-1.5-flash" }
) {

    companion object {
        private const val TAG = "WhatsAppAutoManager"
        private const val DEDUPLICATION_WINDOW_MS = 60_000L // 1 minute
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replySender = WhatsAppReplySender(context)
    val replyEngine = GeminiWhatsAppReplyEngine(apiKeyProvider, modelProvider)
    private val voiceAnnouncer = WhatsAppVoiceAnnouncer(context)

    // In-memory LRU cache to prevent processing identical notifications repeatedly
    private val processedMessageKeys = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 150
            }
        }
    )

    data class AutomationState(
        val isServiceConnected: Boolean = false,
        val isMasterEnabled: Boolean = true,
        val isAutoReplyEnabled: Boolean = true,
        val totalRepliesCount: Int = 0,
        val lastProcessedSender: String = "",
        val lastProcessedTime: Long = 0L
    )

    private val _automationState = MutableStateFlow(
        AutomationState(
            isMasterEnabled = preferences.isMasterEnabled,
            isAutoReplyEnabled = preferences.isAutoReplyEnabled,
            totalRepliesCount = preferences.totalRepliesCount
        )
    )
    val automationState: StateFlow<AutomationState> = _automationState.asStateFlow()

    fun updateServiceConnected(connected: Boolean) {
        _automationState.value = _automationState.value.copy(isServiceConnected = connected)
    }

    /**
     * Extracts sender, text, and conversation info from incoming WhatsApp notification.
     */
    fun onNotificationReceived(sbn: StatusBarNotification) {
        if (!preferences.isMasterEnabled) {
            Log.d(TAG, "WhatsApp Automation is disabled in settings.")
            return
        }

        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") {
            return
        }

        val notification = sbn.notification ?: return

        // Ignore summary bundle notifications and ongoing system status notifications
        if ((notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0 ||
            (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0 ||
            notification.category == android.app.Notification.CATEGORY_PROGRESS
        ) {
            return
        }

        val extras = notification.extras ?: return

        // 1. Extract Title & Text
        var senderName = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() ?: "WhatsApp Contact"
        var messageText = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        var conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString()?.trim()
        var messageTimestamp = sbn.postTime

        // 2. Support MessagingStyle messages and bundled messages
        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (messagingStyle != null) {
            val styleTitle = messagingStyle.conversationTitle?.toString()?.trim()
            if (!styleTitle.isNullOrBlank()) {
                conversationTitle = styleTitle
            }
            val messages = messagingStyle.messages
            if (messages.isNotEmpty()) {
                val latest = messages.last()
                val personName = latest.person?.name?.toString()?.trim()
                if (!personName.isNullOrBlank()) {
                    senderName = personName
                }
                val text = latest.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    messageText = text
                }
                if (latest.timestamp > 0L) {
                    messageTimestamp = latest.timestamp
                }
            }
        }

        if (messageText.isBlank()) {
            return
        }

        // 3. Filter out group summary counts, system prompts, or outgoing echo headers (e.g. "You: ...")
        if (senderName.startsWith("You", ignoreCase = true) ||
            messageText.startsWith("You: ") ||
            messageText.equals("Checking for new messages", ignoreCase = true) ||
            senderName.equals("WhatsApp", ignoreCase = true) && messageText.contains("new messages", ignoreCase = true)
        ) {
            return
        }

        // Clean sender from notification subtexts (e.g. "John (2 messages)")
        val cleanSender = senderName.replace(Regex("\\(\\d+\\s+messages?\\)"), "").trim()
        val conversationKey = if (!conversationTitle.isNullOrBlank() && !conversationTitle.equals(cleanSender, ignoreCase = true)) {
            "$conversationTitle ($cleanSender)"
        } else {
            cleanSender
        }

        // 4. Deduplication using sender + message + timestamp bucket
        val msgKey = "$conversationKey:${messageText.hashCode()}:$messageTimestamp"
        val now = System.currentTimeMillis()
        val lastSeen = processedMessageKeys[msgKey]
        if (lastSeen != null && (now - lastSeen) < DEDUPLICATION_WINDOW_MS) {
            Log.d(TAG, "Skipping duplicate notification for $conversationKey within window.")
            return
        }
        processedMessageKeys[msgKey] = now

        // Discover RemoteInput action immediately on the notification thread
        val replyAction = replySender.findReplyAction(sbn)
        if (replyAction == null && messagingStyle == null) {
            Log.d(TAG, "Skipping non-actionable WhatsApp notification from $cleanSender")
            return
        }

        // Process message asynchronously
        scope.launch {
            processMessageAsync(conversationKey, messageText, replyAction)
        }
    }

    private suspend fun processMessageAsync(
        senderName: String,
        messageText: String,
        replyAction: WhatsAppReplySender.ReplyActionHolder?
    ) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "Processing WhatsApp message from $senderName: $messageText")

        // 1. Save incoming message to database
        val incomingEntity = WhatsAppConversationEntity(
            contactKey = senderName,
            senderName = senderName,
            messageText = messageText,
            isIncoming = true,
            timestamp = now
        )
        database.conversationDao().insertMessage(incomingEntity)

        // 2. Voice announcement if enabled
        if (preferences.isVoiceAnnouncementEnabled && preferences.isReadIncomingEnabled) {
            voiceAnnouncer.announceIncomingMessage(senderName, messageText)
        }

        // 3. Safety Check
        val safetyResult = WhatsAppSafetyFilter.inspectIncomingMessage(messageText)
        if (!safetyResult.isSafe) {
            val reason = safetyResult.reason ?: "Safety filter triggered."
            val fallback = safetyResult.safeFallbackReply ?: "Unable to process sensitive request."
            Log.w(TAG, "Message rejected by safety filter: $reason")
            database.logDao().insertLog(
                WhatsAppLogEntity(
                    timestamp = now,
                    senderName = senderName,
                    incomingText = messageText,
                    replyText = fallback,
                    status = "SKIPPED_SENSITIVE",
                    errorMessage = reason
                )
            )

            // Optionally reply with safe warning if auto-reply is on
            if (preferences.isAutoReplyEnabled && replyAction != null) {
                dispatchReplyWithDelay(senderName, fallback, replyAction)
            }
            return
        }

        // 4. Personalized Rules Engine
        val activeRules = database.ruleDao().getActiveRules()
        val ruleDecision = ContactRuleEngine.evaluateRules(senderName, activeRules)

        var finalReply = ""
        var ruleInstruction: String? = null

        when (ruleDecision) {
            is ContactRuleEngine.RuleDecision.Ignore -> {
                Log.d(TAG, "Rule matched IGNORE for contact: $senderName")
                database.logDao().insertLog(
                    WhatsAppLogEntity(
                        timestamp = now,
                        senderName = senderName,
                        incomingText = messageText,
                        replyText = "[IGNORED BY RULE]",
                        status = "SKIPPED_RULE"
                    )
                )
                return
            }
            is ContactRuleEngine.RuleDecision.FixedReply -> {
                finalReply = ruleDecision.replyText
                Log.d(TAG, "Rule matched FIXED_REPLY for contact: $senderName -> $finalReply")
            }
            is ContactRuleEngine.RuleDecision.CustomInstruction -> {
                ruleInstruction = ruleDecision.instruction
                Log.d(TAG, "Rule matched CUSTOM_INSTRUCTION for contact: $senderName -> $ruleInstruction")
            }
            ContactRuleEngine.RuleDecision.NoRuleMatched -> {
                // Default handling
            }
        }

        // 5. Generate AI Reply with Gemini if not fixed reply
        if (finalReply.isBlank()) {
            if (preferences.isGeminiBrainEnabled) {
                val recentHistory = if (preferences.includeMemoryContext) {
                    database.conversationDao().getRecentMessages(senderName, limit = 8)
                } else emptyList()

                val result = replyEngine.generateReply(
                    senderName = senderName,
                    incomingMessage = messageText,
                    recentHistory = recentHistory,
                    customInstruction = ruleInstruction,
                    operatorName = preferences.operatorName
                )
                finalReply = result.getOrElse {
                    replyEngine.generateOfflineFallback(messageText, preferences.operatorName)
                }
            } else {
                finalReply = replyEngine.generateOfflineFallback(messageText, preferences.operatorName)
            }
        }

        finalReply = WhatsAppSafetyFilter.sanitizeOutgoingReply(finalReply)

        // 6. Send Reply via notification action if auto reply is enabled
        if (preferences.isAutoReplyEnabled) {
            if (replyAction != null) {
                val sendResult = dispatchReplyWithDelay(senderName, finalReply, replyAction)
                when (sendResult) {
                    is WhatsAppReplySender.SendResult.Success -> {
                        database.logDao().insertLog(
                            WhatsAppLogEntity(
                                timestamp = System.currentTimeMillis(),
                                senderName = senderName,
                                incomingText = messageText,
                                replyText = finalReply,
                                status = "REPLIED"
                            )
                        )
                    }
                    is WhatsAppReplySender.SendResult.NoReplyAction -> {
                        database.logDao().insertLog(
                            WhatsAppLogEntity(
                                timestamp = System.currentTimeMillis(),
                                senderName = senderName,
                                incomingText = messageText,
                                replyText = finalReply,
                                status = "FAILED_NO_ACTION",
                                errorMessage = sendResult.reason
                            )
                        )
                    }
                    is WhatsAppReplySender.SendResult.Failure -> {
                        database.logDao().insertLog(
                            WhatsAppLogEntity(
                                timestamp = System.currentTimeMillis(),
                                senderName = senderName,
                                incomingText = messageText,
                                replyText = finalReply,
                                status = "FAILED_SEND",
                                errorMessage = sendResult.error
                            )
                        )
                    }
                }
            } else {
                Log.w(TAG, "No inline reply RemoteInput available for notification from $senderName.")
                database.logDao().insertLog(
                    WhatsAppLogEntity(
                        timestamp = System.currentTimeMillis(),
                        senderName = senderName,
                        incomingText = messageText,
                        replyText = finalReply,
                        status = "FAILED_NO_ACTION",
                        errorMessage = "Notification did not provide RemoteInput reply action."
                    )
                )
            }
        } else {
            database.logDao().insertLog(
                WhatsAppLogEntity(
                    timestamp = System.currentTimeMillis(),
                    senderName = senderName,
                    incomingText = messageText,
                    replyText = finalReply,
                    status = "GENERATED_ONLY",
                    errorMessage = "Auto-reply toggle is turned OFF."
                )
            )
        }

        _automationState.value = _automationState.value.copy(
            lastProcessedSender = senderName,
            lastProcessedTime = System.currentTimeMillis()
        )
    }

    private suspend fun dispatchReplyWithDelay(
        senderName: String,
        replyText: String,
        replyAction: WhatsAppReplySender.ReplyActionHolder
    ): WhatsAppReplySender.SendResult {
        val delaySec = preferences.replyDelaySeconds
        if (delaySec > 0) {
            Log.d(TAG, "Waiting $delaySec seconds to simulate human reply delay...")
            delay(delaySec * 1000L)
        }

        val sendResult = replySender.sendReply(replyAction, replyText)
        if (sendResult is WhatsAppReplySender.SendResult.Success) {
            preferences.incrementRepliesCount()
            _automationState.value = _automationState.value.copy(
                totalRepliesCount = preferences.totalRepliesCount
            )

            // Save our reply to conversation history for future context
            val replyEntity = WhatsAppConversationEntity(
                contactKey = senderName,
                senderName = "NOVA AI",
                messageText = replyText,
                isIncoming = false,
                timestamp = System.currentTimeMillis()
            )
            database.conversationDao().insertMessage(replyEntity)

            // Vocalize reply if voice announcements are enabled
            if (preferences.isVoiceAnnouncementEnabled) {
                voiceAnnouncer.announceSentReply(senderName, replyText)
            }
        }
        return sendResult
    }

    fun shutdown() {
        voiceAnnouncer.shutdown()
    }
}
