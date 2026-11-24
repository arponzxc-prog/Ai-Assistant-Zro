package com.arponzxc.novaai.network.apimanager

import java.util.UUID

enum class AiProviderFormat(val label: String) {
    GEMINI("Google Gemini REST"),
    OPENAI("OpenAI Compatible (/chat/completions)"),
    ANTHROPIC("Anthropic Claude (/messages)")
}

enum class AiCapability(val label: String) {
    TEXT_CHAT("Text Chat"),
    VISION("Multimodal Vision"),
    LIVE_VOICE_WS("Live Voice WebSocket"),
    RESEARCH_SYNTHESIS("Research Synthesis"),
    FILE_ANALYSIS("File & Code Analysis")
}

data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val label: String = "Primary Key",
    val apiKey: String = "",
    val isEnabled: Boolean = true,
    val lastUsedMs: Long = 0L,
    val cooldownUntilMs: Long = 0L,
    val failureCount: Int = 0
) {
    val maskedKey: String
        get() = maskApiKey(apiKey)

    fun isAvailableNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isEnabled && apiKey.isNotBlank() && nowMs >= cooldownUntilMs
    }

    companion object {
        fun maskApiKey(raw: String): String {
            val clean = raw.trim()
            if (clean.isEmpty()) return "Not configured"
            if (clean.length <= 8) return "••••${clean.takeLast(2)}"
            return "${clean.take(4)}••••••${clean.takeLast(4)}"
        }
    }
}

data class AiProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsVision: Boolean = true,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = false,
    val apiFormat: AiProviderFormat = AiProviderFormat.OPENAI,
    val availableModels: List<String> = listOf(defaultModel),
    val customHeaders: Map<String, String> = emptyMap(),
    val isBuiltin: Boolean = true,
    val isEnabled: Boolean = true,
    val allowInsecureHttpConfirmed: Boolean = false
) {
    fun supportedCapabilities(): Set<AiCapability> {
        val set = mutableSetOf(
            AiCapability.TEXT_CHAT,
            AiCapability.RESEARCH_SYNTHESIS,
            AiCapability.FILE_ANALYSIS
        )
        if (supportsVision) set.add(AiCapability.VISION)
        if (id == "gemini" && apiFormat == AiProviderFormat.GEMINI && isBuiltin) {
            set.add(AiCapability.LIVE_VOICE_WS)
        }
        return set
    }
}

data class ProviderUsageStats(
    val providerId: String,
    val totalRequests: Long = 0L,
    val successfulRequests: Long = 0L,
    val failedRequests: Long = 0L,
    val keyRotations: Long = 0L,
    val lastUsedMs: Long = 0L,
    val lastLatencyMs: Long = -1L,
    val lastStatus: String = "Untested"
)

data class EndpointValidationResult(
    val isValid: Boolean,
    val requiresHttpsWarning: Boolean = false,
    val errorMessage: String? = null
)

data class AiChatMessageInput(
    val role: String, // "user" or "assistant"
    val content: String
)

data class UnifiedAiTextRequest(
    val systemPrompt: String,
    val userMessage: String,
    val history: List<AiChatMessageInput> = emptyList(),
    val temperature: Double = 0.6,
    val maxTokens: Int = 2048
)

data class UnifiedAiVisionRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val imageBase64: String,
    val mimeType: String = "image/jpeg",
    val temperature: Double = 0.35,
    val maxTokens: Int = 1500
)

data class UnifiedAiResponse(
    val success: Boolean,
    val text: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val modelUsed: String = "",
    val keyLabelUsed: String = "",
    val rotatedKey: Boolean = false,
    val latencyMs: Long = 0L,
    val errorMessage: String = "",
    val isUnsupportedCapability: Boolean = false
)

data class ProviderTestResult(
    val success: Boolean,
    val statusCode: Int = -1,
    val latencyMs: Long = 0L,
    val message: String = "",
    val testedKeyLabel: String = ""
)
