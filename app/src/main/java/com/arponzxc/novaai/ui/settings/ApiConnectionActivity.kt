package com.arponzxc.novaai.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.arponzxc.novaai.JarvisApp
import com.arponzxc.novaai.R
import com.arponzxc.novaai.data.model.GeminiConstants
import com.arponzxc.novaai.databinding.ActivityApiConnectionBinding
import com.arponzxc.novaai.research.ApiWebSearchProvider
import com.arponzxc.novaai.research.WebSearchProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val ytPreferences by lazy { JarvisApp.instance.youTubePreferences }
    private val webSearchPreferences by lazy { JarvisApp.instance.webSearchPreferences }

    /** Keys typed for each web-search provider but not saved yet (so switching chips never loses text). */
    private val webKeyDrafts = mutableMapOf<String, String>()
    private var currentWebProvider: WebSearchProviderType = WebSearchProviderType.TAVILY
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.arponzxc.novaai.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.arponzxc.novaai.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        com.arponzxc.novaai.network.apimanager.MultiProviderRouter.syncGeminiWithPreferences(this)
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)
        binding.etYouTubeApiKey.setText(ytPreferences.youtubeApiKey)
        loadWebSearchPreferences()

        if (!preferences.canStoreApiKeySecurely) {
            binding.tvLiveStatus.text = "SECURE STORAGE UNAVAILABLE • API KEY DISABLED"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "KEY CONFIGURED • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO API KEY • ENTER KEY BELOW"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }

        if (ytPreferences.youtubeApiKey.isNotBlank()) {
            binding.tvYouTubeStatus.text = "CONFIGURED"
            binding.tvYouTubeStatus.setTextColor(Color.parseColor("#22C55E"))
        } else {
            binding.tvYouTubeStatus.text = "NOT CONFIGURED"
            binding.tvYouTubeStatus.setTextColor(Color.parseColor("#EAB308"))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }
    }

    private fun setupListeners() {
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }

        binding.btnPasteYouTubeKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etYouTubeApiKey.setText(pasted)
                Toast.makeText(this, "YouTube API Key pasted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearYouTubeKey.setOnClickListener {
            binding.etYouTubeApiKey.setText("")
        }

        setupWebSearchListeners()

        binding.btnTestConnection.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter an API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection(enteredKey)
        }

        binding.btnTestYouTubeConnection.setOnClickListener {
            val ytKey = binding.etYouTubeApiKey.text?.toString()?.trim() ?: ""
            if (ytKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter a YouTube API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testYouTubeConnection(ytKey)
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }

        binding.btnOpenMultiProviderManager.setOnClickListener {
            startActivity(android.content.Intent(this, com.arponzxc.novaai.network.apimanager.ApiManagerActivity::class.java))
        }
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val request = Request.Builder().url(url).build()
                    testHttpClient.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        if (response.isSuccessful) {
                            Pair(true, duration)
                        } else {
                            Pair(false, response.code.toLong())
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, -1L)
                }
            }

            binding.btnTestConnection.isEnabled = true
            if (result.first) {
                val ping = result.second
                binding.tvPingResult.text = "Ping: ${ping}ms • Valid"
                binding.tvLiveStatus.text = "CONNECTED • HANDSHAKE OK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Connection verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1L) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "FAILED • INVALID KEY OR NETWORK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "Connection failed. Please verify API key.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun testYouTubeConnection(apiKey: String) {
        binding.btnTestYouTubeConnection.isEnabled = false
        binding.tvYouTubeStatus.text = "TESTING..."
        binding.tvYouTubeStatus.setTextColor(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                com.arponzxc.novaai.automation.youtube.YouTubeDataRepository.testConnectionDetailed(apiKey)
            }
            binding.btnTestYouTubeConnection.isEnabled = true
            if (res.success) {
                binding.tvYouTubeStatus.text = "VERIFIED OK"
                binding.tvYouTubeStatus.setTextColor(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "YouTube API key verified successfully!", Snackbar.LENGTH_LONG).show()
            } else {
                val badge = when (res.errorCode) {
                    com.arponzxc.novaai.automation.youtube.YouTubeErrorCode.QUOTA_EXCEEDED -> "QUOTA EXCEEDED"
                    com.arponzxc.novaai.automation.youtube.YouTubeErrorCode.NETWORK_ERROR,
                    com.arponzxc.novaai.automation.youtube.YouTubeErrorCode.TIMEOUT -> "NETWORK ERROR"
                    else -> "INVALID KEY"
                }
                binding.tvYouTubeStatus.text = badge
                binding.tvYouTubeStatus.setTextColor(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, res.errorMessage.ifBlank { "YouTube API key test failed." }, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Free Web Search API key (Tavily / Serper / Exa)
    // ------------------------------------------------------------------------------------------

    private fun chipIdFor(type: WebSearchProviderType): Int = when (type) {
        WebSearchProviderType.TAVILY -> binding.chipWebTavily.id
        WebSearchProviderType.SERPER -> binding.chipWebSerper.id
        WebSearchProviderType.EXA -> binding.chipWebExa.id
    }

    private fun typeForChip(chipId: Int): WebSearchProviderType = when (chipId) {
        binding.chipWebSerper.id -> WebSearchProviderType.SERPER
        binding.chipWebExa.id -> WebSearchProviderType.EXA
        else -> WebSearchProviderType.TAVILY
    }

    private fun loadWebSearchPreferences() {
        WebSearchProviderType.entries.forEach { webKeyDrafts[it.id] = webSearchPreferences.getKey(it.id) }
        currentWebProvider = WebSearchProviderType.fromId(
            webSearchPreferences.activeProviderIdOrNull(WebSearchProviderType.allIds).orEmpty()
        ) ?: WebSearchProviderType.TAVILY
        binding.cgWebSearchProvider.check(chipIdFor(currentWebProvider))
        showWebProvider(currentWebProvider)
        if (!webSearchPreferences.isSecureStorageAvailable) {
            binding.tvWebSearchStatus.text = "SECURE STORAGE UNAVAILABLE"
            binding.tvWebSearchStatus.setTextColor(Color.parseColor("#EF4444"))
        }
    }

    private fun showWebProvider(type: WebSearchProviderType) {
        currentWebProvider = type
        binding.tvWebSearchInfo.text = type.freeTierNote
        binding.etWebSearchKey.hint = type.keyHint
        binding.etWebSearchKey.setText(webKeyDrafts[type.id].orEmpty())
        refreshWebSearchStatus()
    }

    private fun refreshWebSearchStatus() {
        if (!webSearchPreferences.isSecureStorageAvailable) return
        val hasAny = webKeyDrafts.values.any { it.isNotBlank() }
        if (hasAny) {
            binding.tvWebSearchStatus.text = "CONFIGURED"
            binding.tvWebSearchStatus.setTextColor(Color.parseColor("#22C55E"))
        } else {
            binding.tvWebSearchStatus.text = "NOT CONFIGURED"
            binding.tvWebSearchStatus.setTextColor(Color.parseColor("#EAB308"))
        }
    }

    private fun rememberCurrentWebKey() {
        webKeyDrafts[currentWebProvider.id] = binding.etWebSearchKey.text?.toString()?.trim().orEmpty()
    }

    private fun setupWebSearchListeners() {
        binding.cgWebSearchProvider.setOnCheckedStateChangeListener { _, checkedIds ->
            val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newType = typeForChip(chipId)
            if (newType == currentWebProvider) return@setOnCheckedStateChangeListener
            rememberCurrentWebKey()
            showWebProvider(newType)
        }

        binding.btnGetWebSearchKey.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(currentWebProvider.signupUrl)
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No browser found. Open ${currentWebProvider.signupUrl}", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnPasteWebSearchKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (pasted.isNotBlank()) {
                binding.etWebSearchKey.setText(pasted)
                Toast.makeText(this, "${currentWebProvider.label} key pasted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearWebSearchKey.setOnClickListener {
            binding.etWebSearchKey.setText("")
        }

        binding.etWebSearchKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                rememberCurrentWebKey()
                refreshWebSearchStatus()
            }
        })

        binding.btnTestWebSearchKey.setOnClickListener {
            val key = binding.etWebSearchKey.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                Snackbar.make(binding.root, "Please enter a ${currentWebProvider.label} API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testWebSearchKey(currentWebProvider, key)
        }
    }

    private fun testWebSearchKey(type: WebSearchProviderType, key: String) {
        binding.btnTestWebSearchKey.isEnabled = false
        binding.tvWebSearchStatus.text = "TESTING..."
        binding.tvWebSearchStatus.setTextColor(Color.parseColor("#3B82F6"))
        lifecycleScope.launch {
            val (ok, message) = withContext(Dispatchers.IO) {
                ApiWebSearchProvider.testKey(testHttpClient, type, key)
            }
            binding.btnTestWebSearchKey.isEnabled = true
            if (ok) {
                binding.tvWebSearchStatus.text = "VERIFIED OK"
                binding.tvWebSearchStatus.setTextColor(Color.parseColor("#22C55E"))
            } else {
                binding.tvWebSearchStatus.text = "KEY FAILED"
                binding.tvWebSearchStatus.setTextColor(Color.parseColor("#EF4444"))
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun saveWebSearchPreferences() {
        rememberCurrentWebKey()
        WebSearchProviderType.entries.forEach { type ->
            val draft = webKeyDrafts[type.id].orEmpty()
            if (draft != webSearchPreferences.getKey(type.id)) {
                webSearchPreferences.setKey(type.id, draft)
            }
        }
        // The shown provider becomes the active one when it has a key; otherwise fall back to any saved key.
        val shownHasKey = webKeyDrafts[currentWebProvider.id].orEmpty().isNotBlank()
        webSearchPreferences.selectedProviderId = if (shownHasKey) currentWebProvider.id else ""
    }

    private fun saveAndApply() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""
        val ytKey = binding.etYouTubeApiKey.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        try {
            preferences.apiKey = key
            ytPreferences.youtubeApiKey = ytKey
            com.arponzxc.novaai.automation.youtube.YouTubeDataRepository.clearCache()
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure key storage is unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        try {
            saveWebSearchPreferences()
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure key storage is unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp
        runCatching {
            val storage = com.arponzxc.novaai.network.apimanager.SecureKeyStorage(this)
            if (key.isNotBlank()) {
                storage.saveApiKey("gemini", key, "Primary Gemini Key")
            }
            storage.saveSelectedModel(selectedModel, "gemini")
            com.arponzxc.novaai.network.apimanager.MultiProviderRouter.syncGeminiWithPreferences(this)
        }
        com.arponzxc.novaai.background.JarvisConversationService.clearLiveError()
        com.arponzxc.novaai.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "API & Connection settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
