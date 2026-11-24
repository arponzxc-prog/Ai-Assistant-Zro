package com.arponzxc.novaai.devstudio.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives PendingIntent result callbacks from Termux RunCommandService (com.termux.RUN_COMMAND).
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val executionId = intent.getStringExtra(EXTRA_EXECUTION_ID).orEmpty()
        val resultBundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
        TermuxBridge.onTermuxResultReceived(executionId, resultBundle)
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "nova_termux_execution_id"
        const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
    }
}
