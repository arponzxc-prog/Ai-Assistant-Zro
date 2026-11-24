package com.arponzxc.novaai.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.arponzxc.novaai.data.model.ChatTurn
import com.arponzxc.novaai.data.model.GeminiConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val secureApiPrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            ENCRYPTED_API_PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Secure API-key storage could not be initialized", e)
        null
    }
    private val gson = Gson()

    init {
        migrateApiKeyToEncryptedStorage()
        val current = prefs.getString(KEY_MODEL, null)
        if (current == null || current !in GeminiConstants.SUPPORTED_MODELS) {
            prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
        }
        _aiVoiceResponseFlow.value = prefs.getBoolean(KEY_AI_VOICE_RESPONSE_ENABLED, true)
    }

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val ENCRYPTED_API_PREF_NAME = "jarvis_secure_api_prefs"
        private const val TAG = "AppPreferences"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_FAVORITE_SONG = "key_favorite_song"
        private const val KEY_FAVORITE_APP = "key_favorite_app"
        private const val KEY_SONG_COUNTS = "key_song_counts"
        private const val KEY_USER_BIRTHDAY = "key_user_birthday"
        private const val KEY_USER_LOCATION = "key_user_location"
        private const val KEY_USER_AGE = "key_user_age"
        private const val KEY_DAILY_ROUTINE = "key_daily_routine"
        private const val KEY_OWNER_VOICE_ENABLED = "key_owner_voice_enabled"
        private const val KEY_OWNER_VOICE_PROFILE = "key_owner_voice_profile"
        private const val KEY_OWNER_VOICE_SAMPLES = "key_owner_voice_samples"
        private const val KEY_OWNER_VOICE_THRESHOLD = "key_owner_voice_threshold"
        private const val KEY_OWNER_VOICE_SAMPLE_COUNT = "key_owner_voice_sample_count"
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_SILENT_EXECUTION_MODE = "key_silent_execution_mode"
        private const val KEY_TASK_OVERLAY_ENABLED = "key_task_overlay_enabled"
        private const val KEY_TASK_OVERLAY_AVATAR = "key_task_overlay_avatar"
        private const val KEY_TASK_OVERLAY_MODE = "key_task_overlay_mode"
        private const val KEY_TASK_OVERLAY_EDGE = "key_task_overlay_edge"
        private const val KEY_TASK_OVERLAY_FREE_POSITION = "key_task_overlay_free_position"
        private const val KEY_TASK_OVERLAY_X = "key_task_overlay_x"
        private const val KEY_TASK_OVERLAY_Y = "key_task_overlay_y"
        private const val KEY_APP_THEME = "key_app_theme"
        private const val KEY_CUSTOM_THEME_COLOR = "key_custom_theme_color"
        private const val KEY_ORB_ANIMATION = "key_orb_animation"
        private const val KEY_ORB_SPEED = "key_orb_speed"
        private const val KEY_ORB_GLOW = "key_orb_glow"
        private const val KEY_ORB_SCALE = "key_orb_scale"
        private const val KEY_ORB_HUE = "key_orb_hue"
        private const val KEY_CUSTOM_ENDPOINT = "key_custom_endpoint"
        private const val KEY_CAMERA_PILL_ENABLED = "key_camera_pill_enabled"
        private const val KEY_CAMERA_PILL_MODE = "key_camera_pill_mode"
        private const val KEY_CAMERA_PILL_IDLE_DELAY_MS = "key_camera_pill_idle_delay_ms"
        private const val KEY_CAMERA_PILL_AVATAR_ANIM = "key_camera_pill_avatar_anim"
        private const val KEY_CAMERA_PILL_VISUALIZER = "key_camera_pill_visualizer"
        private const val KEY_CAMERA_PILL_THINKING = "key_camera_pill_thinking"
        private const val KEY_CAMERA_PILL_SPEAKING = "key_camera_pill_speaking"
        private const val KEY_CAMERA_PILL_AVATAR_SIZE = "key_camera_pill_avatar_size"
        private const val KEY_CAMERA_PILL_INTENSITY = "key_camera_pill_intensity"
        private const val KEY_CONTINUOUS_LISTENING_ENABLED = "key_continuous_listening_enabled"
        private const val KEY_AUTO_CONTINUE_AFTER_REPLY = "key_auto_continue_after_reply"
        private const val KEY_VOICE_SILENCE_TIMEOUT_MS = "key_voice_silence_timeout_ms"
        private const val KEY_VOICE_NOISE_SENSITIVITY = "key_voice_noise_sensitivity"
        private const val KEY_BACKGROUND_VOICE_LISTENING = "key_background_voice_listening"
        private const val KEY_SAF_FOLDER_META = "key_saf_folder_meta"
        private const val KEY_STORAGE_ACTION_HISTORY = "key_storage_action_history"
        private const val KEY_AI_VOICE_RESPONSE_ENABLED = "key_ai_voice_response_enabled"
        private const val KEY_CAMERA_PILL_CUSTOM_POS_ENABLED = "key_camera_pill_custom_pos_enabled"
        private const val KEY_CAMERA_PILL_CUSTOM_X = "key_camera_pill_custom_x"
        private const val KEY_CAMERA_PILL_CUSTOM_Y = "key_camera_pill_custom_y"

        private val _aiVoiceResponseFlow = MutableStateFlow(true)
        val aiVoiceResponseFlow: StateFlow<Boolean> = _aiVoiceResponseFlow.asStateFlow()
    }

    var isOwnerVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_OWNER_VOICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OWNER_VOICE_ENABLED, value).apply()

    var ownerVoiceThreshold: Float
        get() {
            val stored = prefs.getFloat(KEY_OWNER_VOICE_THRESHOLD, 0.70f)
            // Auto-migrate older builds that saved overly strict 0.50f threshold
            return if (stored < 0.60f) 0.70f else stored
        }
        set(value) = prefs.edit().putFloat(KEY_OWNER_VOICE_THRESHOLD, value).apply()

    var ownerVoiceSampleCount: Int
        get() = prefs.getInt(KEY_OWNER_VOICE_SAMPLE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, value).apply()

    fun hasOwnerVoiceProfile(): Boolean {
        val str = prefs.getString(KEY_OWNER_VOICE_PROFILE, null)
        return !str.isNullOrBlank()
    }

    fun getOwnerVoiceProfile(): FloatArray? {
        val json = prefs.getString(KEY_OWNER_VOICE_PROFILE, null) ?: return null
        return try {
            val arr = org.json.JSONArray(json)
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    fun getOwnerVoiceSamples(): List<FloatArray> {
        val json = prefs.getString(KEY_OWNER_VOICE_SAMPLES, null) ?: return emptyList()
        return try {
            val root = org.json.JSONArray(json)
            val list = mutableListOf<FloatArray>()
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val vec = FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
                if (vec.isNotEmpty()) list.add(vec)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOwnerVoiceProfile(masterProfile: FloatArray, sampleVectors: List<FloatArray>) {
        val masterArr = org.json.JSONArray()
        for (v in masterProfile) {
            masterArr.put(v.toDouble())
        }

        val samplesArr = org.json.JSONArray()
        for (vec in sampleVectors) {
            val arr = org.json.JSONArray()
            for (v in vec) {
                arr.put(v.toDouble())
            }
            samplesArr.put(arr)
        }

        prefs.edit()
            .putString(KEY_OWNER_VOICE_PROFILE, masterArr.toString())
            .putString(KEY_OWNER_VOICE_SAMPLES, samplesArr.toString())
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleVectors.size)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
            .apply()
    }

    fun saveOwnerVoiceProfile(profile: FloatArray, sampleCount: Int) {
        val arr = org.json.JSONArray()
        for (v in profile) {
            arr.put(v.toDouble())
        }
        prefs.edit()
            .putString(KEY_OWNER_VOICE_PROFILE, arr.toString())
            .putInt(KEY_OWNER_VOICE_SAMPLE_COUNT, sampleCount)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, true)
            .apply()
    }

    fun clearOwnerVoiceProfile() {
        prefs.edit()
            .remove(KEY_OWNER_VOICE_PROFILE)
            .remove(KEY_OWNER_VOICE_SAMPLES)
            .remove(KEY_OWNER_VOICE_SAMPLE_COUNT)
            .putBoolean(KEY_OWNER_VOICE_ENABLED, false)
            .apply()
    }

    var apiKey: String
        get() = secureApiPrefs?.getString(KEY_API_KEY, "")?.trim() ?: ""
        set(value) {
            val secure = checkNotNull(secureApiPrefs) { "Secure API-key storage is unavailable on this device." }
            val clean = value.trim()
            val saved = if (clean.isEmpty()) secure.edit().remove(KEY_API_KEY).commit()
            else secure.edit().putString(KEY_API_KEY, clean).commit()
            check(saved) { "Could not save API key to encrypted storage." }
        }

    val canStoreApiKeySecurely: Boolean get() = secureApiPrefs != null

    private fun migrateApiKeyToEncryptedStorage() {
        val legacyKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        if (legacyKey.isEmpty()) return
        val secure = secureApiPrefs ?: run {
            prefs.edit().remove(KEY_API_KEY).commit()
            return
        }
        try {
            val existing = secure.getString(KEY_API_KEY, null)
            if (existing.isNullOrEmpty()) {
                check(secure.edit().putString(KEY_API_KEY, legacyKey).commit())
            }
            if (!secure.getString(KEY_API_KEY, null).isNullOrEmpty()) prefs.edit().remove(KEY_API_KEY).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy API-key migration to encrypted storage failed", e)
        }
    }

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            if (stored !in GeminiConstants.SUPPORTED_MODELS) {
                prefs.edit().putString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL).apply()
                return GeminiConstants.DEFAULT_MODEL
            }
            return stored
        }
        set(value) {
            val toSave = if (value in GeminiConstants.SUPPORTED_MODELS) value else GeminiConstants.DEFAULT_MODEL
            prefs.edit().putString(KEY_MODEL, toSave).apply()
        }

    var language: String
        get() {
            val stored = prefs.getString(KEY_LANGUAGE, GeminiConstants.DEFAULT_LANGUAGE) ?: GeminiConstants.DEFAULT_LANGUAGE
            return if (stored.contains("Auto", ignoreCase = true)) "Auto" else stored
        }
        set(value) {
            val clean = if (value.contains("Auto", ignoreCase = true)) "Auto" else value.trim()
            prefs.edit().putString(KEY_LANGUAGE, clean).apply()
        }

    var voice: String
        get() = prefs.getString(KEY_VOICE, GeminiConstants.DEFAULT_VOICE) ?: GeminiConstants.DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() {
            val stored = prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT) ?: GeminiConstants.PERSONALITY_ASSISTANT
            return when (stored) {
                "Jarvis Pro" -> GeminiConstants.PERSONALITY_ASSISTANT
                "TechGPT X Mode" -> GeminiConstants.PERSONALITY_ARPON_CORE
                else -> stored
            }
        }
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    var userName: String
        get() {
            val name = prefs.getString(KEY_USER_NAME, "") ?: ""
            return if (name.isBlank() || name == "Salaam" || name.equals("TechGPT X", ignoreCase = true) || name.equals("Rehaan", ignoreCase = true)) {
                "Arpon Gang"
            } else {
                name
            }
        }
        set(value) {
            prefs.edit().putString(KEY_USER_NAME, value.trim()).commit()
        }

    var appTheme: String
        get() {
            val stored = prefs.getString(KEY_APP_THEME, "sunset") ?: "sunset"
            return when (stored) {
                "light", "pearl_white" -> "light"
                "dark", "midnight_onyx", "cyberpunk_neon", "emerald_obsidian" -> "dark"
                "red" -> "red"
                "sunset", "rose_gold" -> "sunset"
                "custom" -> "custom"
                else -> "light"
            }
        }
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    var customThemeColor: String
        get() = prefs.getString(KEY_CUSTOM_THEME_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) = prefs.edit().putString(KEY_CUSTOM_THEME_COLOR, value.trim()).apply()

    var orbAnimation: String
        get() {
            val valid = listOf("holographic_plasma", "cosmic_pulsar", "quantum_core", "cyber_ring", "siri_glow")
            val stored = prefs.getString(KEY_ORB_ANIMATION, "holographic_plasma") ?: "holographic_plasma"
            return if (stored in valid) stored else "holographic_plasma"
        }
        set(value) = prefs.edit().putString(KEY_ORB_ANIMATION, value).apply()

    var orbSpeed: Float
        get() = prefs.getFloat(KEY_ORB_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SPEED, value).apply()

    var orbGlow: Float
        get() = prefs.getFloat(KEY_ORB_GLOW, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_GLOW, value).apply()

    var orbScale: Float
        get() = prefs.getFloat(KEY_ORB_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_ORB_SCALE, value).apply()

    var orbHue: Float
        get() = prefs.getFloat(KEY_ORB_HUE, -1f)
        set(value) = prefs.edit().putFloat(KEY_ORB_HUE, value).apply()

    var customEndpoint: String
        get() = prefs.getString(KEY_CUSTOM_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_ENDPOINT, value.trim()).apply()

    var userBirthday: String
        get() = prefs.getString(KEY_USER_BIRTHDAY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_BIRTHDAY, value.trim()).commit()
        }

    var userLocation: String
        get() = prefs.getString(KEY_USER_LOCATION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_LOCATION, value.trim()).commit()
        }

    var userAge: String
        get() = prefs.getString(KEY_USER_AGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_USER_AGE, value.trim()).commit()
        }

    var dailyRoutine: String
        get() = prefs.getString(KEY_DAILY_ROUTINE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DAILY_ROUTINE, value.trim()).commit()
        }

    var favoriteSong: String
        get() = prefs.getString(KEY_FAVORITE_SONG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_SONG, value.trim()).commit()
        }

    var favoriteApp: String
        get() = prefs.getString(KEY_FAVORITE_APP, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_FAVORITE_APP, value.trim()).commit()
        }

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var isSilentExecutionMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_EXECUTION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SILENT_EXECUTION_MODE, value).apply()

    var isAiVoiceResponseEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_VOICE_RESPONSE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AI_VOICE_RESPONSE_ENABLED, value).apply()
            _aiVoiceResponseFlow.value = value
        }

    var isTaskOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_ENABLED, value).apply()

    var taskOverlayAvatar: String
        get() = prefs.getString(KEY_TASK_OVERLAY_AVATAR, "ghost") ?: "ghost"
        set(value) {
            val available = listOf("alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower", "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "square", "star", "triangle")
            prefs.edit().putString(KEY_TASK_OVERLAY_AVATAR, value.takeIf { it in available } ?: "ghost").apply()
        }

    /** always keeps the avatar visible while the service is alive; working limits it to active tasks. */
    var taskOverlayMode: String
        get() = prefs.getString(KEY_TASK_OVERLAY_MODE, "always") ?: "always"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_MODE, if (value == "working") "working" else "always").apply()

    /** Preferred dock edge. Dragging the overlay updates this to the closest edge. */
    var taskOverlayEdge: String
        get() = prefs.getString(KEY_TASK_OVERLAY_EDGE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_TASK_OVERLAY_EDGE, value.takeIf { it in setOf("left", "right", "top", "bottom") } ?: "right").apply()

    var taskOverlayFreePosition: Boolean
        get() = prefs.getBoolean(KEY_TASK_OVERLAY_FREE_POSITION, false)
        set(value) = prefs.edit().putBoolean(KEY_TASK_OVERLAY_FREE_POSITION, value).apply()

    var taskOverlayX: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_X, value.coerceAtLeast(0)).apply()

    var taskOverlayY: Int
        get() = prefs.getInt(KEY_TASK_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_TASK_OVERLAY_Y, value.coerceAtLeast(0)).apply()

    var isCameraPillEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PILL_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_PILL_ENABLED, value).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var cameraPillDisplayMode: String
        get() = prefs.getString(KEY_CAMERA_PILL_MODE, "only_while_talking") ?: "only_while_talking"
        set(value) {
            val clean = if (value == "always_on") "always_on" else "only_while_talking"
            prefs.edit().putString(KEY_CAMERA_PILL_MODE, clean).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var cameraPillIdleDelayMs: Long
        get() = prefs.getLong(KEY_CAMERA_PILL_IDLE_DELAY_MS, 3500L).coerceIn(1500L, 10000L)
        set(value) {
            prefs.edit().putLong(KEY_CAMERA_PILL_IDLE_DELAY_MS, value.coerceIn(1500L, 10000L)).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var isCameraPillAvatarAnimationEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PILL_AVATAR_ANIM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_PILL_AVATAR_ANIM, value).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var isCameraPillVisualizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PILL_VISUALIZER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_PILL_VISUALIZER, value).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var isCameraPillShowThinking: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PILL_THINKING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_PILL_THINKING, value).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var isCameraPillShowSpeaking: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PILL_SPEAKING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_PILL_SPEAKING, value).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var cameraPillAvatarSize: Float
        get() = prefs.getFloat(KEY_CAMERA_PILL_AVATAR_SIZE, 1.0f).coerceIn(0.75f, 1.35f)
        set(value) {
            prefs.edit().putFloat(KEY_CAMERA_PILL_AVATAR_SIZE, value.coerceIn(0.75f, 1.35f)).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var cameraPillAnimationIntensity: Float
        get() = prefs.getFloat(KEY_CAMERA_PILL_INTENSITY, 1.0f).coerceIn(0.4f, 1.5f)
        set(value) {
            prefs.edit().putFloat(KEY_CAMERA_PILL_INTENSITY, value.coerceIn(0.4f, 1.5f)).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var isCameraPillCustomPositionEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_PILL_CUSTOM_POS_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CAMERA_PILL_CUSTOM_POS_ENABLED, value).apply()
            com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
        }

    var cameraPillCustomX: Int
        get() = prefs.getInt(KEY_CAMERA_PILL_CUSTOM_X, 0)
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_PILL_CUSTOM_X, value).apply()
        }

    var cameraPillCustomY: Int
        get() = prefs.getInt(KEY_CAMERA_PILL_CUSTOM_Y, 0)
        set(value) {
            prefs.edit().putInt(KEY_CAMERA_PILL_CUSTOM_Y, value).apply()
        }

    fun resetCameraPillPosition() {
        prefs.edit()
            .putBoolean(KEY_CAMERA_PILL_CUSTOM_POS_ENABLED, false)
            .remove(KEY_CAMERA_PILL_CUSTOM_X)
            .remove(KEY_CAMERA_PILL_CUSTOM_Y)
            .apply()
        com.arponzxc.novaai.ui.camerapill.CameraPillStateCoordinator.refreshConfigFromPreferences()
    }

    var isContinuousListeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_LISTENING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_LISTENING_ENABLED, value).apply()

    var isAutoContinueAfterReply: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONTINUE_AFTER_REPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONTINUE_AFTER_REPLY, value).apply()

    var voiceSilenceTimeoutMs: Long
        get() = prefs.getLong(KEY_VOICE_SILENCE_TIMEOUT_MS, 1100L).coerceIn(500L, 3500L)
        set(value) = prefs.edit().putLong(KEY_VOICE_SILENCE_TIMEOUT_MS, value.coerceIn(500L, 3500L)).apply()

    var voiceNoiseSensitivity: String
        get() = prefs.getString(KEY_VOICE_NOISE_SENSITIVITY, "BALANCED") ?: "BALANCED"
        set(value) = prefs.edit().putString(KEY_VOICE_NOISE_SENSITIVITY, value).apply()

    var isBackgroundVoiceListeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_VOICE_LISTENING, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_VOICE_LISTENING, value).apply()

    fun getVoiceListeningConfig(): com.arponzxc.novaai.audio.VoiceListeningConfig {
        return com.arponzxc.novaai.audio.VoiceListeningConfig(
            continuousModeEnabled = isContinuousListeningEnabled,
            autoResumeAfterReply = isAutoContinueAfterReply,
            silenceTimeoutMs = voiceSilenceTimeoutMs,
            noiseSensitivity = com.arponzxc.novaai.audio.NoiseSensitivity.fromPref(voiceNoiseSensitivity),
            backgroundListeningEnabled = isBackgroundVoiceListeningEnabled,
            voiceRecognitionLanguage = language
        )
    }

    var authorizedFolderMetaJson: String
        get() = prefs.getString(KEY_SAF_FOLDER_META, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SAF_FOLDER_META, value).apply()

    var storageActionHistoryJson: String
        get() = prefs.getString(KEY_STORAGE_ACTION_HISTORY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STORAGE_ACTION_HISTORY, value).apply()

    fun getSongPlayCounts(): MutableMap<String, Int> {
        val json = prefs.getString(KEY_SONG_COUNTS, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, Int>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun recordSongPlay(rawSong: String): String {
        val cleanSong = rawSong.trim()
        if (cleanSong.isBlank()) return favoriteSong

        val counts = getSongPlayCounts()
        val currentCount = counts.getOrDefault(cleanSong.lowercase(), 0) + 1
        counts[cleanSong.lowercase()] = currentCount

        // Update stored JSON
        prefs.edit().putString(KEY_SONG_COUNTS, gson.toJson(counts)).apply()

        // Auto-promote to favorite song if played at least 2 times, or if favoriteSong is empty
        val currentFav = favoriteSong
        if (currentFav.isBlank() || currentCount >= 2) {
            // Find most played song
            val topSong = counts.maxByOrNull { it.value }?.key
            if (!topSong.isNullOrBlank()) {
                favoriteSong = cleanSong
            }
        }
        return favoriteSong
    }

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
