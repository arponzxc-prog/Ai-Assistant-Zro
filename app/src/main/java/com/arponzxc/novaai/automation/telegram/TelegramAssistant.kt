package com.arponzxc.novaai.automation.telegram

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * User-initiated Telegram helper (the counterpart of WhatsAppAssistant): prepares a message and
 * hands it to the official Telegram app through standard Android intents. The user always
 * confirms and sends inside Telegram itself; nothing is sent in the background.
 */
class TelegramAssistant(private val context: Context) {

    companion object {
        private const val TAG = "TelegramAssistant"
        private val USERNAME = Regex("^@?[A-Za-z][A-Za-z0-9_]{4,31}$")
    }

    sealed class Result {
        data class Opened(val message: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Returns the installed official Telegram package, or null. */
    fun installedTelegramPackage(): String? {
        val pm = context.packageManager
        return TelegramMessageProcessor.TELEGRAM_PACKAGES.firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0); true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun isTelegramInstalled(): Boolean = installedTelegramPackage() != null

    /** Opens Telegram's share sheet with [message] prefilled so the user picks the chat and sends. */
    fun shareDraft(message: String): Result {
        val text = message.trim()
        if (text.isBlank()) return Result.Failed("Message is empty.")
        val pkg = installedTelegramPackage() ?: return Result.Failed("Telegram is not installed.")
        return launch(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "Telegram opened with your draft. Choose the chat and tap send."
        )
    }

    /** Opens a public @username chat with the draft text prefilled (user still presses send). */
    fun openChatWithDraft(username: String, message: String): Result {
        val clean = username.trim()
        if (!USERNAME.matches(clean)) return Result.Failed("'$username' is not a valid Telegram username.")
        val pkg = installedTelegramPackage() ?: return Result.Failed("Telegram is not installed.")
        val uri = Uri.parse("https://t.me/${clean.removePrefix("@")}").buildUpon().apply {
            if (message.isNotBlank()) appendQueryParameter("text", message.trim())
        }.build()
        return launch(
            Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) },
            "Opened Telegram chat with @${clean.removePrefix("@")}."
        )
    }

    private fun launch(intent: Intent, success: String): Result {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Result.Opened(success)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Telegram could not handle intent: ${e.message}")
            Result.Failed("Telegram could not open this request.")
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed: ${e.message}", e)
            Result.Failed("Unable to open Telegram: ${e.message}")
        }
    }
}
