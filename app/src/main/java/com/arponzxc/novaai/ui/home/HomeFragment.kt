package com.arponzxc.novaai.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.data.model.ConversationState
import com.arponzxc.novaai.databinding.FragmentHomeBinding
import com.arponzxc.novaai.ui.memory.MemoryActivity
import com.arponzxc.novaai.util.LocationWeatherHelper
import com.arponzxc.novaai.util.PermissionHelper
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.arponzxc.novaai.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var isPageLoaded = false
    private var pendingOrbState: String? = null
    private var pendingThemeKey: String? = null
    private var pendingCustomHex: String? = null
    private var lastAudioPushTime = 0L
    private var taskFallbackPanel: android.widget.LinearLayout? = null
    private var taskFallbackText: android.widget.TextView? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            fetchLiveWeather()
        } else {
            binding.tvWeatherCondition.text = "Location Permission Needed"
            binding.tvWeatherDetails.text = "Tap here to grant location for accurate weather"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
        createTaskFallbackPanel()

        // Top Navigation
        binding.btnTopChat.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_CHAT)
        }

        binding.btnTopSettings.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_SETTINGS)
        }

        binding.btnDismissLiveError.setOnClickListener {
            hideLiveErrorOverlay {
                viewModel.clearLiveError()
            }
        }

        setupOrbWebView()

        // Action Buttons
        binding.btnScreenShareQuick.setOnClickListener {
            (activity as? MainActivity)?.toggleScreenShare()
        }

        binding.btnSavedMemories.setOnClickListener {
            startActivity(Intent(requireContext(), MemoryActivity::class.java))
        }

        binding.btnConversationLanguageQuick.setOnClickListener {
            showLanguageDialog()
        }

        // Weather Card Click -> Refresh or Request Permission
        binding.cardWeather.setOnClickListener {
            if (!PermissionHelper.hasLocationPermission(requireContext())) {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                fetchLiveWeather()
            }
        }

        binding.btnVoiceOutputToggle.setOnClickListener {
            viewModel.toggleAiVoiceResponse()
        }

        updateHomeHeaderAndVoiceStatus()
        updateVoiceOutputToggleUi(preferences.isAiVoiceResponseEnabled, animate = false)
        observeViewModel()
        checkLocationAndFetchWeather()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOrbWebView() {
        binding.orbWebView.apply {
            setBackgroundColor(Color.TRANSPARENT)
            // Keep LAYER_TYPE_NONE (window is hardwareAccelerated=true); LAYER_TYPE_HARDWARE breaks
            // transparent WebGL canvas compositing on many Android WebView GPU drivers.
            setLayerType(View.LAYER_TYPE_NONE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.e(
                            "HomeOrbWebView",
                            "JS error (${consoleMessage.lineNumber()}): ${consoleMessage.message()}"
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true

                    // Sync Theme
                    val currentTheme = pendingThemeKey ?: preferences.appTheme
                    val customHex = pendingCustomHex ?: preferences.customThemeColor
                    setOrbTheme(currentTheme, customHex)

                    // Sync State
                    val state = pendingOrbState ?: when (viewModel.conversationState.value) {
                        ConversationState.LISTENING -> "listening"
                        ConversationState.SPEAKING -> "speaking"
                        ConversationState.THINKING -> "thinking"
                        ConversationState.IDLE -> "idle"
                    }
                    setOrbState(state)
                }
            }

            // Single tap detection triggers mic/conversation without interrupting 3D rotation drag
            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.triggerPulse && window.JarvisOrb.triggerPulse();", null)
                    (activity as? MainActivity)?.handleMicOrOrbClick()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    (activity as? MainActivity)?.handleHoldMicOrOrb()
                }
            })

            setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                v.onTouchEvent(event)
            }

            loadUrl("file:///android_asset/orb_ball.html?embedded=1")
        }
    }

    fun setOrbState(state: String) {
        pendingOrbState = state
        _binding?.orbHaloRing?.setOrbState(state)
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setState('$state');", null)
        }
    }

    fun setOrbTheme(themeKey: String, customHex: String?) {
        pendingThemeKey = themeKey
        pendingCustomHex = customHex
        _binding?.orbHaloRing?.setThemeKey(themeKey, customHex)
        if (!isPageLoaded) return
        activity?.runOnUiThread {
            if (themeKey == "custom" && !customHex.isNullOrBlank()) {
                binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setBackground('$customHex');", null)
            } else {
                val mapped = when (themeKey) {
                    "dark" -> "dark"
                    "red" -> "red"
                    "sunset" -> "sunset"
                    else -> "light"
                }
                binding.orbWebView.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('$mapped');", null)
            }
        }
    }

    private fun pushExternalAudio(freq: com.arponzxc.novaai.audio.AudioFrequencyData) {
        // Native glass orb reacts to every sample (it does its own smoothing).
        _binding?.orbHaloRing?.setAmplitude(freq.amplitude)
        if (!isPageLoaded) return
        val now = System.currentTimeMillis()
        if (freq.amplitude > 0.005f && (now - lastAudioPushTime < 16L)) return
        lastAudioPushTime = now
        val amp = freq.amplitude
        val bass = freq.bass
        val high = freq.high
        val mid = freq.mid
        val b2 = freq.band200_500
        val b5 = freq.band500_1000
        val b1k = freq.band1000_2000
        val b2k = freq.band2000_4000
        val b4k = freq.band4000_8000
        val plosive = if (freq.isPlosive) 1 else 0
        val fricative = if (freq.isFricative) 1 else 0

        activity?.runOnUiThread {
            binding.orbWebView.evaluateJavascript(
                "window.JarvisOrb && window.JarvisOrb.setExternalAudio($amp, $bass, $high, $mid, $b2, $b5, $b1k, $b2k, $b4k, $plosive, $fricative);",
                null
            )
        }
    }

    private fun pushExternalAudio(amp: Float, bass: Float, high: Float, mid: Float = amp * 0.85f) {
        pushExternalAudio(com.arponzxc.novaai.audio.AudioFrequencyData(amplitude = amp, bass = bass, high = high, mid = mid))
    }

    override fun onResume() {
        super.onResume()
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
        binding.orbWebView.onResume()
        renderTaskFallback(com.arponzxc.novaai.automation.agent.AgentTaskEngine.taskState.value)

        // Sync theme with settings
        setOrbTheme(preferences.appTheme, preferences.customThemeColor)
        updateHomeHeaderAndVoiceStatus()
        updateVoiceOutputToggleUi(preferences.isAiVoiceResponseEnabled, animate = false)

        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.orbWebView.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            binding.orbWebView.visibility = View.VISIBLE
            binding.orbWebView.onResume()
            com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)
            setOrbTheme(preferences.appTheme, preferences.customThemeColor)
            updateHomeHeaderAndVoiceStatus()
        } else if (hidden && _binding != null) {
            binding.orbWebView.onPause()
            binding.orbWebView.visibility = View.GONE
            binding.root.visibility = View.GONE
        }
    }

    private fun checkLocationAndFetchWeather() {
        if (PermissionHelper.hasLocationPermission(requireContext())) {
            fetchLiveWeather()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun fetchLiveWeather() {
        binding.tvWeatherCondition.text = "Locating • Fetching weather..."
        viewLifecycleOwner.lifecycleScope.launch {
            val weather = LocationWeatherHelper.fetchWeather(requireContext())
            if (_binding != null && weather != null) {
                binding.tvTemperature.text = weather.temperature
                binding.tvWeatherCondition.text = weather.condition
                binding.tvWeatherDetails.text = weather.details
                binding.ivWeatherIcon.setImageResource(weather.iconResId)
                // Keep Jarvis's context aligned with the city shown on Home.
                preferences.userLocation = weather.cityName
            } else if (_binding != null) {
                binding.tvWeatherCondition.text = "Live Weather • Tap to retry"
            }
        }
    }

    private fun updateHomeHeaderAndVoiceStatus() {
        if (_binding == null) return
        val userName = preferences.userName.ifBlank { "Arpon Gang" }
        binding.tvGreeting.text = com.arponzxc.novaai.util.TimeGreetingHelper.currentGreeting(userName)

        val currentLang = preferences.language.ifBlank { "Auto" }
        binding.tvQuickLanguageStatus.text = if (currentLang.contains("Auto", ignoreCase = true)) "Auto" else currentLang
    }

    private fun updateVoiceOutputToggleUi(enabled: Boolean, animate: Boolean) {
        if (_binding == null) return
        val toggle = binding.btnVoiceOutputToggle
        val icon = binding.ivVoiceOutputIcon
        val label = binding.tvVoiceOutputStatus

        toggle.setBackgroundResource(
            if (enabled) R.drawable.bg_jarvis_voice_toggle_on else R.drawable.bg_jarvis_voice_toggle_off
        )
        icon.setImageResource(
            if (enabled) R.drawable.ic_voice_output_on else R.drawable.ic_voice_output_off
        )
        label.setText(if (enabled) R.string.voice_output_on else R.string.voice_output_off)
        label.setTextColor(if (enabled) Color.parseColor("#00F0FF") else Color.parseColor("#94A3B8"))

        if (animate) {
            toggle.animate().cancel()
            toggle.scaleX = 0.90f
            toggle.scaleY = 0.90f
            toggle.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(if (enabled) 1f else 0.85f)
                .setDuration(200L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .start()
        } else {
            toggle.alpha = if (enabled) 1f else 0.85f
        }
    }

    private fun showLanguageDialog() {
        val languages = com.arponzxc.novaai.data.model.GeminiConstants.SUPPORTED_LANGUAGES.toTypedArray()
        val currentLang = preferences.language
        val currentIndex = languages.indexOfFirst { it.equals(currentLang, ignoreCase = true) }.let { if (it >= 0) it else 0 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Conversation Language")
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val chosen = languages[which]
                preferences.language = chosen
                binding.tvQuickLanguageStatus.text = chosen
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.liveTime.collect {
                        if (_binding != null) {
                            val userName = preferences.userName.ifBlank { "Arpon Gang" }
                            binding.tvGreeting.text = com.arponzxc.novaai.util.TimeGreetingHelper.currentGreeting(userName)
                        }
                    }
                }
                launch {
                    com.arponzxc.novaai.automation.agent.AgentTaskEngine.taskState.collect { snapshot ->
                        renderTaskFallback(snapshot)
                    }
                }
                launch {
                    val screenCapture = com.arponzxc.novaai.vision.ScreenCaptureManager.getInstance()
                    combine(screenCapture.isSharing, screenCapture.hasReceivedFrame) { sharing, hasFrame -> sharing to hasFrame }
                        .collect { (sharing, hasFrame) ->
                        binding.tvQuickScreenShareStatus.text = when {
                            !sharing -> "Share screen"
                            !hasFrame -> "Starting…"
                            else -> "Stop sharing"
                        }
                        binding.tvQuickScreenShareStatus.setTextColor(
                            if (sharing && hasFrame) Color.parseColor("#10B981")
                            else ContextCompat.getColor(requireContext(), R.color.text_subtle)
                        )
                    }
                }

                launch {
                    combine(
                        viewModel.isSessionOn,
                        viewModel.isMicMuted,
                        viewModel.conversationState,
                        com.arponzxc.novaai.background.JarvisConversationService.isStandby
                    ) { isOn, isMuted, state, isStandby ->
                        HomeStatusState(isOn, isMuted, state, isStandby)
                    }.collect { status ->
                        if (!status.isOn) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "NOVA AI OFF • TAP TO START"
                            binding.tvListeningStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_subtle))
                            binding.tvHoldHint.text = "Tap mic or orb to power on"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#64748B")
                            )
                        } else if (status.isMuted) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "MIC MUTED (OFF) • TAP TO TALK"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#EF4444"))
                            binding.tvHoldHint.text = "Microphone is muted • Tap mic to unmute"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        } else if (status.isStandby) {
                            setOrbState("idle")
                            binding.tvListeningStatus.text = "STANDBY • SAY 'HEY NOVA'"
                            binding.tvListeningStatus.setTextColor(Color.parseColor("#3B82F6"))
                            binding.tvHoldHint.text = "Listening for wake word • Hold mic to power off"
                            binding.tvHoldHint.visibility = View.VISIBLE
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#3B82F6")
                            )
                        } else {
                            binding.tvHoldHint.visibility = View.VISIBLE
                            when (status.state) {
                                ConversationState.LISTENING -> {
                                    setOrbState("listening")
                                    binding.tvListeningStatus.text = "MIC ON • LISTENING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Speak now • Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                                ConversationState.SPEAKING -> {
                                    setOrbState("speaking")
                                    binding.tvListeningStatus.text = "NOVA AI SPEAKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#06B6D4"))
                                    binding.tvHoldHint.text = "Tap mic to interrupt"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#06B6D4")
                                    )
                                }
                                ConversationState.THINKING -> {
                                    setOrbState("thinking")
                                    binding.tvListeningStatus.text = "THINKING..."
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.tvHoldHint.text = "Processing request..."
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#F59E0B")
                                    )
                                }
                                ConversationState.IDLE -> {
                                    setOrbState("idle")
                                    binding.tvListeningStatus.text = "MIC ON • READY"
                                    binding.tvListeningStatus.setTextColor(Color.parseColor("#10B981"))
                                    binding.tvHoldHint.text = "Tap to mute • Hold to power off"
                                    binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                        Color.parseColor("#10B981")
                                    )
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.audioFrequency.collect { freq ->
                        pushExternalAudio(freq)
                    }
                }

                launch {
                    var isFirstEmission = true
                    viewModel.isAiVoiceResponseEnabled.collect { enabled ->
                        updateVoiceOutputToggleUi(enabled, animate = !isFirstEmission)
                        isFirstEmission = false
                    }
                }

                launch {
                    viewModel.liveError.collect { error ->
                        if (error == null) {
                            hideLiveErrorOverlay()
                        } else {
                            showLiveErrorOverlay()
                            binding.tvLiveErrorTitle.text = error.title
                            binding.tvLiveErrorMessage.text = error.message
                            binding.btnFixLiveError.text = error.actionText

                            when (error.errorCode) {
                                "API_KEY_INVALID", "API_KEY_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.settings.ApiConnectionActivity::class.java))
                                    }
                                }
                                "ACCESSIBILITY_DISABLED" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        PermissionHelper.openAccessibilitySettings(requireContext())
                                    }
                                }
                                "PERMISSIONS_MISSING" -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_settings_shield)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#F59E0B"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        startActivity(Intent(requireContext(), com.arponzxc.novaai.ui.permission.PermissionActivity::class.java))
                                    }
                                }
                                else -> {
                                    binding.ivLiveErrorIcon.setImageResource(R.drawable.ic_warning)
                                    binding.ivLiveErrorIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                                    binding.tvLiveErrorTitle.setTextColor(Color.parseColor("#EF4444"))
                                    binding.btnFixLiveError.setOnClickListener {
                                        (activity as? MainActivity)?.handleMicOrOrbClick()
                                    }
                                }
                            }
                        }
                    }
                }

                launch {
                    viewModel.connectionStatus.collect { status ->
                        if (status.startsWith("ERROR")) {
                            binding.tvListeningStatus.text = status
                            binding.dotStatusGlow.backgroundTintList = ColorStateList.valueOf(
                                Color.parseColor("#EF4444")
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showLiveErrorOverlay() {
        if (_binding == null) return
        val card = binding.cardLiveError
        if (card.visibility == View.VISIBLE && card.alpha == 1f) return
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(240)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
            .start()
    }

    private fun hideLiveErrorOverlay(onEnd: (() -> Unit)? = null) {
        if (_binding == null) {
            onEnd?.invoke()
            return
        }
        val card = binding.cardLiveError
        if (card.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        card.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180)
            .withEndAction {
                if (_binding != null) {
                    card.visibility = View.GONE
                }
                onEnd?.invoke()
            }
            .start()
    }

    private fun createTaskFallbackPanel() {
        val root = binding.root as? androidx.constraintlayout.widget.ConstraintLayout ?: return
        val panel = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(12, 20, 35))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), android.graphics.Color.rgb(34, 211, 238))
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        panel.addView(android.widget.TextView(requireContext()).apply {
            text = "NOVA AI AGENT PROGRESS"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.rgb(103, 232, 249))
        })
        val details = android.widget.TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 4
            setPadding(0, dp(5), 0, dp(2))
        }
        panel.addView(details)
        panel.addView(android.widget.Button(requireContext()).apply {
            text = "Enable floating overlay"
            isAllCaps = false
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    ))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "Open Android Settings → Apps → NOVA AI → Display over other apps.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, android.widget.LinearLayout.LayoutParams(-2, dp(44)))
        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, -2).apply {
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = dp(70)
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        root.addView(panel, params)
        taskFallbackPanel = panel
        taskFallbackText = details
    }

    private fun renderTaskFallback(snapshot: com.arponzxc.novaai.automation.agent.AgentTaskSnapshot?) {
        val panel = taskFallbackPanel ?: return
        val show = snapshot != null && !android.provider.Settings.canDrawOverlays(requireContext())
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || snapshot == null) return
        val activeStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }?.title ?: snapshot.currentStatus
        val checklist = snapshot.steps.take(4).joinToString("\n") { step ->
            val mark = if (step.status == com.arponzxc.novaai.automation.agent.TaskStepStatus.COMPLETED) "✓" else "○"
            "$mark ${step.title}"
        }
        taskFallbackText?.text = "${snapshot.originalGoal}\n${snapshot.progressPercentage}% · ${snapshot.currentStatus.replace('_', ' ')}\nCurrent: $activeStep\n$checklist"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private data class HomeStatusState(
    val isOn: Boolean,
    val isMuted: Boolean,
    val state: ConversationState,
    val isStandby: Boolean
)
