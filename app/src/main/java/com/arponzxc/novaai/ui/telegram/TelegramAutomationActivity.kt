package com.arponzxc.novaai.ui.telegram

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.automation.telegram.TelegramAutomationManager
import com.arponzxc.novaai.automation.telegram.TelegramAutomationPreferences
import com.arponzxc.novaai.automation.telegram.db.TelegramLogEntity
import com.arponzxc.novaai.automation.telegram.db.TelegramRuleEntity
import com.arponzxc.novaai.automation.whatsapp.NovaNotificationListenerService
import com.arponzxc.novaai.databinding.ActivityTelegramAutomationBinding
import com.arponzxc.novaai.databinding.ItemTelegramLogBinding
import com.arponzxc.novaai.databinding.ItemTelegramRuleBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TelegramAutomationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTelegramAutomationBinding
    private val manager: TelegramAutomationManager get() = JarvisApp.instance.telegramAutomationManager
    private val prefs: TelegramAutomationPreferences get() = manager.preferences
    private val database get() = manager.database

    /** Prevents programmatic switch changes from being treated as user actions. */
    private var isBindingPrefs = false

    private val rulesAdapter = RulesAdapter { rule -> confirmDeleteRule(rule) }
    private val logsAdapter = LogsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = rulesAdapter
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logsAdapter

        setupListeners()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        refreshStatus()
    }

    // ---- status -------------------------------------------------------------------------------

    private fun refreshStatus() {
        val granted = NovaNotificationListenerService.isPermissionGranted(this)
        val connected = manager.automationState.value.isServiceConnected
        val masterOn = prefs.isMasterEnabled
        val installed = JarvisApp.instance.telegramAssistant.isTelegramInstalled()

        binding.cardPermissionWarning.visibility = if (granted) View.GONE else View.VISIBLE
        binding.cardNotInstalled.visibility = if (installed) View.GONE else View.VISIBLE

        val badge = binding.tvServiceStatusBadge
        when {
            !granted -> setBadge(badge, "PERMISSION REQUIRED", "#EF4444", R.drawable.bg_jarvis_error_card)
            !masterOn -> {
                badge.text = "AUTOMATION OFF"
                badge.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
                badge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            }
            !connected -> setBadge(badge, "SERVICE DISCONNECTED", "#F59E0B", R.drawable.bg_jarvis_status_badge)
            !prefs.isAutoReplyEnabled -> setBadge(badge, "MONITORING (AUTO REPLY OFF)", "#F59E0B", R.drawable.bg_jarvis_status_badge)
            else -> setBadge(badge, "MONITORING ACTIVE", "#10B981", R.drawable.bg_jarvis_status_badge_green)
        }

        binding.tvMasterSubtitle.text = if (masterOn) "Telegram automation is ON" else "Telegram automation is OFF"
        binding.tvConnectionStatus.text = buildString {
            append("Notification permission: ").append(if (granted) "granted" else "missing")
            append("  •  Listener: ").append(if (connected) "connected" else "not connected")
            append("  •  Telegram app: ").append(if (installed) "installed" else "not found")
        }
    }

    private fun setBadge(view: android.widget.TextView, text: String, color: String, bg: Int) {
        view.text = text
        view.setTextColor(Color.parseColor(color))
        view.setBackgroundResource(bg)
    }

    private fun loadPreferences() {
        isBindingPrefs = true
        binding.switchMasterAutomation.isChecked = prefs.isMasterEnabled
        binding.switchAutoReply.isChecked = prefs.isAutoReplyEnabled
        binding.switchGeminiBrain.isChecked = prefs.isGeminiEnabled
        binding.switchContext.isChecked = prefs.includeConversationContext
        binding.switchIgnoreGroups.isChecked = prefs.ignoreGroupMessages
        binding.switchIgnoreUnknown.isChecked = prefs.ignoreUnknownSenders
        binding.switchAllowlistOnly.isChecked = prefs.allowlistOnly
        binding.switchLogPreviews.isChecked = prefs.logMessagePreviews
        binding.switchVoiceAnnouncement.isChecked = prefs.isVoiceAnnouncementEnabled
        binding.switchReadIncoming.isChecked = prefs.isReadIncomingEnabled
        isBindingPrefs = false

        binding.tvReplyModeValue.text = prefs.replyMode.name
        binding.tvLanguageValue.text = prefs.languageMode.name
        binding.tvPersonalityValue.text = prefs.aiPersonality.ifBlank { "Default" }
        binding.tvCustomPromptValue.text = prefs.customPrompt.ifBlank { "None" }
        binding.tvOperatorName.text = prefs.operatorName
        binding.tvReplyDelayValue.text = if (prefs.replyDelaySeconds == 0) "INSTANT" else "${prefs.replyDelaySeconds} SECONDS"
        binding.tvCooldownValue.text = "${prefs.defaultCooldownSeconds} s"
        binding.tvMaxPerHourValue.text = prefs.maxRepliesPerHour.toString()
    }

    // ---- listeners ----------------------------------------------------------------------------

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.switchMasterAutomation.setOnCheckedChangeListener { _, checked ->
            if (isBindingPrefs) return@setOnCheckedChangeListener
            manager.setMasterEnabled(checked)
            refreshStatus()
        }

        binding.switchAutoReply.setOnCheckedChangeListener { _, checked ->
            if (isBindingPrefs) return@setOnCheckedChangeListener
            if (checked) confirmEnableAutoReply() else {
                manager.setAutoReplyEnabled(false)
                refreshStatus()
            }
        }

        binding.switchGeminiBrain.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.isGeminiEnabled = c }
        binding.switchContext.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.includeConversationContext = c }
        binding.switchIgnoreGroups.setOnCheckedChangeListener { _, c ->
            if (isBindingPrefs) return@setOnCheckedChangeListener
            if (!c) confirmEnableGroups() else { prefs.ignoreGroupMessages = true }
        }
        binding.switchIgnoreUnknown.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.ignoreUnknownSenders = c }
        binding.switchAllowlistOnly.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.allowlistOnly = c }
        binding.switchLogPreviews.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.logMessagePreviews = c }
        binding.switchVoiceAnnouncement.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.isVoiceAnnouncementEnabled = c }
        binding.switchReadIncoming.setOnCheckedChangeListener { _, c -> if (!isBindingPrefs) prefs.isReadIncomingEnabled = c }

        binding.rowReplyMode.setOnClickListener { showReplyModeDialog() }
        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowPersonality.setOnClickListener {
            showTextDialog("AI Personality", "e.g. friendly, calm and a little humorous", prefs.aiPersonality, false) {
                prefs.aiPersonality = it; loadPreferences()
            }
        }
        binding.rowCustomPrompt.setOnClickListener {
            showTextDialog("Custom Prompt", "Standing instructions for every Telegram reply", prefs.customPrompt, true) {
                prefs.customPrompt = it; loadPreferences()
            }
        }
        binding.rowOperatorName.setOnClickListener {
            showTextDialog("Operator Name", "Name NOVA AI speaks on behalf of", prefs.operatorName, false) {
                if (it.isNotBlank()) { prefs.operatorName = it; loadPreferences() }
            }
        }
        binding.rowReplyDelay.setOnClickListener { showNumberChoiceDialog(
            "Reply Delay", arrayOf("Instant", "1 s", "3 s (recommended)", "5 s", "10 s", "15 s"),
            intArrayOf(0, 1, 3, 5, 10, 15), prefs.replyDelaySeconds
        ) { prefs.replyDelaySeconds = it; loadPreferences() } }
        binding.rowCooldown.setOnClickListener { showNumberChoiceDialog(
            "Default Cooldown Per Chat", arrayOf("None", "30 s", "60 s (recommended)", "5 min", "15 min", "1 hour"),
            intArrayOf(0, 30, 60, 300, 900, 3600), prefs.defaultCooldownSeconds
        ) { prefs.defaultCooldownSeconds = it; loadPreferences() } }
        binding.rowMaxPerHour.setOnClickListener { showNumberChoiceDialog(
            "Max Replies Per Hour", arrayOf("5", "10", "20 (recommended)", "50", "100"),
            intArrayOf(5, 10, 20, 50, 100), prefs.maxRepliesPerHour
        ) { prefs.maxRepliesPerHour = it; loadPreferences() } }

        binding.btnAddRule.setOnClickListener { showAddRuleDialog() }
        binding.btnTestAi.setOnClickListener { showTestDialog() }
        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                database.logDao().clearLogs()
                Toast.makeText(this@TelegramAutomationActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnClearErrors.setOnClickListener {
            lifecycleScope.launch { database.errorDao().clearErrors() }
        }
    }

    private fun confirmEnableAutoReply() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable automatic replies?")
            .setMessage(
                "NOVA AI will answer Telegram messages on your behalf using the notification Reply action. " +
                    "Messages that look like OTPs, passwords or financial data are never answered. " +
                    "Groups stay ignored unless you change that. You can turn this off at any time."
            )
            .setPositiveButton("Enable") { _, _ ->
                manager.setAutoReplyEnabled(true)
                refreshStatus()
            }
            .setNegativeButton("Cancel") { _, _ -> loadPreferences() }
            .setOnCancelListener { loadPreferences() }
            .show()
    }

    private fun confirmEnableGroups() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow replies in groups?")
            .setMessage("Automatic replies in group chats can be noisy and are OFF by default. Rate limits and cooldowns still apply.")
            .setPositiveButton("Allow") { _, _ -> prefs.ignoreGroupMessages = false }
            .setNegativeButton("Keep ignoring") { _, _ -> loadPreferences() }
            .setOnCancelListener { loadPreferences() }
            .show()
    }

    // ---- data ---------------------------------------------------------------------------------

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun observeData() {
        val since = startOfToday()
        lifecycleScope.launch {
            database.ruleDao().getAllRulesFlow().collectLatest { rules ->
                binding.tvNoRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
                binding.rvRules.visibility = if (rules.isEmpty()) View.GONE else View.VISIBLE
                rulesAdapter.submitList(rules)
            }
        }
        lifecycleScope.launch {
            database.logDao().getRecentLogsFlow().collectLatest { logs ->
                binding.tvNoLogs.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.rvLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
                logsAdapter.submitList(logs)
            }
        }
        lifecycleScope.launch { database.logDao().repliedSinceFlow(since).collectLatest { binding.tvStatToday.text = it.toString() } }
        lifecycleScope.launch { database.logDao().skippedSinceFlow(since).collectLatest { binding.tvStatSkipped.text = it.toString() } }
        lifecycleScope.launch { database.errorDao().errorsSinceFlow(since).collectLatest { binding.tvStatErrors.text = it.toString() } }
        lifecycleScope.launch {
            database.chatStatsDao().totalRepliesFlow().collectLatest {
                binding.tvStatTotal.text = it.toString()
                binding.tvTotalRepliesBadge.text = "$it REPLIES SENT"
            }
        }
        lifecycleScope.launch {
            database.chatStatsDao().lastProcessedFlow().collectLatest { ts ->
                binding.tvLastProcessed.text = if (ts <= 0L) "Last processed: never"
                else "Last processed: " + SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ts))
            }
        }
        lifecycleScope.launch {
            database.errorDao().latestErrorFlow().collectLatest { err ->
                binding.tvLastError.text = if (err == null) "No errors recorded." else
                    "${SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(err.timestamp))}  [${err.component}]\n${err.message}"
                binding.tvLastError.setTextColor(if (err == null) ContextCompat.getColor(this@TelegramAutomationActivity, R.color.status_green) else Color.parseColor("#EF4444"))
            }
        }
        lifecycleScope.launch { manager.automationState.collectLatest { refreshStatus() } }
    }

    // ---- dialogs ------------------------------------------------------------------------------

    private fun showReplyModeDialog() {
        val modes = TelegramAutomationPreferences.ReplyMode.values()
        val labels = arrayOf("Short (1-2 sentences)", "Detailed (3-5 sentences)", "Professional (formal tone)")
        MaterialAlertDialogBuilder(this).setTitle("Reply Mode")
            .setSingleChoiceItems(labels, modes.indexOf(prefs.replyMode)) { d, which ->
                prefs.replyMode = modes[which]; loadPreferences(); d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showLanguageDialog() {
        val modes = TelegramAutomationPreferences.LanguageMode.values()
        val labels = arrayOf("Auto-detect (match the sender)", "Always Bengali (বাংলা)", "Always English")
        MaterialAlertDialogBuilder(this).setTitle("Reply Language")
            .setSingleChoiceItems(labels, modes.indexOf(prefs.languageMode)) { d, which ->
                prefs.languageMode = modes[which]; loadPreferences(); d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showNumberChoiceDialog(title: String, labels: Array<String>, values: IntArray, current: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { d, which ->
                onPick(values[which]); d.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showTextDialog(title: String, hint: String, current: String, multiline: Boolean, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setText(current)
            setSelection(text.length)
            if (multiline) { minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        }
        val container = LinearLayout(this).apply { setPadding(48, 16, 48, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(container)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAddRuleDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }

        val etChat = EditText(this).apply { hint = "Chat name exactly as in Telegram (or * for all chats)" }
        container.addView(etChat)

        val types = listOf(
            TelegramRuleEntity.RULE_INSTRUCTION to "AI reply with custom instruction",
            TelegramRuleEntity.RULE_FIXED_REPLY to "Fixed reply (no AI)",
            TelegramRuleEntity.RULE_ALLOW to "Allowlist this chat",
            TelegramRuleEntity.RULE_BLOCK to "Blocklist this chat (never reply)",
            TelegramRuleEntity.RULE_KEYWORD_REPLY to "Keyword → fixed reply",
            TelegramRuleEntity.RULE_KEYWORD_IGNORE to "Keyword → ignore message"
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; setPadding(0, 12, 0, 12) }
        val ids = types.map { (_, label) ->
            RadioButton(this).apply { text = label; id = View.generateViewId() }.also { group.addView(it) }.id
        }
        group.check(ids[0])
        container.addView(group)

        val etKeywords = EditText(this).apply { hint = "Keywords, comma separated"; visibility = View.GONE }
        val etContent = EditText(this).apply { hint = "Instruction or reply text"; minLines = 2 }
        val etCooldown = EditText(this).apply { hint = "Cooldown seconds for this chat (0 = default)"; inputType = InputType.TYPE_CLASS_NUMBER }
        val cbAi = CheckBox(this).apply { text = "Allow AI replies in this chat"; isChecked = true }
        container.addView(etKeywords); container.addView(etContent); container.addView(etCooldown); container.addView(cbAi)

        fun type() = types[ids.indexOf(group.checkedRadioButtonId).coerceAtLeast(0)].first
        group.setOnCheckedChangeListener { _, _ ->
            val t = type()
            etKeywords.visibility = if (t == TelegramRuleEntity.RULE_KEYWORD_REPLY || t == TelegramRuleEntity.RULE_KEYWORD_IGNORE) View.VISIBLE else View.GONE
            etContent.visibility = if (t == TelegramRuleEntity.RULE_BLOCK || t == TelegramRuleEntity.RULE_KEYWORD_IGNORE) View.GONE else View.VISIBLE
            cbAi.visibility = if (t == TelegramRuleEntity.RULE_INSTRUCTION || t == TelegramRuleEntity.RULE_ALLOW) View.VISIBLE else View.GONE
        }

        MaterialAlertDialogBuilder(this).setTitle("Add Chat Rule")
            .setView(android.widget.ScrollView(this).apply { addView(container) })
            .setPositiveButton("Add Rule") { _, _ ->
                val chat = etChat.text.toString().trim()
                val t = type()
                val needsText = t == TelegramRuleEntity.RULE_FIXED_REPLY || t == TelegramRuleEntity.RULE_KEYWORD_REPLY
                val needsKeywords = t == TelegramRuleEntity.RULE_KEYWORD_REPLY || t == TelegramRuleEntity.RULE_KEYWORD_IGNORE
                when {
                    chat.isBlank() -> toast("Enter the chat name")
                    needsText && etContent.text.isBlank() -> toast("Enter the reply text")
                    needsKeywords && etKeywords.text.isBlank() -> toast("Enter at least one keyword")
                    else -> lifecycleScope.launch {
                        database.ruleDao().insertRule(
                            TelegramRuleEntity(
                                chatPattern = chat,
                                ruleType = t,
                                keywords = if (needsKeywords) etKeywords.text.toString().trim() else "",
                                customContent = etContent.text.toString().trim(),
                                aiEnabled = cbAi.isChecked,
                                cooldownSeconds = etCooldown.text.toString().toIntOrNull()?.coerceIn(0, 86_400) ?: 0
                            )
                        )
                        toast("Rule saved for $chat")
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeleteRule(rule: TelegramRuleEntity) {
        MaterialAlertDialogBuilder(this).setTitle("Delete Rule")
            .setMessage("Remove the rule for '${rule.chatPattern}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { database.ruleDao().deleteRule(rule); toast("Rule removed") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Dry run: applies safety filter + rules + Gemini, but never sends or logs a reply. */
    private fun showTestDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 10) }
        val etChat = EditText(this).apply { hint = "Chat name"; setText("Test Contact") }
        val etMsg = EditText(this).apply { hint = "Incoming message"; setText("তুমি এখন কোথায়? জরুরি কথা আছে।") }
        container.addView(etChat); container.addView(etMsg)
        MaterialAlertDialogBuilder(this).setTitle("Test Telegram AI Reply").setView(container)
            .setPositiveButton("Generate") { _, _ ->
                val chat = etChat.text.toString().trim().ifBlank { "Test Contact" }
                val msg = etMsg.text.toString().trim().ifBlank { "Hello" }
                lifecycleScope.launch {
                    val text = when (val r = manager.previewReply(chat, msg)) {
                        is TelegramAutomationManager.PreviewResult.Blocked -> "No reply would be sent.\n\nReason: ${r.reason}"
                        is TelegramAutomationManager.PreviewResult.Reply ->
                            "Reply:\n\"${r.text}\"\n\n${if (r.viaAi) "Generated by Gemini." else "Not generated by Gemini."} ${r.note.orEmpty()}"
                    }
                    MaterialAlertDialogBuilder(this@TelegramAutomationActivity)
                        .setTitle("Dry run (nothing was sent)").setMessage(text).setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- adapters -----------------------------------------------------------------------------

    private class RulesAdapter(private val onDelete: (TelegramRuleEntity) -> Unit) :
        RecyclerView.Adapter<RulesAdapter.VH>() {
        private var items = listOf<TelegramRuleEntity>()
        fun submitList(list: List<TelegramRuleEntity>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTelegramRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemTelegramRuleBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(rule: TelegramRuleEntity) {
                b.tvRuleContact.text = rule.chatPattern
                b.tvRuleTypeBadge.text = when (rule.ruleType) {
                    TelegramRuleEntity.RULE_ALLOW -> "ALLOW"
                    TelegramRuleEntity.RULE_BLOCK -> "BLOCK"
                    TelegramRuleEntity.RULE_FIXED_REPLY -> "FIXED REPLY"
                    TelegramRuleEntity.RULE_KEYWORD_REPLY -> "KEYWORD REPLY"
                    TelegramRuleEntity.RULE_KEYWORD_IGNORE -> "KEYWORD IGNORE"
                    else -> "CUSTOM AI"
                }
                val extras = buildList {
                    if (!rule.aiEnabled) add("AI off")
                    if (rule.cooldownSeconds > 0) add("cooldown ${rule.cooldownSeconds}s")
                }.joinToString(" • ").let { if (it.isBlank()) "" else "\n[$it]" }
                b.tvRuleContent.text = when (rule.ruleType) {
                    TelegramRuleEntity.RULE_BLOCK -> "Never reply to this chat."
                    TelegramRuleEntity.RULE_ALLOW -> "Chat is allowlisted." + if (rule.customContent.isNotBlank()) " Instruction: \"${rule.customContent}\"" else ""
                    TelegramRuleEntity.RULE_FIXED_REPLY -> "Static reply: \"${rule.customContent}\""
                    TelegramRuleEntity.RULE_KEYWORD_REPLY -> "If message has [${rule.keywords}] reply: \"${rule.customContent}\""
                    TelegramRuleEntity.RULE_KEYWORD_IGNORE -> "Ignore messages containing [${rule.keywords}]."
                    else -> "Instruction: \"${rule.customContent.ifBlank { "(none)" }}\""
                } + extras
                b.btnDeleteRule.setOnClickListener { onDelete(rule) }
            }
        }
    }

    private class LogsAdapter : RecyclerView.Adapter<LogsAdapter.VH>() {
        private var items = listOf<TelegramLogEntity>()
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        fun submitList(list: List<TelegramLogEntity>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemTelegramLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemTelegramLogBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(log: TelegramLogEntity) {
                b.tvLogSender.text = log.chatName
                b.tvLogTime.text = timeFormat.format(Date(log.timestamp))

                val (label, color, bg) = when (log.status) {
                    TelegramLogEntity.REPLIED -> Triple("REPLIED", "#10B981", R.drawable.bg_jarvis_status_badge_green)
                    TelegramLogEntity.SKIPPED_SENSITIVE -> Triple("SECURITY FILTER", "#EF4444", R.drawable.bg_jarvis_error_card)
                    TelegramLogEntity.FAILED_SEND -> Triple("SEND FAILED", "#EF4444", R.drawable.bg_jarvis_error_card)
                    TelegramLogEntity.NO_REPLY_ACTION -> Triple("NO REPLY ACTION", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_RULE -> Triple("RULE SKIPPED", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_GROUP -> Triple("GROUP IGNORED", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_UNKNOWN_SENDER -> Triple("UNKNOWN SENDER", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_COOLDOWN -> Triple("COOLDOWN", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_RATE_LIMIT -> Triple("RATE LIMIT", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_UNSUPPORTED -> Triple("UNSUPPORTED TYPE", "#94A3B8", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SKIPPED_AI_DISABLED -> Triple("AI DISABLED", "#F59E0B", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.AUTO_REPLY_OFF -> Triple("AUTO REPLY OFF", "#94A3B8", R.drawable.bg_jarvis_status_badge)
                    TelegramLogEntity.SUPERSEDED -> Triple("NOT SENT", "#94A3B8", R.drawable.bg_jarvis_status_badge)
                    else -> Triple(log.status, "#94A3B8", R.drawable.bg_jarvis_status_badge)
                }
                b.tvLogStatusBadge.text = label
                b.tvLogStatusBadge.setTextColor(Color.parseColor(color))
                b.tvLogStatusBadge.setBackgroundResource(bg)

                b.tvLogDetail.visibility = if (log.detail.isNullOrBlank()) View.GONE else View.VISIBLE
                b.tvLogDetail.text = log.detail.orEmpty()
                b.tvLogIncoming.visibility = if (log.incomingPreview.isBlank()) View.GONE else View.VISIBLE
                b.tvLogIncoming.text = "Incoming: ${log.incomingPreview}"
                b.tvLogReply.visibility = if (log.replyText.isBlank()) View.GONE else View.VISIBLE
                b.tvLogReply.text = "Reply: ${log.replyText}"
            }
        }
    }
}
