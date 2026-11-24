package com.arponzxc.novaai.network.apimanager

import android.content.Context
import android.util.Log
import com.arponzxc.novaai.data.model.GeminiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Unified Multi-Provider AI Router connecting Built-in (Gemini, OpenAI, Anthropic)
 * and Custom OpenAI/Anthropic/Gemini-compatible endpoints with key rotation,
 * HTTPS safety checks, connection testing, and multimodal vision support.
 */
object MultiProviderRouter {
    private const val TAG = "MultiProviderRouter"

    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    val builtinProviders = listOf(
        AiProviderConfig(
            id = "gemini",
            name = "Google Gemini AI (Built-in)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            defaultModel = GeminiConstants.DEFAULT_MODEL,
            supportsVision = true,
            supportsStreaming = true,
            supportsTools = true,
            apiFormat = AiProviderFormat.GEMINI,
            availableModels = GeminiConstants.SUPPORTED_MODELS,
            isBuiltin = true
        ),
        AiProviderConfig(
            id = "openai",
            name = "OpenAI Compatible",
            baseUrl = "https://api.openai.com/v1/",
            defaultModel = "gpt-4o-mini",
            supportsVision = true,
            supportsStreaming = true,
            supportsTools = true,
            apiFormat = AiProviderFormat.OPENAI,
            availableModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o4-mini"),
            isBuiltin = true
        ),
        AiProviderConfig(
            id = "anthropic",
            name = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com/v1/",
            defaultModel = "claude-3-5-sonnet-20241022",
            supportsVision = true,
            supportsStreaming = true,
            supportsTools = false,
            apiFormat = AiProviderFormat.ANTHROPIC,
            availableModels = listOf(
                "claude-3-5-sonnet-20241022",
                "claude-3-5-haiku-20241022",
                "claude-3-7-sonnet-20250219"
            ),
            isBuiltin = true
        )
    )

    fun getAllProviders(context: Context): List<AiProviderConfig> {
        val storage = SecureKeyStorage(context)
        val builtins = builtinProviders.map {
            it.copy(isEnabled = storage.isBuiltinProviderEnabled(it.id))
        }
        val customs = storage.getCustomProviders()
        return builtins + customs
    }

    fun getProviderById(context: Context, providerId: String): AiProviderConfig? {
        return getAllProviders(context).firstOrNull { it.id == providerId }
    }

    fun getActiveProviderConfig(context: Context): AiProviderConfig {
        val storage = SecureKeyStorage(context)
        val selectedId = storage.getSelectedProvider()
        val found = getProviderById(context, selectedId)?.takeIf { it.isEnabled }
        if (found != null) return found
        return getAllProviders(context).firstOrNull { it.isEnabled } ?: builtinProviders.first()
    }

    fun getActiveApiKey(context: Context): String {
        val storage = SecureKeyStorage(context)
        val provider = getActiveProviderConfig(context)
        val key = storage.getApiKey(provider.id)
        if (key.isNotBlank()) return key
        if (provider.id == "gemini") {
            return runCatching {
                com.arponzxc.novaai.JarvisApp.instance.preferences.apiKey
            }.getOrDefault("")
        }
        return ""
    }

    /**
     * Synchronizes Gemini primary key between AppPreferences and SecureKeyStorage
     * so legacy Gemini Voice Live WebSocket and Multi-Provider Router remain seamless.
     */
    fun syncGeminiWithPreferences(context: Context) {
        val storage = SecureKeyStorage(context)
        if (!storage.canStoreSecurely) return
        val prefs = runCatching { com.arponzxc.novaai.JarvisApp.instance.preferences }.getOrNull() ?: return
        val routerGeminiKey = storage.getApiKey("gemini")
        val prefsGeminiKey = prefs.apiKey
        when {
            routerGeminiKey.isBlank() && prefsGeminiKey.isNotBlank() -> {
                runCatching { storage.saveApiKey("gemini", prefsGeminiKey, "Primary Gemini Key") }
            }
            routerGeminiKey.isNotBlank() && prefsGeminiKey.isBlank() -> {
                runCatching { prefs.apiKey = routerGeminiKey }
            }
        }
        if (storage.getSelectedProvider() == "gemini") {
            val routerModel = storage.getSelectedModel("gemini")
            if (routerModel.isNotBlank() && routerModel in GeminiConstants.SUPPORTED_MODELS) {
                prefs.aiModel = routerModel
            }
        }
    }

    fun validateEndpoint(baseUrl: String): Boolean {
        return validateEndpointPolicy(baseUrl, allowInsecureHttpConfirmed = true).isValid
    }

    fun validateEndpointPolicy(
        baseUrl: String,
        allowInsecureHttpConfirmed: Boolean = false
    ): EndpointValidationResult {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) {
            return EndpointValidationResult(isValid = false, errorMessage = "Endpoint URL cannot be empty.")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return EndpointValidationResult(isValid = false, errorMessage = "Invalid URL syntax.")
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) {
            return EndpointValidationResult(isValid = false, errorMessage = "URL must include a valid hostname.")
        }
        if (scheme == "https") {
            return EndpointValidationResult(isValid = true, requiresHttpsWarning = false)
        }
        if (scheme == "http") {
            val isLoopback = host == "127.0.0.1" || host == "localhost"
            if (isLoopback) {
                return EndpointValidationResult(isValid = true, requiresHttpsWarning = false)
            }
            return if (allowInsecureHttpConfirmed) {
                EndpointValidationResult(isValid = true, requiresHttpsWarning = true)
            } else {
                EndpointValidationResult(
                    isValid = false,
                    requiresHttpsWarning = true,
                    errorMessage = "Plain HTTP sends credentials unencrypted. Use HTTPS or confirm insecure HTTP explicitly."
                )
            }
        }
        return EndpointValidationResult(isValid = false, errorMessage = "Only HTTPS (or local HTTP) endpoints are supported.")
    }

    fun isLiveVoiceWebSocketSupported(context: Context): Boolean {
        val active = getActiveProviderConfig(context)
        return active.id == "gemini" && active.isBuiltin
    }

    suspend fun generateText(
        context: Context,
        request: UnifiedAiTextRequest,
        providerIdOverride: String? = null,
        client: OkHttpClient = sharedHttpClient
    ): UnifiedAiResponse = withContext(Dispatchers.IO) {
        val storage = SecureKeyStorage(context)
        val provider = providerIdOverride?.let { getProviderById(context, it) } ?: getActiveProviderConfig(context)
        if (!provider.isEnabled) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                errorMessage = "Provider '${provider.name}' is currently disabled."
            )
        }
        val policy = validateEndpointPolicy(provider.baseUrl, provider.allowInsecureHttpConfirmed)
        if (!policy.isValid) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                errorMessage = policy.errorMessage ?: "Invalid endpoint configuration."
            )
        }
        val model = storage.getSelectedModel(provider.id).ifBlank { provider.defaultModel }
        val keys = resolveCandidateKeys(context, storage, provider.id)
        if (keys.isEmpty()) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                modelUsed = model,
                errorMessage = "No enabled API key configured for ${provider.name}. Open Settings -> API Manager to add one."
            )
        }

        var rotated = false
        var lastError = ""
        for ((index, keyEntry) in keys.withIndex()) {
            if (index > 0) rotated = true
            val start = System.currentTimeMillis()
            val (code, reply, err) = executeTextCall(
                client = client,
                provider = provider,
                model = model,
                apiKey = keyEntry.apiKey,
                request = request
            )
            val latency = System.currentTimeMillis() - start
            if (reply != null) {
                storage.markKeyOutcome(provider.id, keyEntry.id, success = true)
                storage.recordRequestUsage(provider.id, success = true, latencyMs = latency, rotated = rotated)
                return@withContext UnifiedAiResponse(
                    success = true,
                    text = reply,
                    providerId = provider.id,
                    providerName = provider.name,
                    modelUsed = model,
                    keyLabelUsed = keyEntry.label,
                    rotatedKey = rotated,
                    latencyMs = latency
                )
            }
            val rotatable = code == 401 || code == 403 || code == 429
            storage.markKeyOutcome(provider.id, keyEntry.id, success = false, rateLimitedOrInvalid = rotatable)
            lastError = err
            if (!rotatable && index == keys.lastIndex) {
                storage.recordRequestUsage(provider.id, success = false, latencyMs = latency, rotated = rotated, statusNote = "HTTP $code")
                break
            }
            if (index == keys.lastIndex) {
                storage.recordRequestUsage(provider.id, success = false, latencyMs = latency, rotated = rotated, statusNote = "HTTP $code")
            }
        }

        UnifiedAiResponse(
            success = false,
            providerId = provider.id,
            providerName = provider.name,
            modelUsed = model,
            rotatedKey = rotated,
            errorMessage = lastError.ifBlank { "Request to ${provider.name} failed." }
        )
    }

    suspend fun generateVisionAnalysis(
        context: Context,
        request: UnifiedAiVisionRequest,
        providerIdOverride: String? = null,
        client: OkHttpClient = sharedHttpClient
    ): UnifiedAiResponse = withContext(Dispatchers.IO) {
        val storage = SecureKeyStorage(context)
        val provider = providerIdOverride?.let { getProviderById(context, it) } ?: getActiveProviderConfig(context)
        if (!provider.isEnabled) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                errorMessage = "Provider '${provider.name}' is disabled."
            )
        }
        if (!provider.supportsVision) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                isUnsupportedCapability = true,
                errorMessage = "Unsupported: '${provider.name}' does not support Multimodal Vision. Switch to Gemini, OpenAI Vision, or Anthropic."
            )
        }
        val policy = validateEndpointPolicy(provider.baseUrl, provider.allowInsecureHttpConfirmed)
        if (!policy.isValid) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                errorMessage = policy.errorMessage ?: "Invalid endpoint."
            )
        }
        val model = storage.getSelectedModel(provider.id).ifBlank { provider.defaultModel }
        val keys = resolveCandidateKeys(context, storage, provider.id)
        if (keys.isEmpty()) {
            return@withContext UnifiedAiResponse(
                success = false,
                providerId = provider.id,
                providerName = provider.name,
                modelUsed = model,
                errorMessage = "No active API key configured for ${provider.name}."
            )
        }

        var rotated = false
        var lastError = ""
        for ((index, keyEntry) in keys.withIndex()) {
            if (index > 0) rotated = true
            val start = System.currentTimeMillis()
            val (code, reply, err) = executeVisionCall(
                client = client,
                provider = provider,
                model = model,
                apiKey = keyEntry.apiKey,
                request = request
            )
            val latency = System.currentTimeMillis() - start
            if (reply != null) {
                storage.markKeyOutcome(provider.id, keyEntry.id, success = true)
                storage.recordRequestUsage(provider.id, success = true, latencyMs = latency, rotated = rotated)
                return@withContext UnifiedAiResponse(
                    success = true,
                    text = reply,
                    providerId = provider.id,
                    providerName = provider.name,
                    modelUsed = model,
                    keyLabelUsed = keyEntry.label,
                    rotatedKey = rotated,
                    latencyMs = latency
                )
            }
            val rotatable = code == 401 || code == 403 || code == 429
            storage.markKeyOutcome(provider.id, keyEntry.id, success = false, rateLimitedOrInvalid = rotatable)
            lastError = err
            if (!rotatable || index == keys.lastIndex) {
                storage.recordRequestUsage(provider.id, success = false, latencyMs = latency, rotated = rotated, statusNote = "HTTP $code")
                if (!rotatable) break
            }
        }

        UnifiedAiResponse(
            success = false,
            providerId = provider.id,
            providerName = provider.name,
            modelUsed = model,
            rotatedKey = rotated,
            errorMessage = lastError.ifBlank { "Vision request to ${provider.name} failed." }
        )
    }

    suspend fun testProviderConnection(
        context: Context,
        provider: AiProviderConfig,
        modelOverride: String? = null,
        client: OkHttpClient = sharedHttpClient
    ): ProviderTestResult = withContext(Dispatchers.IO) {
        val policy = validateEndpointPolicy(provider.baseUrl, provider.allowInsecureHttpConfirmed)
        if (!policy.isValid) {
            return@withContext ProviderTestResult(
                success = false,
                message = policy.errorMessage ?: "Invalid endpoint URL."
            )
        }
        val storage = SecureKeyStorage(context)
        val keys = resolveCandidateKeys(context, storage, provider.id)
        if (keys.isEmpty()) {
            return@withContext ProviderTestResult(
                success = false,
                message = "Add at least one enabled API key to test ${provider.name}."
            )
        }
        val keyEntry = keys.first()
        val model = (modelOverride ?: storage.getSelectedModel(provider.id)).ifBlank { provider.defaultModel }
        val base = ensureTrailingSlash(provider.baseUrl)
        val start = System.currentTimeMillis()
        try {
            val reqBuilder = Request.Builder()
            provider.customHeaders.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) reqBuilder.header(k.trim(), v.trim())
            }
            val request = when (provider.apiFormat) {
                AiProviderFormat.GEMINI -> {
                    reqBuilder.url("${base}models?key=${keyEntry.apiKey}").get().build()
                }
                AiProviderFormat.OPENAI -> {
                    val bodyJson = buildOpenAiBody(
                        model = model,
                        request = UnifiedAiTextRequest(
                            systemPrompt = "Respond with OK only.",
                            userMessage = "ping",
                            maxTokens = 8
                        )
                    )
                    reqBuilder
                        .url("${base}chat/completions")
                        .header("Authorization", "Bearer ${keyEntry.apiKey}")
                        .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                AiProviderFormat.ANTHROPIC -> {
                    val bodyJson = buildAnthropicBody(
                        model = model,
                        request = UnifiedAiTextRequest(
                            systemPrompt = "Respond with OK only.",
                            userMessage = "ping",
                            maxTokens = 8
                        )
                    )
                    reqBuilder
                        .url("${base}messages")
                        .header("x-api-key", keyEntry.apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
            }

            client.newCall(request).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    storage.recordRequestUsage(provider.id, success = true, latencyMs = latency, statusNote = "Verified (${latency}ms)")
                    ProviderTestResult(
                        success = true,
                        statusCode = resp.code,
                        latencyMs = latency,
                        testedKeyLabel = keyEntry.label,
                        message = "Connected (${latency}ms) • Key '${keyEntry.label}' verified"
                    )
                } else {
                    val reason = parseProviderError(raw, resp.code)
                    storage.recordRequestUsage(provider.id, success = false, latencyMs = latency, statusNote = "HTTP ${resp.code}")
                    ProviderTestResult(
                        success = false,
                        statusCode = resp.code,
                        latencyMs = latency,
                        testedKeyLabel = keyEntry.label,
                        message = reason
                    )
                }
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            Log.w(TAG, "Connection test failed for ${provider.id}: ${e.javaClass.simpleName}")
            storage.recordRequestUsage(provider.id, success = false, latencyMs = latency, statusNote = "Network Error")
            ProviderTestResult(
                success = false,
                latencyMs = latency,
                testedKeyLabel = keyEntry.label,
                message = "Network error: ${e.message ?: "Unable to reach endpoint"}"
            )
        }
    }

    private fun resolveCandidateKeys(
        context: Context,
        storage: SecureKeyStorage,
        providerId: String
    ): List<ApiKeyEntry> {
        val now = System.currentTimeMillis()
        val stored = storage.getApiKeyEntries(providerId).toMutableList()
        if (stored.isEmpty() && providerId == "gemini") {
            val legacy = runCatching { com.arponzxc.novaai.JarvisApp.instance.preferences.apiKey }.getOrDefault("")
            if (legacy.isNotBlank()) {
                stored.add(ApiKeyEntry(id = "legacy_gemini", label = "Primary Gemini Key", apiKey = legacy, isEnabled = true))
            }
        }
        val ready = stored.filter { it.isAvailableNow(now) }
        if (ready.isNotEmpty()) return ready
        return stored.filter { it.isEnabled && it.apiKey.isNotBlank() }
    }

    private fun executeTextCall(
        client: OkHttpClient,
        provider: AiProviderConfig,
        model: String,
        apiKey: String,
        request: UnifiedAiTextRequest
    ): Triple<Int, String?, String> {
        val base = ensureTrailingSlash(provider.baseUrl)
        return try {
            val reqBuilder = Request.Builder()
            provider.customHeaders.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) reqBuilder.header(k.trim(), v.trim())
            }
            val okReq = when (provider.apiFormat) {
                AiProviderFormat.GEMINI -> {
                    val cleanModel = model.removePrefix("models/").ifBlank { "gemini-2.5-flash" }
                    val body = buildGeminiTextBody(request)
                    reqBuilder
                        .url("${base}models/$cleanModel:generateContent?key=$apiKey")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                AiProviderFormat.OPENAI -> {
                    val body = buildOpenAiBody(model, request)
                    reqBuilder
                        .url("${base}chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                AiProviderFormat.ANTHROPIC -> {
                    val body = buildAnthropicBody(model, request)
                    reqBuilder
                        .url("${base}messages")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
            }

            client.newCall(okReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Triple(resp.code, null, parseProviderError(raw, resp.code))
                }
                val parsed = extractTextFromResponse(provider.apiFormat, raw)
                if (parsed.isNullOrBlank()) {
                    Triple(resp.code, null, "Provider returned an empty response.")
                } else {
                    Triple(resp.code, parsed, "")
                }
            }
        } catch (e: Exception) {
            Triple(-1, null, "Network error: ${e.message ?: "Request failed"}")
        }
    }

    private fun executeVisionCall(
        client: OkHttpClient,
        provider: AiProviderConfig,
        model: String,
        apiKey: String,
        request: UnifiedAiVisionRequest
    ): Triple<Int, String?, String> {
        val base = ensureTrailingSlash(provider.baseUrl)
        return try {
            val reqBuilder = Request.Builder()
            provider.customHeaders.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) reqBuilder.header(k.trim(), v.trim())
            }
            val okReq = when (provider.apiFormat) {
                AiProviderFormat.GEMINI -> {
                    val cleanModel = model.removePrefix("models/").ifBlank { "gemini-2.5-flash" }
                    val body = buildGeminiVisionBody(request)
                    reqBuilder
                        .url("${base}models/$cleanModel:generateContent?key=$apiKey")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                AiProviderFormat.OPENAI -> {
                    val body = buildOpenAiVisionBody(model, request)
                    reqBuilder
                        .url("${base}chat/completions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
                AiProviderFormat.ANTHROPIC -> {
                    val body = buildAnthropicVisionBody(model, request)
                    reqBuilder
                        .url("${base}messages")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                }
            }

            client.newCall(okReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Triple(resp.code, null, parseProviderError(raw, resp.code))
                }
                val parsed = extractTextFromResponse(provider.apiFormat, raw)
                if (parsed.isNullOrBlank()) {
                    Triple(resp.code, null, "Vision provider returned an empty response.")
                } else {
                    Triple(resp.code, parsed, "")
                }
            }
        } catch (e: Exception) {
            Triple(-1, null, "Network error: ${e.message ?: "Vision request failed"}")
        }
    }

    internal fun buildGeminiTextBody(request: UnifiedAiTextRequest): JSONObject {
        val contents = JSONArray()
        request.history.takeLast(8).forEach { turn ->
            val role = if (turn.role == "assistant") "model" else "user"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", request.userMessage)))
        })
        return JSONObject().apply {
            if (request.systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt)))
                })
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", request.temperature)
                put("maxOutputTokens", request.maxTokens)
            })
        }
    }

    internal fun buildGeminiVisionBody(request: UnifiedAiVisionRequest): JSONObject {
        val parts = JSONArray().apply {
            put(JSONObject().put("text", request.userPrompt))
            put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", request.mimeType)
                    put("data", request.imageBase64)
                })
            })
        }
        return JSONObject().apply {
            if (request.systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt)))
                })
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", parts)
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", request.temperature)
                put("maxOutputTokens", request.maxTokens)
            })
        }
    }

    internal fun buildOpenAiBody(model: String, request: UnifiedAiTextRequest): JSONObject {
        val msgs = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        }
        request.history.takeLast(8).forEach { turn ->
            msgs.put(JSONObject().put("role", if (turn.role == "assistant") "assistant" else "user").put("content", turn.content))
        }
        msgs.put(JSONObject().put("role", "user").put("content", request.userMessage))
        return JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
        }
    }

    internal fun buildOpenAiVisionBody(model: String, request: UnifiedAiVisionRequest): JSONObject {
        val msgs = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            msgs.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        }
        val contentArr = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", request.userPrompt))
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:${request.mimeType};base64,${request.imageBase64}"))
            })
        }
        msgs.put(JSONObject().put("role", "user").put("content", contentArr))
        return JSONObject().apply {
            put("model", model)
            put("messages", msgs)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
        }
    }

    internal fun buildAnthropicBody(model: String, request: UnifiedAiTextRequest): JSONObject {
        val msgs = JSONArray()
        request.history.takeLast(8).forEach { turn ->
            msgs.put(JSONObject().put("role", if (turn.role == "assistant") "assistant" else "user").put("content", turn.content))
        }
        msgs.put(JSONObject().put("role", "user").put("content", request.userMessage))
        return JSONObject().apply {
            put("model", model)
            if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
            put("messages", msgs)
            put("max_tokens", request.maxTokens)
        }
    }

    internal fun buildAnthropicVisionBody(model: String, request: UnifiedAiVisionRequest): JSONObject {
        val contentArr = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", request.mimeType)
                    put("data", request.imageBase64)
                })
            })
            put(JSONObject().put("type", "text").put("text", request.userPrompt))
        }
        return JSONObject().apply {
            put("model", model)
            if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", contentArr)))
            put("max_tokens", request.maxTokens)
        }
    }

    internal fun extractTextFromResponse(format: AiProviderFormat, rawJson: String): String? {
        return runCatching {
            val root = JSONObject(rawJson)
            when (format) {
                AiProviderFormat.GEMINI -> {
                    val parts = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: return null
                    buildString {
                        for (i in 0 until parts.length()) {
                            val txt = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                            if (txt.isNotBlank()) append(txt)
                        }
                    }.trim()
                }
                AiProviderFormat.OPENAI -> {
                    root.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        ?.trim()
                }
                AiProviderFormat.ANTHROPIC -> {
                    val content = root.optJSONArray("content") ?: return null
                    buildString {
                        for (i in 0 until content.length()) {
                            val block = content.optJSONObject(i)
                            if (block?.optString("type") == "text") {
                                append(block.optString("text", ""))
                            }
                        }
                    }.trim()
                }
            }
        }.getOrNull()
    }

    private fun parseProviderError(raw: String, code: Int): String {
        val msg = runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message")
                ?.ifBlank { root.optString("message", "") }
                ?: root.optString("message", "")
        }.getOrDefault("")
        return when {
            code == 401 || code == 403 -> "Authentication error (HTTP $code): ${msg.ifBlank { "Invalid or unauthorized API key" }}"
            code == 429 -> "Rate limit / quota reached (HTTP 429): ${msg.ifBlank { "Too many requests; key rotation triggered" }}"
            msg.isNotBlank() -> "HTTP $code: $msg"
            else -> "Provider returned HTTP $code"
        }
    }

    private fun ensureTrailingSlash(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
