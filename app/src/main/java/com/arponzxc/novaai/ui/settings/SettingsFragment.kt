package com.arponzxc.novaai.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.config.SocialLinks
import com.arponzxc.novaai.data.model.GeminiConstants
import com.arponzxc.novaai.data.preferences.AppPreferences
import com.arponzxc.novaai.databinding.FragmentSettingsBinding
import com.arponzxc.novaai.ui.memory.MemoryActivity
import com.arponzxc.novaai.ui.permission.PermissionActivity
import com.arponzxc.novaai.ui.voice.VoiceEnrollmentActivity
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val preferences by lazy { JarvisApp.instance.preferences }
    private var isSyncingAiVoiceSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)

        loadUserData()
        setupClickListeners()
        observeVoiceResponsePreference()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
            loadUserData()
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun loadUserData() {
        val userName = preferences.userName.ifBlank { "Arpon Gang" }
        binding.tvProfileName.text = userName
        binding.tvProfileSubtitle.text = "NOVA AI OPERATOR • PRIMARY USER"

        // Owner Voice Profile Status
        val hasVoiceProfile = preferences.hasOwnerVoiceProfile()
        if (hasVoiceProfile) {
            binding.tvOwnerVoiceBadge.text = "ACTIVE"
            binding.tvOwnerVoiceBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvOwnerVoiceSubtitle.text = "Voice Profile Active • Only you can speak"

            binding.tvProfileVoiceStatus.text = "VOICE ENROLLED"
            binding.tvProfileVoiceStatus.setTextColor(Color.parseColor("#10B981"))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_verified_badge)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981"))
        } else {
            binding.tvOwnerVoiceBadge.text = "SETUP"
            binding.tvOwnerVoiceBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvOwnerVoiceBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvOwnerVoiceSubtitle.text = "Not enrolled • Tap to record voice profile"

            binding.tvProfileVoiceStatus.text = "ENROLL VOICE"
            binding.tvProfileVoiceStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.chipProfileVoiceStatus.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.ivProfileVoiceBadgeIcon.setImageResource(R.drawable.ic_settings_owner_voice)
            binding.ivProfileVoiceBadgeIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
            )
        }

        // Language
        binding.tvCurrentLanguage.text = preferences.language

        // Persona & Voice
        val currentPersona = preferences.personality
        val currentVoice = preferences.voice
        binding.tvCurrentPersona.text = "$currentPersona • Voice: $currentVoice"
        binding.tvProfilePersonaChip.text = currentPersona.uppercase()

        // AI Voice Response ON/OFF (sync without firing toast)
        syncAiVoiceResponseUi(preferences.isAiVoiceResponseEnabled)

        // Silent Execution Mode
        binding.switchSilentMode.isChecked = preferences.isSilentExecutionMode

        // Model
        val modelDisplay = preferences.aiModel.removePrefix("models/")
        binding.tvCurrentModel.text = "$modelDisplay • Live WebSocket"

        // Theme
        binding.tvCurrentTheme.text = when (preferences.appTheme) {
            "dark" -> "Dark Theme"
            "red" -> "Red Theme"
            "sunset" -> "Sunset Theme"
            "custom" -> "Custom (${preferences.customThemeColor})"
            else -> "Light Theme"
        }

        // Camera Pill status
        val pillEnabled = preferences.isCameraPillEnabled
        val hasOverlay = android.provider.Settings.canDrawOverlays(requireContext())
        if (pillEnabled && hasOverlay) {
            binding.tvCameraPillBadge.text = "ACTIVE"
            binding.tvCameraPillBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvCameraPillBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            val modeLabel = com.arponzxc.novaai.ui.camerapill.CameraPillDisplayMode
                .fromPref(preferences.cameraPillDisplayMode).label
            binding.tvCameraPillSubtitle.text = "$modeLabel • AI Live Avatar active"
        } else if (pillEnabled) {
            binding.tvCameraPillBadge.text = "PERMISSION"
            binding.tvCameraPillBadge.setTextColor(Color.parseColor("#F59E0B"))
            binding.tvCameraPillBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvCameraPillSubtitle.text = "Grant Overlay Permission to show floating pill"
        } else {
            binding.tvCameraPillBadge.text = "OFF"
            binding.tvCameraPillBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvCameraPillBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvCameraPillSubtitle.text = "Dynamic Island AI Live Avatar & Voice Visualizer"
        }

        // Continuous Voice status
        val contOn = preferences.isContinuousListeningEnabled
        val silenceSec = String.format(java.util.Locale.US, "%.1fs", preferences.voiceSilenceTimeoutMs / 1000.0)
        binding.tvContinuousVoiceBadge.text = if (contOn) "ON" else "OFF"
        binding.tvContinuousVoiceBadge.setTextColor(
            if (contOn) Color.parseColor("#10B981")
            else ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
        )
        binding.tvContinuousVoiceBadge.setBackgroundResource(
            if (contOn) R.drawable.bg_jarvis_status_badge_green else R.drawable.bg_jarvis_status_badge
        )
        binding.tvContinuousVoiceSubtitle.text =
            "${if (contOn) "Continuous mode active" else "Tap-to-talk mode"} • $silenceSec silence pause"

        // SAF Storage Access status
        val folders = JarvisApp.instance.safFileManager.getAuthorizedFolders()
        binding.tvStorageAccessBadge.text = if (folders.isEmpty()) "SETUP" else "${folders.size} FOLDER${if (folders.size == 1) "" else "S"}"
        binding.tvStorageAccessBadge.setTextColor(
            if (folders.isNotEmpty()) Color.parseColor("#10B981")
            else ContextCompat.getColor(requireContext(), R.color.accent_sapphire)
        )
        binding.tvStorageAccessBadge.setBackgroundResource(
            if (folders.isNotEmpty()) R.drawable.bg_jarvis_status_badge_green else R.drawable.bg_jarvis_status_badge
        )
        binding.tvStorageAccessSubtitle.text = if (folders.isEmpty()) {
            "Authorize folders via SAF for voice & text file creation"
        } else {
            "Authorized: ${folders.take(2).joinToString(", ") { it.displayName }}"
        }

        // WhatsApp AI Automation status
        val waPrefs = JarvisApp.instance.whatsAppAutomationManager.preferences
        val waGranted = com.arponzxc.novaai.automation.whatsapp.NovaNotificationListenerService.isPermissionGranted(requireContext())
        if (!waGranted) {
            binding.tvWhatsAppAutomationBadge.text = "PERMISSION"
            binding.tvWhatsAppAutomationBadge.setTextColor(Color.parseColor("#EF4444"))
            binding.tvWhatsAppAutomationBadge.setBackgroundResource(R.drawable.bg_jarvis_error_card)
            binding.tvWhatsAppAutomationSubtitle.text = "Notification access required for automatic reply"
        } else if (waPrefs.isMasterEnabled) {
            binding.tvWhatsAppAutomationBadge.text = "ACTIVE"
            binding.tvWhatsAppAutomationBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvWhatsAppAutomationBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvWhatsAppAutomationSubtitle.text = "Autonomous notification replies, rules & Gemini AI"
        } else {
            binding.tvWhatsAppAutomationBadge.text = "OFF"
            binding.tvWhatsAppAutomationBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvWhatsAppAutomationBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvWhatsAppAutomationSubtitle.text = "Automation paused • Tap to open control panel"
        }

        // Telegram AI Automation status (independent from WhatsApp)
        val tgPrefs = JarvisApp.instance.telegramAutomationManager.preferences
        val tgGranted = com.arponzxc.novaai.automation.whatsapp.NovaNotificationListenerService.isPermissionGranted(requireContext())
        if (!tgGranted) {
            binding.tvTelegramAutomationBadge.text = "PERMISSION"
            binding.tvTelegramAutomationBadge.setTextColor(Color.parseColor("#EF4444"))
            binding.tvTelegramAutomationBadge.setBackgroundResource(R.drawable.bg_jarvis_error_card)
            binding.tvTelegramAutomationSubtitle.text = "Notification access required for automatic reply"
        } else if (tgPrefs.isMasterEnabled) {
            binding.tvTelegramAutomationBadge.text = if (tgPrefs.isAutoReplyEnabled) "ACTIVE" else "MONITOR"
            binding.tvTelegramAutomationBadge.setTextColor(Color.parseColor("#10B981"))
            binding.tvTelegramAutomationBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge_green)
            binding.tvTelegramAutomationSubtitle.text = if (tgPrefs.isAutoReplyEnabled)
                "Notification replies, chat rules & Gemini AI" else "Auto reply is OFF • Tap to configure"
        } else {
            binding.tvTelegramAutomationBadge.text = "OFF"
            binding.tvTelegramAutomationBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent_sapphire))
            binding.tvTelegramAutomationBadge.setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            binding.tvTelegramAutomationSubtitle.text = "Automation paused • Tap to open control panel"
        }

        // Phase 2 Multi-Provider, Vision, Research & Intelligent File Manager status
        runCatching {
            val ctx = requireContext()
            val storage = com.arponzxc.novaai.network.apimanager.SecureKeyStorage(ctx)
            val activeProvider = com.arponzxc.novaai.network.apimanager.MultiProviderRouter.getActiveProviderConfig(ctx)
            val keyCount = storage.getApiKeyEntries(activeProvider.id).count { it.isEnabled }
            binding.tvMultiProviderBadge.text = activeProvider.id.uppercase(java.util.Locale.ROOT)
            binding.tvMultiProviderSubtitle.text = "Active: ${activeProvider.name} • $keyCount enabled key${if (keyCount == 1) "" else "s"}"
            binding.tvVisionStudioBadge.text = if (activeProvider.supportsVision) "READY" else "NO VISION"
            val historyCount = com.arponzxc.novaai.research.ResearchEngine(ctx).getHistory().size
            binding.tvResearchCenterBadge.text = if (historyCount > 0) "$historyCount SAVED" else "LIVE"
            binding.tvFileManagerBadge.text = if (folders.isEmpty()) "SETUP" else "${folders.size} ROOT${if (folders.size == 1) "" else "S"}"
        }
    }

    private fun setupClickListeners() {
        // Profile Card -> Change Name
        binding.cardProfile.setOnClickListener {
            showEditNameDialog()
        }

        // Voice Status Chip on Profile Card -> Opens Voice Enrollment
        binding.chipProfileVoiceStatus.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        // Section 1: AI & Voice Intelligence
        binding.rowOwnerVoice.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceEnrollmentActivity::class.java))
        }

        binding.rowLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.rowPersonaVoice.setOnClickListener {
            startActivity(Intent(requireContext(), PersonaActivity::class.java))
        }

        binding.rowContinuousVoice.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.voice.VoiceListeningSettingsActivity::class.java))
        }

        binding.rowAiVoiceResponse.setOnClickListener {
            binding.switchAiVoiceResponse.toggle()
        }

        binding.switchAiVoiceResponse.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingAiVoiceSwitch) return@setOnCheckedChangeListener
            preferences.isAiVoiceResponseEnabled = isChecked
            com.arponzxc.novaai.background.JarvisConversationService.onAiVoiceResponseSettingChanged(isChecked)
            syncAiVoiceResponseUi(isChecked)
            val msg = if (isChecked) "AI Voice Response Enabled" else "AI Voice Response Off (Text-Only Mode)"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchSilentMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.isSilentExecutionMode = isChecked
            val msg = if (isChecked) "Silent Execution Mode Enabled" else "Silent Execution Mode Disabled"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Section 2: System & Intelligence Data
        binding.rowApiConnection.setOnClickListener {
            startActivity(Intent(requireContext(), ApiConnectionActivity::class.java))
        }

        binding.rowMemory.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.rowPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionActivity::class.java))
        }

        binding.rowStorageAccess.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.storage.StorageManagerActivity::class.java))
        }

        binding.rowCameraPill.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.camerapill.CameraPillSettingsActivity::class.java))
        }

        binding.rowOverlayAvatars.setOnClickListener {
            startActivity(Intent(requireContext(), OverlayAvatarActivity::class.java))
        }

        binding.rowApplications.setOnClickListener {
            startActivity(Intent(requireContext(), HandsFreeActivity::class.java))
        }

        binding.rowWhatsAppAutomation.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.whatsapp.WhatsAppAutomationActivity::class.java))
        }

        binding.rowTelegramAutomation.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.telegram.TelegramAutomationActivity::class.java))
        }

        binding.rowDevStudio.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.devstudio.ui.DevStudioActivity::class.java))
        }

        binding.rowMultiProviderApi.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.network.apimanager.ApiManagerActivity::class.java))
        }

        binding.rowVisionStudio.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.vision.VisionActivity::class.java))
        }

        binding.rowResearchCenter.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.research.ResearchActivity::class.java))
        }

        binding.rowFileManager.setOnClickListener {
            startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.storage.FileManagerActivity::class.java))
        }

        // Section 3: Appearance & Security
        binding.rowAppTheme.setOnClickListener {
            ThemeSelectionBottomSheet.newInstance().show(childFragmentManager, ThemeSelectionBottomSheet.TAG)
        }

        binding.rowPrivacyPolicy.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacyShieldActivity::class.java))
        }

        binding.rowAppIdentity.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("NOVA AI App Identity")
                .setMessage(com.arponzxc.novaai.security.AppIdentityVerifier.report(requireContext()))
                .setPositiveButton("Close", null)
                .show()
        }

        // Social Media Community Buttons (Centralized via SocialLinks)
        binding.btnSocialTelegram.setOnClickListener {
            SocialLinks.openTelegramOfficial(requireContext())
        }

        binding.btnSocialInstagram.setOnClickListener {
            SocialLinks.openInstagram(requireContext())
        }

        binding.btnSocialYoutube.setOnClickListener {
            SocialLinks.openYoutube(requireContext())
        }

        binding.btnSocialGithub.setOnClickListener {
            SocialLinks.openGithub(requireContext())
        }

        binding.tvFooterCredit.setOnClickListener {
            SocialLinks.openGithub(requireContext())
        }
    }

    private fun showLanguageDialog() {
        val languages = GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentIndex = languages.indexOfFirst { it.equals(preferences.language, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvCurrentLanguage.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWakeWordDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Wake Words & Sensitivity")
            .setMessage("• Wake Words: 'Nova', 'Hey Nova'\n• Acoustic Trigger: Double Clap detection\n• Standby Mode: Low-power local Vosk offline engine\n\nWhen standby listening is active, NOVA AI awakens instantly upon hearing your voice.")
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showAppAutomationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Autonomous App Automation")
            .setMessage("NOVA AI is integrated with your device capabilities:\n\n• WhatsApp: Hands-free messaging & calls\n• YouTube: Search and playback automation\n• Phone Controls: Flashlight, Volume, Brightness, Bluetooth\n• Apps & Tools: Launch, search, and manage apps via voice")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showEditNameDialog() {
        val input = EditText(requireContext()).apply {
            setText(preferences.userName.ifBlank { "Arpon Gang" })
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Operator Name")
            .setMessage("Enter the name NOVA AI should use to address you:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    preferences.userName = newName
                    loadUserData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeVoiceResponsePreference() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppPreferences.aiVoiceResponseFlow.collect { enabled ->
                    syncAiVoiceResponseUi(enabled)
                }
            }
        }
    }

    private fun syncAiVoiceResponseUi(enabled: Boolean) {
        if (_binding == null) return
        if (binding.switchAiVoiceResponse.isChecked != enabled) {
            isSyncingAiVoiceSwitch = true
            binding.switchAiVoiceResponse.isChecked = enabled
            isSyncingAiVoiceSwitch = false
        }
        binding.tvAiVoiceResponseSubtitle.text = if (enabled) {
            "ON • Spoken with ${preferences.voice} voice + Chat text"
        } else {
            "OFF • Text-only responses (Mic listening stays independent)"
        }
        binding.ivAiVoiceResponseIcon.setImageResource(
            if (enabled) R.drawable.ic_voice_output_on else R.drawable.ic_voice_output_off
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
