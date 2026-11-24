package com.arponzxc.novaai.automation.whatsapp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.arponzxc.novaai.automation.whatsapp.db.WhatsAppConversationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiWhatsAppReplyEngine(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gemini-1.5-flash" },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "GeminiWhatsAppBrain"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    private val gson = Gson()

    /**
     * Generates a context-aware automated WhatsApp reply.
     */
    suspend fun generateReply(
        senderName: String,
        incomingMessage: String,
        recentHistory: List<WhatsAppConversationEntity> = emptyList(),
        customInstruction: String? = null,
        operatorName: String = "Arpon"
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            Log.w(TAG, "Gemini API key is missing. Using smart offline fallback.")
            return@withContext Result.success(generateOfflineFallback(incomingMessage, operatorName))
        }

        try {
            val systemInstruction = buildSystemPrompt(operatorName, customInstruction)
            val conversationContext = buildConversationContext(senderName, incomingMessage, recentHistory)

            val fullPrompt = "$systemInstruction\n\n$conversationContext\n\n[INCOMING MESSAGE FROM $senderName]: \"$incomingMessage\"\nReply:"

            val payload = JsonObject().apply {
                val contentsArray = com.google.gson.JsonArray().apply {
                    val contentObj = JsonObject().apply {
                        addProperty("role", "user")
                        val partsArray = com.google.gson.JsonArray().apply {
                            val partObj = JsonObject().apply {
                                addProperty("text", fullPrompt)
                            }
                            add(partObj)
                        }
                        add("parts", partsArray)
                    }
                    add(contentObj)
                }
                add("contents", contentsArray)

                val genConfig = JsonObject().apply {
                    addProperty("temperature", 0.7)
                    addProperty("maxOutputTokens", 120)
                }
                add("generationConfig", genConfig)
            }

            val candidateModels = resolveTextModels(modelProvider())
            val bodyJson = payload.toString()

            for (targetModel in candidateModels) {
                val endpoint = "$BASE_URL$targetModel:generateContent?key=$apiKey"
                val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string().orEmpty()
                        Log.w(TAG, "Gemini model $targetModel failed (${response.code}): $errBody")
                        return@use
                    }

                    val resJson = response.body?.string() ?: ""
                    val rawReply = extractTextFromGeminiResponse(resJson)
                    val sanitized = WhatsAppSafetyFilter.sanitizeOutgoingReply(cleanReplyText(rawReply))

                    if (sanitized.isNotBlank()) {
                        return@withContext Result.success(sanitized)
                    }
                }
            }

            Result.success(generateOfflineFallback(incomingMessage, operatorName))
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini WhatsApp reply generation: ${e.message}", e)
            Result.success(generateOfflineFallback(incomingMessage, operatorName))
        }
    }

    private fun resolveTextModels(requestedModel: String): List<String> {
        val clean = requestedModel.removePrefix("models/").trim()
        val candidates = mutableListOf<String>()
        if (clean.isNotBlank() &&
            !clean.contains("live", ignoreCase = true) &&
            !clean.contains("agent", ignoreCase = true)
        ) {
            candidates.add(clean)
        }
        candidates.addAll(listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"))
        return candidates.distinct()
    }

    private fun buildSystemPrompt(operatorName: String, customInstruction: String?): String {
        val basePrompt = """
            You are NOVA AI, an intelligent personal assistant managing WhatsApp replies on behalf of your operator.
            Your operator's name is $operatorName.
            
            Strict rules for automated replies:
            1. Language Matching: Automatically respond in the exact same language or dialect as the incoming message (e.g., Bengali, English, Hindi, Hinglish, etc.). If the incoming message is in Bengali, your reply MUST be in natural Bengali. If Hinglish, reply in Hinglish.
            2. Context & Tone: Understand the intent, emotional nuance, and relationship context. Reply warmly, politely, and naturally as the operator or on behalf of the operator.
            3. Brevity & Naturalness: Keep replies natural, concise, and WhatsApp-appropriate (1 to 2 short sentences). Do not use bullet points or sound like a robot.
            4. Safety & Privacy: NEVER disclose passwords, OTPs, PINs, bank details, API keys, or sensitive credentials.
            5. Unknown Details: If the message asks a specific personal question you don't know (e.g. 'Where are you right now?', 'Can we meet?'), reply politely that $operatorName is currently occupied and will reply shortly.
            6. Output format: Return ONLY the raw reply message text. Do NOT wrap in quotes or add preamble like 'Here is your reply:'.
        """.trimIndent()

        return if (!customInstruction.isNullOrBlank()) {
            "$basePrompt\n\nSPECIAL SENDER INSTRUCTION:\n$customInstruction"
        } else {
            basePrompt
        }
    }

    private fun buildConversationContext(
        senderName: String,
        incomingMessage: String,
        recentHistory: List<WhatsAppConversationEntity>
    ): String {
        if (recentHistory.isEmpty()) return ""

        val sb = StringBuilder("RECENT CHAT HISTORY WITH $senderName:\n")
        val ordered = recentHistory.take(6).reversed()
        for (item in ordered) {
            val speaker = if (item.isIncoming) senderName else "NOVA AI"
            sb.append("- ").append(speaker).append(": ").append(item.messageText).append("\n")
        }
        return sb.toString()
    }

    private fun extractTextFromGeminiResponse(json: String): String {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val candidates = root.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val firstCandidate = candidates.get(0).asJsonObject
                val content = firstCandidate.getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    return parts.get(0).asJsonObject.get("text").asString
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini response: ${e.message}")
            ""
        }
    }

    private fun cleanReplyText(text: String): String {
        var clean = text.trim()
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length >= 2) {
            clean = clean.substring(1, clean.length - 1).trim()
        }
        if (clean.startsWith("NOVA AI:") || clean.startsWith("Reply:")) {
            clean = clean.substringAfter(":").trim()
        }
        return clean
    }

    /**
     * Provides a respectful, natural fallback when internet or API is unavailable,
     * maintaining linguistic congruence.
     */
    fun generateOfflineFallback(incomingMessage: String, operatorName: String): String {
        val lower = incomingMessage.lowercase()
        val isBengali = incomingMessage.any { it in '\u0980'..'\u09FF' }
        val isHindiScript = incomingMessage.any { it in '\u0900'..'\u097F' }
        val isHinglish = lower.contains("kaise") || lower.contains("kya") || lower.contains("bhai") || lower.contains("haan") || lower.contains("aap")

        return when {
            isBengali -> {
                when {
                    lower.contains("কেমন") || lower.contains("ভালো") -> "ভালো আছি। $operatorName এখন ব্যস্ত আছেন, কিছুক্ষণের মধ্যে কথা বলবেন।"
                    lower.contains("কোথায়") -> "$operatorName এখন বাইরে আছেন, দ্রুত উত্তর দেবেন।"
                    else -> "নমস্কার/সালাম, বার্তাটি পেয়েছি। $operatorName একটু ব্যস্ত আছেন, দ্রুত যোগাযোগ করবেন।"
                }
            }
            isHindiScript || isHinglish -> {
                when {
                    lower.contains("kaise") || lower.contains("kya") || lower.contains("bhai") -> "Sab theek hai. $operatorName abhi thode busy hain, jaldi reply karenge."
                    else -> "Message mil gaya hai. $operatorName thodi der mein aapse contact karenge."
                }
            }
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") -> {
                "Hello! $operatorName is currently occupied and will get back to you shortly."
            }
            lower.contains("urgent") || lower.contains("emergency") -> {
                "I have noted your message. I will alert $operatorName right away."
            }
            else -> {
                "Thank you for your message. $operatorName is currently away and will reply as soon as possible."
            }
        }
    }
}
