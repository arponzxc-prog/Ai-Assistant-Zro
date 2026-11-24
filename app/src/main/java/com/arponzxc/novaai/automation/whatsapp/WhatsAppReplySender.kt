package com.arponzxc.novaai.automation.whatsapp

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log

class WhatsAppReplySender(private val context: Context) {

    companion object {
        private const val TAG = "WhatsAppReplySender"
    }

    data class ReplyActionHolder(
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val resultKey: String
    )

    sealed class SendResult {
        object Success : SendResult()
        data class NoReplyAction(val reason: String) : SendResult()
        data class Failure(val error: String) : SendResult()
    }

    /**
     * Inspects a StatusBarNotification to discover inline reply action and RemoteInput.
     */
    fun findReplyAction(sbn: StatusBarNotification): ReplyActionHolder? {
        val notification = sbn.notification ?: return null
        val actions = notification.actions ?: return null

        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (ri in remoteInputs) {
                // Look for action with valid resultKey for messaging reply
                val resultKey = ri.resultKey
                if (!resultKey.isNullOrBlank()) {
                    val pIntent = action.actionIntent ?: continue
                    return ReplyActionHolder(
                        pendingIntent = pIntent,
                        remoteInputs = remoteInputs,
                        resultKey = resultKey
                    )
                }
            }
        }
        return null
    }

    /**
     * Dispatches the automated reply directly to the WhatsApp notification via RemoteInput.
     */
    fun sendReply(actionHolder: ReplyActionHolder, replyText: String): SendResult {
        return try {
            val replyIntent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(actionHolder.resultKey, replyText)
            }

            RemoteInput.addResultsToIntent(
                actionHolder.remoteInputs,
                replyIntent,
                bundle
            )

            // Specify flag 0 for existing PendingIntent compatibility
            actionHolder.pendingIntent.send(context, 0, replyIntent)
            Log.d(TAG, "Successfully sent WhatsApp inline reply: $replyText")
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp inline reply: ${e.message}", e)
            SendResult.Failure("Send failed: ${e.message}")
        }
    }
}
