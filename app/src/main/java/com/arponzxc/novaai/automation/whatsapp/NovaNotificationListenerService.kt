package com.arponzxc.novaai.automation.whatsapp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.automation.telegram.TelegramAutomationManager

/**
 * Single notification listener shared by WhatsApp and Telegram automation.
 * Each platform has its own independent manager; notifications are routed by package name.
 * (The class stays in this package so the existing manifest entry and permission grant keep working.)
 */
class NovaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NovaNotifListener"

        /**
         * Checks if the NotificationListenerService permission has been granted by user.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            return enabledListeners.contains(context.packageName)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NovaNotificationListenerService connected.")
        try {
            JarvisApp.instance.whatsAppAutomationManager.updateServiceConnected(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update service connected state: ${e.message}")
        }
        try {
            JarvisApp.instance.telegramAutomationManager.updateServiceConnected(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Telegram service connected state: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NovaNotificationListenerService disconnected.")
        try {
            JarvisApp.instance.whatsAppAutomationManager.updateServiceConnected(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update service disconnected state: ${e.message}")
        }
        try {
            JarvisApp.instance.telegramAutomationManager.updateServiceConnected(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update Telegram service disconnected state: ${e.message}")
        }
        // Ask the system to reconnect us (only if the user still has the permission granted).
        try {
            if (isPermissionGranted(applicationContext)) {
                NotificationListenerService.requestRebind(ComponentName(applicationContext, NovaNotificationListenerService::class.java))
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            try {
                JarvisApp.instance.whatsAppAutomationManager.onNotificationReceived(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling WhatsApp notification: ${e.message}", e)
            }
        } else if (TelegramAutomationManager.isTelegramPackage(pkg)) {
            try {
                JarvisApp.instance.telegramAutomationManager.onNotificationReceived(sbn) { key -> isNotificationStillActive(key) }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling Telegram notification: ${e.message}", e)
            }
        }
    }

    /** true/false when the active-notification list can be read, null when it cannot. */
    private fun isNotificationStillActive(key: String): Boolean? {
        return try {
            activeNotifications?.any { it.key == key }
        } catch (e: Exception) {
            null
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // No-op for removed notifications
    }
}
