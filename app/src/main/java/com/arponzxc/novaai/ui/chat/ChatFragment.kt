package com.arponzxc.novaai.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.automation.command.CommandParser
import com.arponzxc.novaai.automation.command.JarvisCommand
import com.arponzxc.novaai.background.JarvisConversationService
import com.arponzxc.novaai.data.model.ConversationState
import com.arponzxc.novaai.databinding.FragmentChatBinding
import com.arponzxc.novaai.storage.SafActionCoordinator
import com.arponzxc.novaai.ui.home.MainActivity
import com.arponzxc.novaai.ui.home.MainViewModel
import com.arponzxc.novaai.ui.storage.StorageManagerActivity
import com.arponzxc.novaai.ui.voice.VoiceListeningSettingsActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)

        // Setup Chat List
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        // Send Message
        binding.btnSendMessage.setOnClickListener {
            sendMessage()
        }

        // New Chat / Clear
        binding.btnChatNew.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    JarvisApp.instance.chatRepository.clearHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Back / Menu
        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        // Quick Voice VAD Settings & Storage SAF shortcuts
        binding.chipPersona.setOnClickListener {
            startActivity(Intent(requireContext(), VoiceListeningSettingsActivity::class.java))
        }

        binding.chipMode.setOnClickListener {
            startActivity(Intent(requireContext(), StorageManagerActivity::class.java))
        }

        binding.btnAttach.setOnClickListener {
            startActivity(Intent(requireContext(), StorageManagerActivity::class.java))
        }

        // Live Voice Listening Indicator Tap Control
        binding.layoutVoiceListeningBar.setOnClickListener {
            when {
                !JarvisConversationService.isSessionOn.value -> {
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                }
                JarvisConversationService.isListeningPaused.value -> {
                    JarvisConversationService.resumeListening()
                }
                else -> {
                    JarvisConversationService.pauseListening()
                }
            }
        }

        observeChatTurns()
        observeVoiceIndicator()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim() ?: ""
        if (text.isBlank()) return

        val app = JarvisApp.instance
        val parsed = CommandParser.parse(
            input = text,
            isAwaitingConfirmation = app.whatsAppAssistant.hasPendingDraft(),
            isAwaitingDisambiguation = app.whatsAppAssistant.isAwaitingDisambiguation(),
            isAwaitingStorageConfirmation = SafActionCoordinator.hasPendingConfirmation()
        )
        val cmd = parsed.command
        val isDirectLocalCommand = cmd != null && parsed.confidence >= 0.90f && (
            cmd is JarvisCommand.CreateFolder ||
                cmd is JarvisCommand.CreateFile ||
                cmd is JarvisCommand.ListStorageFiles ||
                cmd is JarvisCommand.CheckFileExists ||
                cmd is JarvisCommand.RenameStorageItem ||
                cmd is JarvisCommand.DeleteStorageItem ||
                cmd is JarvisCommand.ConfirmStorageAction ||
                cmd is JarvisCommand.VoiceListeningControl
            )

        JarvisConversationService.sendTextMessage(text)
        binding.etMessageInput.text?.clear()
    }

    private fun observeVoiceIndicator() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    JarvisConversationService.isSessionOn,
                    JarvisConversationService.isListeningPaused,
                    JarvisConversationService.conversationState,
                    JarvisConversationService.connectionStatus
                ) { sessionOn, paused, state, connStatus ->
                    arrayOf(sessionOn, paused, state, connStatus)
                }.collect { arr ->
                    val sessionOn = arr[0] as Boolean
                    val paused = arr[1] as Boolean
                    val state = arr[2] as ConversationState
                    val connStatus = arr[3] as String

                    when {
                        !sessionOn -> {
                            binding.ivVoiceIndicator.setImageResource(R.drawable.ic_mic_mute_jarvis)
                            binding.tvVoiceIndicator.text = "Continuous Voice Off • Tap to start listening"
                            binding.tvVoiceActionBadge.text = "START"
                            binding.tvChatSubtitle.text = "NOVA AI • Offline"
                        }
                        paused -> {
                            binding.ivVoiceIndicator.setImageResource(R.drawable.ic_mic_mute_jarvis)
                            binding.tvVoiceIndicator.text = "Mic Paused • Say \"NOVA, Start Listening\" or tap"
                            binding.tvVoiceActionBadge.text = "RESUME"
                            binding.tvChatSubtitle.text = "NOVA AI • Paused"
                        }
                        state == ConversationState.LISTENING -> {
                            binding.ivVoiceIndicator.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.tvVoiceIndicator.text = "Mic Active • Continuous Listening (Speak now)"
                            binding.tvVoiceActionBadge.text = "PAUSE"
                            binding.tvChatSubtitle.text = "NOVA AI • Listening"
                        }
                        state == ConversationState.SPEAKING -> {
                            binding.ivVoiceIndicator.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.tvVoiceIndicator.text = "NOVA Replying • Auto-continues after answer"
                            binding.tvVoiceActionBadge.text = "PAUSE"
                            binding.tvChatSubtitle.text = "NOVA AI • Speaking"
                        }
                        state == ConversationState.THINKING -> {
                            binding.ivVoiceIndicator.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.tvVoiceIndicator.text = "Processing your request…"
                            binding.tvVoiceActionBadge.text = "PAUSE"
                            binding.tvChatSubtitle.text = "NOVA AI • Processing"
                        }
                        else -> {
                            binding.ivVoiceIndicator.setImageResource(R.drawable.ic_mic_jarvis)
                            binding.tvVoiceIndicator.text = "Continuous Mic Ready • $connStatus"
                            binding.tvVoiceActionBadge.text = "PAUSE"
                            binding.tvChatSubtitle.text = "NOVA AI • $connStatus"
                        }
                    }
                }
            }
        }
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) {
                            binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
