package com.arponzxc.novaai.network

import android.util.Log
import com.arponzxc.novaai.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

enum class GeminiTextChatError {
    NONE,
    API_KEY_MISSING,
    API_KEY_INVALID,
    QUOTA_EXCEEDED,
    TIMEOUT,
    NETWORK_ERROR,
    MODEL_ERROR,
    UNKNOWN
}

data class GeminiTextChatResult(
    val success: Boolean,
    val replyText: String = "",
    val error: GeminiTextChatError = GeminiTextChatError.NONE,
    val errorMessage: String = "",
    val executedTools: List<String> = emptyList()
)

/**
 * Reliable REST generateContent engine for NOVA AI text-based chat and tool execution
 * (supports Bengali, English, conversation history, and all YouTube/device function tools)
 * when Voice Output is OFF or when the Live WebSocket voice session is offline.
 */
class GeminiTextChatEngine(
    private val httpClient: OkHttpClient = defaultClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        private const val TAG = "GeminiTextChatEngine"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TOOL_ROUNDS = 5

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()

        fun resolveCandidateRestModels(configuredModel: String): List<String> {
            val cleaned = configuredModel.trim()
                .removePrefix("models/")
                .lowercase()
            return when {
                cleaned.isBlank() ||
                    cleaned.contains("live") ||
                    cleaned.contains("native-audio") ||
                    cleaned.contains("agent") -> listOf(
                    "gemini-2.5-flash",
                    "gemini-2.0-flash",
                    "gemini-1.5-flash"
                )
                else -> listOf(
                    configuredModel.trim().removePrefix("models/"),
                    "gemini-2.5-flash",
                    "gemini-2.0-flash"
                ).distinct()
            }
        }
    }

    suspend fun generateReply(
        apiKey: String,
        configuredModel: String,
        systemPrompt: String,
        recentTurns: List<ChatTurn>,
        userMessage: String,
        toolDeclarations: JSONArray = GeminiLiveWebSocket.buildToolDeclarations(),
        toolExecutor: (suspend (name: String, args: JSONObject) -> JSONObject)? = null
    ): GeminiTextChatResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanUser = userMessage.trim()
        if (cleanKey.isBlank()) {
            return@withContext GeminiTextChatResult(
                success = false,
                error = GeminiTextChatError.API_KEY_MISSING,
                errorMessage = "Gemini API Key is missing. Please configure it in Settings."
            )
        }
        if (cleanUser.isBlank()) {
            return@withContext GeminiTextChatResult(
                success = false,
                error = GeminiTextChatError.UNKNOWN,
                errorMessage = "Empty prompt."
            )
        }

        val models = resolveCandidateRestModels(configuredModel)
        var lastResult = GeminiTextChatResult(
            success = false,
            error = GeminiTextChatError.UNKNOWN,
            errorMessage = "Failed to generate a response."
        )

        for (modelId in models) {
            val attempt = executeConversationForModel(
                apiKey = cleanKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                recentTurns = recentTurns,
                userMessage = cleanUser,
                toolDeclarations = toolDeclarations,
                toolExecutor = toolExecutor
            )
            if (attempt.success) return@withContext attempt
            lastResult = attempt
            // Only fall back to another model if the model ID itself is unsupported/not found
            if (attempt.error != GeminiTextChatError.MODEL_ERROR) {
                return@withContext attempt
            }
        }
        return@withContext lastResult
    }

    private suspend fun executeConversationForModel(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        recentTurns: List<ChatTurn>,
        userMessage: String,
        toolDeclarations: JSONArray,
        toolExecutor: (suspend (name: String, args: JSONObject) -> JSONObject)?
    ): GeminiTextChatResult {
        val contents = JSONArray()
        // Include up to 8 recent completed turns for natural multi-turn context
        recentTurns
            .filter {
                it.userTranscript.isNotBlank() &&
                    it.jarvisResponse.isNotBlank() &&
                    it.jarvisResponse != "..." &&
                    !it.jarvisResponse.startsWith("(Audio")
            }
            .takeLast(8)
            .forEach { turn ->
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", turn.userTranscript)))
                })
                contents.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().put(JSONObject().put("text", turn.jarvisResponse)))
                })
            }

        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
        })

        val executedTools = mutableListOf<String>()

        for (round in 0..MAX_TOOL_ROUNDS) {
            val payload = JSONObject().apply {
                if (systemPrompt.isNotBlank()) {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    })
                }
                put("contents", contents)
                if (toolDeclarations.length() > 0) {
                    put("tools", JSONArray().put(JSONObject().apply {
                        put("function_declarations", toolDeclarations)
                    }))
                }
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.65)
                })
            }

            val url = "${baseUrl.trimEnd('/')}/models/$modelId:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val (httpCode, bodyText) = try {
                httpClient.newCall(request).execute().use { resp ->
                    resp.code to (resp.body?.string().orEmpty())
                }
            } catch (_: SocketTimeoutException) {
                return GeminiTextChatResult(
                    success = false,
                    error = GeminiTextChatError.TIMEOUT,
                    errorMessage = "Request timed out while contacting Gemini. Please try again."
                )
            } catch (e: IOException) {
                return GeminiTextChatResult(
                    success = false,
                    error = GeminiTextChatError.NETWORK_ERROR,
                    errorMessage = "Network error while contacting Gemini: ${e.message ?: "connection failed"}"
                )
            }

            if (httpCode !in 200..299) {
                return classifyHttpError(httpCode, bodyText)
            }

            val responseJson = try {
                JSONObject(bodyText)
            } catch (e: Exception) {
                Log.e(TAG, "Malformed JSON from generateContent", e)
                return GeminiTextChatResult(
                    success = false,
                    error = GeminiTextChatError.UNKNOWN,
                    errorMessage = "Received an invalid response format from Gemini."
                )
            }

            val candidate = responseJson.optJSONArray("candidates")?.optJSONObject(0)
            val candidateContent = candidate?.optJSONObject("content")
            val parts = candidateContent?.optJSONArray("parts")
                ?: return GeminiTextChatResult(
                    success = false,
                    error = GeminiTextChatError.UNKNOWN,
                    errorMessage = "No response content returned by Gemini."
                )

            val functionCalls = mutableListOf<Pair<String, JSONObject>>()
            val textBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val fnCall = part.optJSONObject("functionCall")
                if (fnCall != null) {
                    val name = fnCall.optString("name", "").trim()
                    val args = fnCall.optJSONObject("args") ?: JSONObject()
                    if (name.isNotBlank()) {
                        functionCalls += name to args
                    }
                } else {
                    val t = part.optString("text", "")
                    if (t.isNotBlank()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(t.trim())
                    }
                }
            }

            if (functionCalls.isEmpty() || toolExecutor == null || round == MAX_TOOL_ROUNDS) {
                val finalText = textBuilder.toString().trim()
                if (finalText.isBlank()) {
                    return GeminiTextChatResult(
                        success = false,
                        error = GeminiTextChatError.UNKNOWN,
                        errorMessage = "Gemini completed without a text message."
                    )
                }
                return GeminiTextChatResult(
                    success = true,
                    replyText = finalText,
                    error = GeminiTextChatError.NONE,
                    executedTools = executedTools.toList()
                )
            }

            // Append model functionCall message, execute each tool, then append functionResponse turn
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", parts)
            })

            val responseParts = JSONArray()
            for ((fnName, fnArgs) in functionCalls) {
                executedTools += fnName
                val fnOutput = try {
                    toolExecutor(fnName, fnArgs)
                } catch (e: Exception) {
                    JSONObject().apply {
                        put("success", false)
                        put("message", e.message ?: "Tool execution failed")
                    }
                }
                responseParts.put(JSONObject().apply {
                    put("functionResponse", JSONObject().apply {
                        put("name", fnName)
                        put("response", fnOutput)
                    })
                })
            }

            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", responseParts)
            })
        }

        return GeminiTextChatResult(
            success = false,
            error = GeminiTextChatError.UNKNOWN,
            errorMessage = "Tool execution exceeded maximum steps."
        )
    }

    private fun classifyHttpError(httpCode: Int, bodyText: String): GeminiTextChatResult {
        val errMsg = try {
            JSONObject(bodyText).optJSONObject("error")?.optString("message", "").orEmpty()
        } catch (_: Exception) {
            ""
        }.ifBlank { bodyText.take(240) }

        val lower = errMsg.lowercase()
        val isApiKey = httpCode == 401 || httpCode == 403 ||
            lower.contains("api_key_invalid") ||
            lower.contains("api key not valid") ||
            lower.contains("permission_denied")
        if (isApiKey) {
            return GeminiTextChatResult(
                success = false,
                error = GeminiTextChatError.API_KEY_INVALID,
                errorMessage = "Invalid Gemini API Key. Please check your key in Settings."
            )
        }
        if (httpCode == 429 || lower.contains("resource_exhausted") || lower.contains("quota")) {
            return GeminiTextChatResult(
                success = false,
                error = GeminiTextChatError.QUOTA_EXCEEDED,
                errorMessage = "Gemini API quota or rate limit exceeded. Please try again shortly."
            )
        }
        if (httpCode == 404 || lower.contains("not found") || lower.contains("not supported")) {
            return GeminiTextChatResult(
                success = false,
                error = GeminiTextChatError.MODEL_ERROR,
                errorMessage = errMsg.ifBlank { "Model unavailable ($httpCode)" }
            )
        }
        return GeminiTextChatResult(
            success = false,
            error = GeminiTextChatError.UNKNOWN,
            errorMessage = errMsg.ifBlank { "Gemini error (HTTP $httpCode)" }
        )
    }
}
