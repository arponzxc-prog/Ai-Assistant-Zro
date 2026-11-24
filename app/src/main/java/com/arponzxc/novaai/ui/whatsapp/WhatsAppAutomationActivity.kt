package com.arponzxc.novaai.ui.whatsapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.automation.whatsapp.NovaNotificationListenerService
import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppContactRuleEntity
import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppLogEntity
import com.arponzxc.novaai.databinding.ActivityWhatsappAutomationBinding
import com.arponzxc.novaai.databinding.ItemWhatsappLogBinding
import com.arponzxc.novaai.databinding.ItemWhatsappRuleBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WhatsAppAutomationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhatsappAutomationBinding
    private val automationManager get() = JarvisApp.instance.whatsAppAutomationManager
    private val preferences get() = automationManager.preferences
    private val database get() = automationManager.database

    private val rulesAdapter = RulesAdapter { rule ->
        deleteRule(rule)
    }

    private val logsAdapter = LogsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhatsappAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)

        setupRecyclerViews()
        setupListeners()
        observeData()
    }

    override fun onResume() {
        super.onResume()
        checkPermission()
        loadPreferences()
    }

    private fun setupRecyclerViews() {
        binding.rvRules.layoutManager = LinearLayoutManager(this)
        binding.rvRules.adapter = rulesAdapter

        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logsAdapter
    }

    private fun checkPermission() {
        val granted = NovaNotificationListenerService.isPermissionGranted(this)
        binding.cardPermissionWarning.visibility = if (granted) View.GONE else View.VISIBLE
        updateStatusBadge(granted)
    }

    private fun updateStatusBadge(hasPermission: Boolean) {
        val isMasterOn = preferences.isMasterEnabled
        if (!hasPermission) {
            binding.tvServiceStatusBadge.text = "PERMISSION REQUIRED"
            binding.tvServiceStatusBadge.setTextColor(Color.parseColor("#EF4444"))
            binding.tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_error_card)
        } else if (isMasterOn) {
            binding.tvServiceStatusBadge.text = "MONITORING ACTIVE"
            binding.tvServiceStatusBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
        } else {
            binding.tvServiceStatusBadge.text = "AUTOMATION OFF"
            binding.tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_sapphire))
            binding.tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
        }
    }

    private fun loadPreferences() {
        binding.switchMasterAutomation.isChecked = preferences.isMasterEnabled
        binding.switchGeminiBrain.isChecked = preferences.isGeminiBrainEnabled
        binding.switchAutoReply.isChecked = preferences.isAutoReplyEnabled
        binding.switchVoiceAnnouncement.isChecked = preferences.isVoiceAnnouncementEnabled
        binding.switchReadIncoming.isChecked = preferences.isReadIncomingEnabled

        val delay = preferences.replyDelaySeconds
        binding.tvReplyDelayValue.text = if (delay == 0) "INSTANT" else "$delay SECONDS"
        binding.tvOperatorName.text = preferences.operatorName
        binding.tvTotalRepliesBadge.text = "${preferences.totalRepliesCount} REPLIES SENT"
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.switchMasterAutomation.setOnCheckedChangeListener { _, isChecked ->
            preferences.isMasterEnabled = isChecked
            checkPermission()
        }

        binding.switchGeminiBrain.setOnCheckedChangeListener { _, isChecked ->
            preferences.isGeminiBrainEnabled = isChecked
        }

        binding.switchAutoReply.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAutoReplyEnabled = isChecked
        }

        binding.switchVoiceAnnouncement.setOnCheckedChangeListener { _, isChecked ->
            preferences.isVoiceAnnouncementEnabled = isChecked
        }

        binding.switchReadIncoming.setOnCheckedChangeListener { _, isChecked ->
            preferences.isReadIncomingEnabled = isChecked
        }

        binding.rowReplyDelay.setOnClickListener {
            showReplyDelayDialog()
        }

        binding.rowOperatorName.setOnClickListener {
            showOperatorNameDialog()
        }

        binding.btnAddRule.setOnClickListener {
            showAddRuleDialog()
        }

        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                database.logDao().clearLogs()
                Toast.makeText(this@WhatsAppAutomationActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestAi.setOnClickListener {
            showTestAiDialog()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            database.ruleDao().getAllRulesFlow().collectLatest { rules ->
                if (rules.isEmpty()) {
                    binding.tvNoRules.visibility = View.VISIBLE
                    binding.rvRules.visibility = View.GONE
                } else {
                    binding.tvNoRules.visibility = View.GONE
                    binding.rvRules.visibility = View.VISIBLE
                    rulesAdapter.submitList(rules)
                }
            }
        }

        lifecycleScope.launch {
            database.logDao().getRecentLogsFlow().collectLatest { logs ->
                if (logs.isEmpty()) {
                    binding.tvNoLogs.visibility = View.VISIBLE
                    binding.rvLogs.visibility = View.GONE
                } else {
                    binding.tvNoLogs.visibility = View.GONE
                    binding.rvLogs.visibility = View.VISIBLE
                    logsAdapter.submitList(logs)
                }
            }
        }

        lifecycleScope.launch {
            automationManager.automationState.collectLatest { state ->
                binding.tvTotalRepliesBadge.text = "${state.totalRepliesCount} REPLIES SENT"
            }
        }
    }

    private fun showReplyDelayDialog() {
        val options = arrayOf("0 Seconds (Instant)", "1 Second", "3 Seconds (Recommended)", "5 Seconds", "10 Seconds", "15 Seconds")
        val values = intArrayOf(0, 1, 3, 5, 10, 15)
        val currentIdx = values.indexOf(preferences.replyDelaySeconds).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Reply Delay")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val chosen = values[which]
                preferences.replyDelaySeconds = chosen
                binding.tvReplyDelayValue.text = if (chosen == 0) "INSTANT" else "$chosen SECONDS"
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOperatorNameDialog() {
        val input = EditText(this).apply {
            setText(preferences.operatorName)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Operator Name")
            .setMessage("Enter the name NOVA AI should use for your persona:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.operatorName = newName
                    binding.tvOperatorName.text = newName
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddRuleDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val etContact = EditText(this).apply {
            hint = "Contact name, partial name, or phone number (e.g. Boss, Mom, +8801...)"
        }
        container.addView(etContact)

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val rbInstruction = RadioButton(this).apply {
            text = "Custom AI Instruction (e.g. Be very respectful and say I am busy)"
            id = View.generateViewId()
            isChecked = true
        }
        val rbFixed = RadioButton(this).apply {
            text = "Fixed Reply Text (Static reply without AI)"
            id = View.generateViewId()
        }
        val rbIgnore = RadioButton(this).apply {
            text = "Ignore Contact (Never reply automatically)"
            id = View.generateViewId()
        }

        radioGroup.addView(rbInstruction)
        radioGroup.addView(rbFixed)
        radioGroup.addView(rbIgnore)
        container.addView(radioGroup)

        val etContent = EditText(this).apply {
            hint = "Instruction or fixed message..."
            minLines = 2
        }
        container.addView(etContent)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            etContent.visibility = if (checkedId == rbIgnore.id) View.GONE else View.VISIBLE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Contact Rule")
            .setView(container)
            .setPositiveButton("Add Rule") { _, _ ->
                val contactPattern = etContact.text.toString().trim()
                if (contactPattern.isBlank()) {
                    Toast.makeText(this, "Please enter a contact name or pattern", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val ruleType = when (radioGroup.checkedRadioButtonId) {
                    rbFixed.id -> WhatsAppContactRuleEntity.RULE_TYPE_FIXED_REPLY
                    rbIgnore.id -> WhatsAppContactRuleEntity.RULE_TYPE_IGNORE
                    else -> WhatsAppContactRuleEntity.RULE_TYPE_INSTRUCTION
                }

                val content = etContent.text.toString().trim()

                lifecycleScope.launch {
                    database.ruleDao().insertRule(
                        WhatsAppContactRuleEntity(
                            contactPattern = contactPattern,
                            ruleType = ruleType,
                            customContent = content,
                            priority = 10,
                            isEnabled = true
                        )
                    )
                    Toast.makeText(this@WhatsAppAutomationActivity, "Rule saved for $contactPattern", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRule(rule: WhatsAppContactRuleEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Rule")
            .setMessage("Remove automated rule for '${rule.contactPattern}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    database.ruleDao().deleteRule(rule)
                    Toast.makeText(this@WhatsAppAutomationActivity, "Rule removed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTestAiDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val etSender = EditText(this).apply {
            hint = "Sender Name (e.g. Arpon, Boss, Friend)"
            setText("Boss Arpon")
        }
        container.addView(etSender)

        val etMessage = EditText(this).apply {
            hint = "Incoming Message (e.g. কেমন আছেন? অথবা Where are you?)"
            setText("অর্পন, তুমি এখন কোথায়? জরুরি কথা আছে।")
        }
        container.addView(etMessage)

        MaterialAlertDialogBuilder(this)
            .setTitle("Test AI WhatsApp Reply")
            .setView(container)
            .setPositiveButton("Simulate & Test") { _, _ ->
                val sender = etSender.text.toString().trim().ifBlank { "Test Contact" }
                val message = etMessage.text.toString().trim().ifBlank { "Hello" }

                lifecycleScope.launch {
                    val activeRules = database.ruleDao().getActiveRules()
                    val decision = com.arponzxc.novaai.automation.whatsapp.ContactRuleEngine.evaluateRules(sender, activeRules)

                    val reply = if (decision is com.arponzxc.novaai.automation.whatsapp.ContactRuleEngine.RuleDecision.FixedReply) {
                        decision.replyText
                    } else {
                        val instruction = (decision as? com.arponzxc.novaai.automation.whatsapp.ContactRuleEngine.RuleDecision.CustomInstruction)?.instruction
                        automationManager.replyEngine.generateReply(
                            senderName = sender,
                            incomingMessage = message,
                            customInstruction = instruction,
                            operatorName = preferences.operatorName
                        ).getOrElse {
                            automationManager.replyEngine.generateOfflineFallback(message, preferences.operatorName)
                        }
                    }

                    database.logDao().insertLog(
                        WhatsAppLogEntity(
                            timestamp = System.currentTimeMillis(),
                            senderName = "[TEST] $sender",
                            incomingText = message,
                            replyText = reply,
                            status = "REPLIED"
                        )
                    )

                    MaterialAlertDialogBuilder(this@WhatsAppAutomationActivity)
                        .setTitle("NOVA AI Response")
                        .setMessage("Sender: $sender\nIncoming: \"$message\"\n\nGenerated Reply:\n\"$reply\"")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // RecyclerView Adapter for Rules
    private class RulesAdapter(private val onDeleteClick: (WhatsAppContactRuleEntity) -> Unit) :
        RecyclerView.Adapter<RulesAdapter.RuleViewHolder>() {

        private var items = listOf<WhatsAppContactRuleEntity>()

        fun submitList(list: List<WhatsAppContactRuleEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
            val binding = ItemWhatsappRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return RuleViewHolder(binding)
        }

        override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class RuleViewHolder(private val binding: ItemWhatsappRuleBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(rule: WhatsAppContactRuleEntity) {
                binding.tvRuleContact.text = rule.contactPattern
                binding.tvRuleTypeBadge.text = when (rule.ruleType) {
                    WhatsAppContactRuleEntity.RULE_TYPE_FIXED_REPLY -> "FIXED REPLY"
                    WhatsAppContactRuleEntity.RULE_TYPE_IGNORE -> "IGNORE"
                    else -> "CUSTOM AI"
                }

                binding.tvRuleContent.text = when (rule.ruleType) {
                    WhatsAppContactRuleEntity.RULE_TYPE_IGNORE -> "Silently ignore incoming messages from this sender."
                    WhatsAppContactRuleEntity.RULE_TYPE_FIXED_REPLY -> "Static Reply: \"${rule.customContent}\""
                    else -> "Instruction: \"${rule.customContent}\""
                }

                binding.btnDeleteRule.setOnClickListener {
                    onDeleteClick(rule)
                }
            }
        }
    }

    // RecyclerView Adapter for Activity Logs
    private class LogsAdapter : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

        private var items = listOf<WhatsAppLogEntity>()
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun submitList(list: List<WhatsAppLogEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val binding = ItemWhatsappLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return LogViewHolder(binding)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class LogViewHolder(private val binding: ItemWhatsappLogBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(log: WhatsAppLogEntity) {
                binding.tvLogSender.text = log.senderName
                binding.tvLogTime.text = timeFormat.format(Date(log.timestamp))

                when (log.status) {
                    "REPLIED" -> {
                        binding.tvLogStatusBadge.text = "REPLIED"
                        binding.tvLogStatusBadge.setTextColor(Color.parseColor("#10B981"))
                        binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
                    }
                    "SKIPPED_RULE" -> {
                        binding.tvLogStatusBadge.text = "RULE SKIPPED"
                        binding.tvLogStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
                        binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
                    }
                    "SKIPPED_SENSITIVE", "FAILED_SEND" -> {
                        binding.tvLogStatusBadge.text = if (log.status == "SKIPPED_SENSITIVE") "SECURITY FILTER" else "SEND FAILED"
                        binding.tvLogStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                        binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_error_card)
                    }
                    "FAILED_NO_ACTION", "GENERATED_ONLY" -> {
                        binding.tvLogStatusBadge.text = if (log.status == "GENERATED_ONLY") "DRAFT ONLY" else "NO REPLY ACTION"
                        binding.tvLogStatusBadge.setTextColor(Color.parseColor("#F59E0B"))
                        binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
                    }
                    else -> {
                        binding.tvLogStatusBadge.text = log.status
                        binding.tvLogStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
                        binding.tvLogStatusBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
                    }
                }

                binding.tvLogIncoming.text = "Incoming: ${log.incomingText}"
                if (log.replyText.isNotBlank()) {
                    binding.tvLogReply.visibility = View.VISIBLE
                    binding.tvLogReply.text = "Reply: ${log.replyText}"
                } else {
                    binding.tvLogReply.visibility = View.GONE
                }
            }
        }
    }
}
