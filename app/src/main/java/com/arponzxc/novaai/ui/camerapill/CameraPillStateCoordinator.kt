package com.arponzxc.novaai.ui.camerapill

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.background.CameraPillOverlayService
import com.arponzxc.novaai.background.JarvisConversationService
import com.arponzxc.novaai.data.model.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Central state coordinator connecting:
 * 1) Persisted Camera Pill preferences
 * 2) Existing NOVA AI voice engine flows (ConversationState, audioLevel, audioFrequency, connectionStatus, liveError, TTS)
 * 3) Connectivity state (for OFFLINE state)
 * 4) Only While Talking vs Always On visibility policy
 *
 * Does NOT open any camera or duplicate microphone recorder.
 */
object CameraPillStateCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _config = MutableStateFlow(CameraPillConfig())
    val config: StateFlow<CameraPillConfig> = _config.asStateFlow()

    private val _uiState = MutableStateFlow(CameraPillUiState())
    val uiState: StateFlow<CameraPillUiState> = _uiState.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isTtsSpeaking = MutableStateFlow(false)
    private val _interruptedUntilMs = MutableStateFlow(0L)
    private val _happyUntilMs = MutableStateFlow(0L)
    private val _previewState = MutableStateFlow<CameraPillAiState?>(null)
    private val _isExpanded = MutableStateFlow(false)
    private val _isMinimized = MutableStateFlow(false)
    private val _hasOverlayPermission = MutableStateFlow(false)
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    @Volatile
    private var lastActiveVoiceTimestampMs: Long = 0L

    @Volatile
    private var initialized = false

    private var appContext: Context? = null
    private var idleHideJob: Job? = null
    private var transientResetJob: Job? = null
    private var previewCycleJob: Job? = null
    private var networkCallbackRegistered = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (initialized) {
            refreshConfigFromPreferences()
            refreshOverlayPermission()
            return
        }
        initialized = true
        refreshConfigFromPreferences()
        refreshOverlayPermission()
        registerNetworkMonitor(context.applicationContext)
        startObservingVoiceEngine()
    }

    fun refreshOverlayPermission() {
        val ctx = appContext ?: return
        val granted = Settings.canDrawOverlays(ctx)
        _hasOverlayPermission.value = granted
        if (!granted && _previewState.value != null) {
            stopPreview()
        }
        syncOverlayServiceLifecycle()
    }

    fun refreshConfigFromPreferences() {
        val prefs = try {
            JarvisApp.instance.preferences
        } catch (_: Exception) {
            null
        } ?: return

        val next = CameraPillConfig(
            enabled = prefs.isCameraPillEnabled,
            displayMode = CameraPillDisplayMode.fromPref(prefs.cameraPillDisplayMode),
            idleHideDelayMs = prefs.cameraPillIdleDelayMs,
            avatarAnimationEnabled = prefs.isCameraPillAvatarAnimationEnabled,
            audioVisualizerEnabled = prefs.isCameraPillVisualizerEnabled,
            showThinkingAnimation = prefs.isCameraPillShowThinking,
            showSpeakingAnimation = prefs.isCameraPillShowSpeaking,
            avatarSizeScale = prefs.cameraPillAvatarSize,
            animationIntensity = prefs.cameraPillAnimationIntensity
        )
        _config.value = next
        if (!next.enabled && _previewState.value == null) {
            _isExpanded.value = false
            _isMinimized.value = false
        }
        recomputeUiState()
        syncOverlayServiceLifecycle()
    }

    fun notifyInterrupted() {
        val now = System.currentTimeMillis()
        lastActiveVoiceTimestampMs = now
        _interruptedUntilMs.value = now + 700L
        recomputeUiState()
        scheduleTransientRefresh(720L)
    }

    fun notifyHappyResponse() {
        val now = System.currentTimeMillis()
        lastActiveVoiceTimestampMs = now
        _happyUntilMs.value = now + 1500L
        recomputeUiState()
        scheduleTransientRefresh(1550L)
    }

    fun setTtsSpeaking(speaking: Boolean) {
        if (speaking) {
            lastActiveVoiceTimestampMs = System.currentTimeMillis()
        }
        _isTtsSpeaking.value = speaking
        recomputeUiState()
    }

    fun toggleExpanded() {
        if (_isMinimized.value) {
            _isMinimized.value = false
            _isExpanded.value = false
        } else {
            _isExpanded.value = !_isExpanded.value
        }
        recomputeUiState()
    }

    fun setExpanded(expanded: Boolean) {
        _isExpanded.value = expanded
        if (expanded) _isMinimized.value = false
        recomputeUiState()
    }

    fun toggleMinimized() {
        _isMinimized.value = !_isMinimized.value
        if (_isMinimized.value) {
            _isExpanded.value = false
        }
        recomputeUiState()
    }

    fun setMinimized(minimized: Boolean) {
        _isMinimized.value = minimized
        if (minimized) _isExpanded.value = false
        recomputeUiState()
    }

    fun resetPositionToCutout() {
        runCatching { JarvisApp.instance.preferences.resetCameraPillPosition() }
        recomputeUiState()
    }

    fun toggleListeningFromPill() {
        if (JarvisConversationService.isListeningPaused.value) {
            JarvisConversationService.resumeListening()
        } else {
            JarvisConversationService.pauseListening()
        }
        recomputeUiState()
    }

    fun stopSpeakingFromPill() {
        JarvisConversationService.stopSpeakingNow()
        recomputeUiState()
    }

    /**
     * Starts an interactive live preview of the Camera Pill across all visual states.
     */
    fun startPreview() {
        previewCycleJob?.cancel()
        val states = listOf(
            CameraPillAiState.LISTENING,
            CameraPillAiState.THINKING,
            CameraPillAiState.SPEAKING,
            CameraPillAiState.INTERRUPTED,
            CameraPillAiState.IDLE
        )
        _previewState.value = states.first()
        _happyUntilMs.value = 0L
        recomputeUiState()
        syncOverlayServiceLifecycle()

        previewCycleJob = scope.launch {
            var index = 0
            while (true) {
                val state = states[index % states.size]
                _previewState.value = state
                if (state == CameraPillAiState.IDLE) {
                    _happyUntilMs.value = System.currentTimeMillis() + 1400L
                }
                recomputeUiState()
                delay(if (state == CameraPillAiState.INTERRUPTED) 1100L else 2200L)
                index++
            }
        }
    }

    fun stopPreview() {
        previewCycleJob?.cancel()
        previewCycleJob = null
        _previewState.value = null
        recomputeUiState()
        syncOverlayServiceLifecycle()
    }

    private fun scheduleTransientRefresh(delayMs: Long) {
        transientResetJob?.cancel()
        transientResetJob = scope.launch {
            delay(delayMs)
            recomputeUiState()
            syncOverlayServiceLifecycle()
        }
    }

    private fun startObservingVoiceEngine() {
        scope.launch {
            combine(
                JarvisConversationService.isSessionOn,
                JarvisConversationService.isStandby,
                JarvisConversationService.conversationState,
                JarvisConversationService.connectionStatus,
                JarvisConversationService.liveError
            ) { sessionOn, standby, convState, connStatus, liveErr ->
                VoiceSnapshot(sessionOn, standby, convState, connStatus, liveErr != null)
            }.collect {
                if (it.convState != ConversationState.IDLE || it.hasError) {
                    lastActiveVoiceTimestampMs = System.currentTimeMillis()
                }
                recomputeUiState()
                syncOverlayServiceLifecycle()
            }
        }

        scope.launch {
            combine(
                JarvisConversationService.audioLevel,
                JarvisConversationService.audioFrequency,
                _isTtsSpeaking,
                _isOnline
            ) { level, freq, ttsSpeaking, online ->
                AudioSnapshot(level, freq.bass, freq.mid, freq.high, ttsSpeaking, online)
            }.collect {
                if (it.ttsSpeaking) {
                    lastActiveVoiceTimestampMs = System.currentTimeMillis()
                }
                recomputeUiState()
            }
        }
    }

    private fun recomputeUiState() {
        val now = System.currentTimeMillis()
        val cfg = _config.value
        val preview = _previewState.value
        val isPreview = preview != null
        val sessionOn = JarvisConversationService.isSessionOn.value
        val standby = JarvisConversationService.isStandby.value
        val convState = JarvisConversationService.conversationState.value
        val connStatus = JarvisConversationService.connectionStatus.value
        val liveErr = JarvisConversationService.liveError.value
        val audioLevel = JarvisConversationService.audioLevel.value
        val freq = JarvisConversationService.audioFrequency.value
        val ttsSpeaking = _isTtsSpeaking.value
        val online = _isOnline.value

        val rawAiState = when {
            preview != null -> preview
            !online && !sessionOn -> CameraPillAiState.OFFLINE
            liveErr != null || connStatus.startsWith("ERROR", ignoreCase = true) -> CameraPillAiState.ERROR
            now < _interruptedUntilMs.value -> CameraPillAiState.INTERRUPTED
            ttsSpeaking || convState == ConversationState.SPEAKING ->
                if (cfg.showSpeakingAnimation) CameraPillAiState.SPEAKING else CameraPillAiState.IDLE
            convState == ConversationState.THINKING ->
                if (cfg.showThinkingAnimation) CameraPillAiState.THINKING else CameraPillAiState.IDLE
            convState == ConversationState.LISTENING -> CameraPillAiState.LISTENING
            !online && !connStatus.equals("LIVE", ignoreCase = true) -> CameraPillAiState.OFFLINE
            else -> CameraPillAiState.IDLE
        }

        val activeTalkingOrProcessing = rawAiState in setOf(
            CameraPillAiState.LISTENING,
            CameraPillAiState.THINKING,
            CameraPillAiState.SPEAKING,
            CameraPillAiState.INTERRUPTED,
            CameraPillAiState.ERROR
        )
        if (activeTalkingOrProcessing) {
            lastActiveVoiceTimestampMs = now
        }

        val shouldShow = computeVisibility(
            config = cfg,
            hasOverlayPermission = _hasOverlayPermission.value,
            isSessionActive = sessionOn,
            isStandbyActive = standby,
            aiState = rawAiState,
            isPreviewActive = isPreview,
            lastActiveTimestampMs = lastActiveVoiceTimestampMs,
            nowMs = now
        )

        if (shouldShow && cfg.displayMode == CameraPillDisplayMode.ONLY_WHILE_TALKING &&
            !activeTalkingOrProcessing && !isPreview
        ) {
            val remaining = (lastActiveVoiceTimestampMs + cfg.idleHideDelayMs) - now
            if (remaining > 0L) {
                scheduleIdleHideCheck(remaining + 60L)
            }
        }

        val previewWave = if (isPreview) {
            when (rawAiState) {
                CameraPillAiState.LISTENING -> 0.62f
                CameraPillAiState.SPEAKING -> 0.78f
                else -> 0.15f
            }
        } else 0f

        val userAmp = when {
            isPreview && rawAiState == CameraPillAiState.LISTENING -> previewWave
            rawAiState == CameraPillAiState.LISTENING -> audioLevel.coerceIn(0f, 1f)
            else -> 0f
        }
        val aiAmp = when {
            isPreview && rawAiState == CameraPillAiState.SPEAKING -> previewWave
            ttsSpeaking -> 0.68f
            rawAiState == CameraPillAiState.SPEAKING -> audioLevel.coerceIn(0f, 1f)
            else -> 0f
        }

        val statusLabel = when (rawAiState) {
            CameraPillAiState.IDLE -> if (standby) "Say \"Hey Nova\"" else "NOVA AI Ready"
            CameraPillAiState.LISTENING -> "Listening…"
            CameraPillAiState.THINKING -> "Thinking…"
            CameraPillAiState.SPEAKING -> "NOVA Speaking"
            CameraPillAiState.INTERRUPTED -> "Listening again…"
            CameraPillAiState.ERROR -> liveErr?.title ?: "Connection Issue"
            CameraPillAiState.OFFLINE -> "Offline"
        }

        val hasCustomPos = runCatching {
            JarvisApp.instance.preferences.isCameraPillCustomPositionEnabled
        }.getOrDefault(false)

        _uiState.value = CameraPillUiState(
            config = cfg,
            aiState = rawAiState,
            userAmplitude = userAmp,
            aiAmplitude = aiAmp,
            bass = if (isPreview) previewWave * 0.8f else freq.bass,
            mid = if (isPreview) previewWave else freq.mid,
            high = if (isPreview) previewWave * 0.6f else freq.high,
            isHappyResponse = now < _happyUntilMs.value,
            isTtsSpeaking = ttsSpeaking,
            isSessionActive = sessionOn,
            isStandbyActive = standby,
            isPreviewActive = isPreview,
            isExpanded = _isExpanded.value,
            isMinimized = _isMinimized.value,
            hasCustomPosition = hasCustomPos,
            statusText = statusLabel,
            errorDetail = liveErr?.message,
            shouldShowOverlay = shouldShow
        )
    }

    private fun scheduleIdleHideCheck(delayMs: Long) {
        idleHideJob?.cancel()
        idleHideJob = scope.launch {
            delay(delayMs.coerceAtLeast(100L))
            recomputeUiState()
            syncOverlayServiceLifecycle()
        }
    }

    /**
     * Pure visibility policy for both Only While Talking and Always On modes (unit-testable).
     */
    fun computeVisibility(
        config: CameraPillConfig,
        hasOverlayPermission: Boolean,
        isSessionActive: Boolean,
        isStandbyActive: Boolean,
        aiState: CameraPillAiState,
        isPreviewActive: Boolean,
        lastActiveTimestampMs: Long,
        nowMs: Long
    ): Boolean {
        if (!hasOverlayPermission) return false
        if (isPreviewActive) return true
        if (!config.enabled) return false

        val assistantRunning = isSessionActive || isStandbyActive
        if (!assistantRunning) return false

        return when (config.displayMode) {
            CameraPillDisplayMode.ALWAYS_ON -> true
            CameraPillDisplayMode.ONLY_WHILE_TALKING -> {
                val activeNow = aiState in setOf(
                    CameraPillAiState.LISTENING,
                    CameraPillAiState.THINKING,
                    CameraPillAiState.SPEAKING,
                    CameraPillAiState.INTERRUPTED,
                    CameraPillAiState.ERROR
                )
                activeNow || (lastActiveTimestampMs > 0L && (nowMs - lastActiveTimestampMs) < config.idleHideDelayMs)
            }
        }
    }

    fun syncOverlayServiceLifecycle() {
        val ctx = appContext ?: return
        val state = _uiState.value
        val shouldServiceRun = state.shouldShowOverlay
        if (shouldServiceRun) {
            CameraPillOverlayService.start(ctx)
        } else if (CameraPillOverlayService.isRunning.value) {
            CameraPillOverlayService.stop(ctx)
        }
    }

    private fun registerNetworkMonitor(context: Context) {
        if (networkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        _isOnline.value = checkIsCurrentlyOnline(cm)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch {
                            _isOnline.value = true
                            recomputeUiState()
                        }
                    }

                    override fun onLost(network: Network) {
                        scope.launch {
                            _isOnline.value = checkIsCurrentlyOnline(cm)
                            recomputeUiState()
                        }
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        val online = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        scope.launch {
                            _isOnline.value = online
                            recomputeUiState()
                        }
                    }
                })
                networkCallbackRegistered = true
            }
        } catch (_: Exception) {
        }
    }

    private fun checkIsCurrentlyOnline(cm: ConnectivityManager): Boolean {
        return try {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private data class VoiceSnapshot(
        val sessionOn: Boolean,
        val standby: Boolean,
        val convState: ConversationState,
        val connStatus: String,
        val hasError: Boolean
    )

    private data class AudioSnapshot(
        val level: Float,
        val bass: Float,
        val mid: Float,
        val high: Float,
        val ttsSpeaking: Boolean,
        val online: Boolean
    )
}
