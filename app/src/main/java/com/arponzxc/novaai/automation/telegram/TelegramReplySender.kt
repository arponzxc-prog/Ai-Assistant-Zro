package com.arponzxc.novaai.automation.telegram

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Sends replies through the notification's own inline-reply action (RemoteInput), exactly as if
 * the user typed into the reply field of the notification. No Telegram account/API access is used.
 */
class TelegramReplySender(private val context: Context) {

    companion object {
        private const val TAG = "TelegramReplySender"
    }

    data class ReplyActionHolder(
        val notificationKey: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val resultKey: String
    )

    sealed class Availability {
        data class Available(val holder: ReplyActionHolder) : Availability()
        data class Unavailable(val reason: String) : Availability()
    }

    sealed class SendResult {
        object Success : SendResult()
        data class NoReplyAction(val reason: String) : SendResult()
        data class Failure(val error: String) : SendResult()
    }

    /** Inspects the notification and says clearly whether (and why not) an inline reply is possible. */
    fun checkAvailability(sbn: StatusBarNotification): Availability {
        val notification = sbn.notification ?: return Availability.Unavailable("Notification has no content.")
        val actions = notification.actions
            ?: return Availability.Unavailable("Notification has no actions (channel, muted chat or reply disabled).")

        val candidates = actions.filter { !it.remoteInputs.isNullOrEmpty() }
        if (candidates.isEmpty()) {
            return Availability.Unavailable("Notification has no inline reply (RemoteInput) action.")
        }

        // Prefer the action Telegram marks as REPLY, otherwise the first free-form text action.
        val preferred = candidates.firstOrNull {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: candidates.first()

        val inputs = preferred.remoteInputs
        val freeForm = inputs.firstOrNull { it.allowFreeFormInput && it.resultKey.isNotBlank() }
            ?: return Availability.Unavailable("Reply action does not accept free-form text.")
        val pi = preferred.actionIntent
            ?: return Availability.Unavailable("Reply action has no PendingIntent.")

        val key = sbn.key ?: "${sbn.packageName}:${sbn.id}:${sbn.tag}"
        return Availability.Available(ReplyActionHolder(key, pi, inputs, freeForm.resultKey))
    }

    fun sendReply(holder: ReplyActionHolder, replyText: String): SendResult {
        if (replyText.isBlank()) return SendResult.Failure("Empty reply.")
        return try {
            val replyIntent = Intent()
            val results = Bundle().apply { putCharSequence(holder.resultKey, replyText) }
            RemoteInput.addResultsToIntent(holder.remoteInputs, replyIntent, results)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(replyIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            holder.pendingIntent.send(context, 0, replyIntent)
            Log.d(TAG, "Inline reply dispatched (${replyText.length} chars).")
            SendResult.Success
        } catch (e: PendingIntent.CanceledException) {
            SendResult.Failure("Reply action is no longer valid (notification was dismissed).")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send inline reply: ${e.message}", e)
            SendResult.Failure("Send failed: ${e.message}")
        }
    }
}
