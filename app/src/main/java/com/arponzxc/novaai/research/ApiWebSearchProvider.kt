package com.arponzxc.novaai.research

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Free web-search APIs the user can plug in from Settings -> API & Connection.
 * Free-tier details change often, so [freeTierNote] is only a hint shown in the UI.
 */
enum class WebSearchProviderType(
    val id: String,
    val label: String,
    val signupUrl: String,
    val keyHint: String,
    val freeTierNote: String
) {
    TAVILY(
        id = "tavily",
        label = "Tavily",
        signupUrl = "https://app.tavily.com",
        keyHint = "Tavily API Key (tvly-...)",
        freeTierNote = "tavily.com • about 1,000 free searches per month • no card needed • made for AI assistants"
    ),
    SERPER(
        id = "serper",
        label = "Serper",
        signupUrl = "https://serper.dev",
        keyHint = "Serper API Key",
        freeTierNote = "serper.dev • about 2,500 free Google searches on signup"
    ),
    EXA(
        id = "exa",
        label = "Exa",
        signupUrl = "https://dashboard.exa.ai/api-keys",
        keyHint = "Exa API Key",
        freeTierNote = "exa.ai • free monthly credit • no card needed"
    );

    companion object {
        fun fromId(id: String): WebSearchProviderType? = entries.firstOrNull { it.id == id }
        val allIds: List<String> get() = entries.map { it.id }
    }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/** Live web search through one of the [WebSearchProviderType] APIs. */
class ApiWebSearchProvider(
    private val client: OkHttpClient,
    private val type: WebSearchProviderType,
    private val apiKey: String
) : SearchProvider {

    override val providerName: String = "${type.label} Web Search"

    override suspend fun search(query: String, limit: Int): ResearchProviderOutput {
        return try {
            val request = buildRequest(query, limit)
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val reason = when (resp.code) {
                        401, 403 -> "invalid or unauthorized API key (HTTP ${resp.code})"
                        429 -> "rate limit or free quota reached (HTTP 429)"
                        else -> "HTTP ${resp.code}"
                    }
                    return ResearchProviderOutput(providerName, false, errorMessage = reason)
                }
                ResearchProviderOutput(providerName, true, items = parseItems(body, limit))
            }
        } catch (e: Exception) {
            ResearchProviderOutput(providerName, false, errorMessage = e.message ?: "Network error")
        }
    }

    private fun buildRequest(query: String, limit: Int): Request {
        val builder = Request.Builder().header("User-Agent", "NOVA-AI-Research/2.2")
        return when (type) {
            WebSearchProviderType.TAVILY -> builder
                .url("https://api.tavily.com/search")
                .header("Authorization", "Bearer $apiKey")
                .post(
                    JSONObject()
                        .put("query", query)
                        .put("max_results", limit)
                        .put("search_depth", "basic")
                        .toString().toRequestBody(JSON_MEDIA)
                ).build()

            WebSearchProviderType.SERPER -> builder
                .url("https://google.serper.dev/search")
                .header("X-API-KEY", apiKey)
                .post(
                    JSONObject().put("q", query).put("num", limit)
                        .toString().toRequestBody(JSON_MEDIA)
                ).build()

            WebSearchProviderType.EXA -> builder
                .url("https://api.exa.ai/search")
                .header("x-api-key", apiKey)
                .post(
                    JSONObject()
                        .put("query", query)
                        .put("numResults", limit)
                        .put("contents", JSONObject().put("text", JSONObject().put("maxCharacters", 600)))
                        .toString().toRequestBody(JSON_MEDIA)
                ).build()
        }
    }

    private fun parseItems(body: String, limit: Int): List<SourceItem> {
        val root = JSONObject(body)
        val items = mutableListOf<SourceItem>()
        fun add(title: String, url: String, snippet: String) {
            if (title.isBlank() || url.isBlank() || items.size >= limit) return
            val base = ResearchEngine.scoreCredibility(url, providerName)
            // A real web-search hit is at least "Verified Web" so it is not cut from the AI context.
            val cred = if (base == SourceCredibility.HIGH) SourceCredibility.HIGH else SourceCredibility.MEDIUM
            items += SourceItem(
                title = title.trim().take(120),
                url = url.trim(),
                snippet = snippet.trim().replace(Regex("\\s+"), " ").take(600)
                    .ifBlank { "Web result for \"$title\"." },
                providerName = providerName,
                domain = ResearchEngine.extractDomain(url),
                credibility = cred
            )
        }
        when (type) {
            WebSearchProviderType.TAVILY -> root.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("title"), o.optString("url"), o.optString("content"))
                }
            }
            WebSearchProviderType.SERPER -> {
                val answer = root.optJSONObject("answerBox")
                if (answer != null) {
                    val text = answer.optString("answer").ifBlank { answer.optString("snippet") }
                    add(answer.optString("title").ifBlank { "Direct answer" }, answer.optString("link"), text)
                }
                root.optJSONArray("organic")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(o.optString("title"), o.optString("link"), o.optString("snippet"))
                    }
                }
            }
            WebSearchProviderType.EXA -> root.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(o.optString("title"), o.optString("url"), o.optString("text"))
                }
            }
        }
        return items
    }

    companion object {
        /** Runs one tiny search so the Settings screen can tell the user if the key really works. */
        suspend fun testKey(client: OkHttpClient, type: WebSearchProviderType, apiKey: String): Pair<Boolean, String> {
            val out = ApiWebSearchProvider(client, type, apiKey).search("nova ai test", 1)
            return if (out.success) true to "${type.label} key works (${out.items.size} result)"
            else false to (out.errorMessage ?: "Search failed")
        }
    }
}
