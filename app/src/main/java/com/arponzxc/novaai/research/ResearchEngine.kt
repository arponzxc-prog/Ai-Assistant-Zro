package com.arponzxc.novaai.research

import android.content.Context
import com.arponzxc.novaai.network.apimanager.MultiProviderRouter
import com.arponzxc.novaai.network.apimanager.UnifiedAiTextRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class ResearchEngine(
    private val context: Context,
    private val providers: List<SearchProvider> = defaultProviders(context = context),
    private val aiSynthesizer: suspend (Context, String, ResearchMode, List<SourceItem>) -> Pair<Boolean, String> = ::defaultAiSynthesize
) {
    companion object {
        private const val PREF_NAME = "nova_research_history"
        private const val KEY_HISTORY = "history_entries_json"
        private const val MAX_HISTORY = 25

        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Free knowledge providers, plus a live web-search provider (Tavily / Serper / Exa)
         * placed first when the user has saved a key for it in Settings.
         */
        fun defaultProviders(
            client: OkHttpClient = sharedHttpClient,
            context: Context? = null
        ): List<SearchProvider> {
            val base = listOf(
                WikipediaSearchProvider(client),
                DuckDuckGoInstantSearchProvider(client),
                WikidataSearchProvider(client)
            )
            val webProvider = runCatching {
                val prefs = context?.let { com.arponzxc.novaai.JarvisApp.instance.webSearchPreferences }
                    ?: return@runCatching null
                val id = prefs.activeProviderIdOrNull(WebSearchProviderType.allIds) ?: return@runCatching null
                val type = WebSearchProviderType.fromId(id) ?: return@runCatching null
                ApiWebSearchProvider(client, type, prefs.getKey(id))
            }.getOrNull()
            return if (webProvider != null) listOf<SearchProvider>(webProvider) + base else base
        }

        fun scoreCredibility(url: String, providerName: String): SourceCredibility {
            val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
            return when {
                host.endsWith(".edu") || host.endsWith(".gov") || host.contains("wikipedia.org") ||
                    host.contains("wikidata.org") || host.contains("arxiv.org") ||
                    host.contains("developer.android.com") || host.contains("github.com") -> SourceCredibility.HIGH
                providerName.contains("Wikipedia", ignoreCase = true) ||
                    providerName.contains("Wikidata", ignoreCase = true) ||
                    providerName.contains("DuckDuckGo", ignoreCase = true) -> SourceCredibility.MEDIUM
                else -> SourceCredibility.LOW
            }
        }

        fun extractDomain(url: String): String {
            return runCatching { URI(url).host?.removePrefix("www.").orEmpty() }
                .getOrDefault("")
        }

        fun deduplicateAndRankSources(raw: List<SourceItem>): List<SourceItem> {
            val seenUrls = mutableSetOf<String>()
            val seenTitles = mutableSetOf<String>()
            val deduped = mutableListOf<SourceItem>()
            for (item in raw) {
                val normUrl = item.url.trim().trimEnd('/').lowercase(Locale.ROOT)
                val normTitle = item.title.trim().lowercase(Locale.ROOT)
                if (normUrl.isBlank() || normTitle.isBlank()) continue
                if (normUrl in seenUrls || normTitle in seenTitles) continue
                seenUrls.add(normUrl)
                seenTitles.add(normTitle)
                val cred = if (item.domain.isNotBlank()) item.credibility else scoreCredibility(item.url, item.providerName)
                deduped.add(
                    item.copy(
                        domain = item.domain.ifBlank { extractDomain(item.url) },
                        credibility = cred
                    )
                )
            }
            return deduped.sortedByDescending { it.credibility.rankWeight }
        }

        private suspend fun defaultAiSynthesize(
            context: Context,
            query: String,
            mode: ResearchMode,
            sources: List<SourceItem>
        ): Pair<Boolean, String> {
            val sourceContext = sources.take(12).mapIndexed { index, src ->
                "[${index + 1}] (${src.credibility.label} • ${src.providerName}) ${src.title}\nURL: ${src.url}\nExcerpt: ${src.snippet}"
            }.joinToString("\n\n")

            val response = MultiProviderRouter.generateText(
                context = context,
                request = UnifiedAiTextRequest(
                    systemPrompt = "You are NOVA AI Research Analyst. Synthesize findings strictly from the verified sources provided. Cite source numbers like [1], [2]. Mention agreements or gaps honestly. Do NOT invent URLs or facts outside the snippets.",
                    userMessage = "Topic: $query\nMode: ${mode.label} (${mode.synthesisGuidance})\n\nVerified Sources:\n$sourceContext",
                    temperature = 0.3
                )
            )
            return if (response.success && response.text.isNotBlank()) {
                true to "${response.text}\n\n— Synthesized via ${response.providerName} (${response.modelUsed})"
            } else {
                false to response.errorMessage.ifBlank { "AI synthesis unavailable." }
            }
        }
    }

    private val gson = Gson()
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    suspend fun performResearch(
        query: String,
        mode: String = ResearchMode.DETAILED.label,
        saveHistory: Boolean = true
    ): ResearchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val resolvedMode = ResearchMode.entries.firstOrNull {
            it.label.equals(mode.trim(), ignoreCase = true)
        } ?: ResearchMode.DETAILED

        if (cleanQuery.isBlank()) {
            return@withContext ResearchResult(
                query = "",
                summary = "",
                sources = emptyList(),
                mode = resolvedMode.label,
                success = false,
                errorMessage = "Please enter a research topic or question."
            )
        }

        val outputs = coroutineScope {
            providers.map { provider ->
                async {
                    runCatching { provider.search(cleanQuery, limit = 5) }
                        .getOrElse { ex ->
                            ResearchProviderOutput(
                                providerName = provider.providerName,
                                success = false,
                                errorMessage = ex.message ?: "Provider failed"
                            )
                        }
                }
            }.awaitAll()
        }

        val diagnostics = outputs.map { out ->
            if (out.success) {
                "${out.providerName}: ${out.items.size} result(s)"
            } else {
                "${out.providerName}: Failed (${out.errorMessage ?: "Unavailable"})"
            }
        }

        val mergedSources = deduplicateAndRankSources(outputs.flatMap { it.items })
        val anyProviderSucceeded = outputs.any { it.success }

        if (mergedSources.isEmpty()) {
            val errMsg = if (!anyProviderSucceeded) {
                "Unable to retrieve live sources (offline or all search providers unreachable). No synthetic or fake sources were generated."
            } else {
                "Search providers responded, but no matching verified sources were found for \"$cleanQuery\"."
            }
            return@withContext ResearchResult(
                query = cleanQuery,
                summary = "",
                sources = emptyList(),
                mode = resolvedMode.label,
                success = false,
                errorMessage = errMsg,
                providerDiagnostics = diagnostics
            )
        }

        val (aiOk, aiText) = aiSynthesizer(context, cleanQuery, resolvedMode, mergedSources)
        val summary = if (aiOk) {
            aiText
        } else {
            buildString {
                append("AI Synthesis Unavailable ($aiText)\n")
                append("Showing ${mergedSources.size} verified source excerpts for \"$cleanQuery\":\n\n")
                mergedSources.take(5).forEachIndexed { idx, src ->
                    append("${idx + 1}. ${src.title} [${src.credibility.label}]\n   ${src.snippet}\n")
                }
            }.trim()
        }

        val finalResult = ResearchResult(
            query = cleanQuery,
            summary = summary,
            sources = mergedSources,
            mode = resolvedMode.label,
            success = true,
            aiSynthesized = aiOk,
            providerDiagnostics = diagnostics
        )

        if (saveHistory) {
            saveHistoryEntry(finalResult)
        }
        finalResult
    }

    fun getHistory(): List<ResearchResult> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ResearchResult>>() {}.type
            gson.fromJson<List<ResearchResult>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun saveHistoryEntry(result: ResearchResult) {
        if (!result.success || result.query.isBlank()) return
        val existing = getHistory().filterNot {
            it.query.equals(result.query, ignoreCase = true) && it.mode == result.mode
        }.toMutableList()
        existing.add(0, result)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(existing.take(MAX_HISTORY))).apply()
    }

    fun deleteHistoryEntry(timestamp: Long) {
        val updated = getHistory().filterNot { it.timestamp == timestamp }
        prefs.edit().putString(KEY_HISTORY, gson.toJson(updated)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
}

class WikipediaSearchProvider(private val client: OkHttpClient) : SearchProvider {
    override val providerName: String = "Wikipedia"

    override suspend fun search(query: String, limit: Int): ResearchProviderOutput {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=$limit&format=json"
        return executeGet(client, url, providerName) { body ->
            val items = mutableListOf<SourceItem>()
            val arr = JSONObject(body).optJSONObject("query")?.optJSONArray("search")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim()
                    val snippet = obj.optString("snippet").replace(Regex("<.*?>"), "").trim()
                    val pageId = obj.optString("pageid").trim()
                    if (title.isNotBlank() && pageId.isNotBlank()) {
                        val pageUrl = "https://en.wikipedia.org/?curid=$pageId"
                        items.add(
                            SourceItem(
                                title = title,
                                url = pageUrl,
                                snippet = snippet.ifBlank { "Wikipedia reference entry for $title." },
                                providerName = providerName,
                                domain = "en.wikipedia.org",
                                credibility = SourceCredibility.HIGH
                            )
                        )
                    }
                }
            }
            items
        }
    }
}

class DuckDuckGoInstantSearchProvider(private val client: OkHttpClient) : SearchProvider {
    override val providerName: String = "DuckDuckGo Knowledge"

    override suspend fun search(query: String, limit: Int): ResearchProviderOutput {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        return executeGet(client, url, providerName) { body ->
            val items = mutableListOf<SourceItem>()
            val root = JSONObject(body)
            val absText = root.optString("AbstractText").trim()
            val absUrl = root.optString("AbstractURL").trim()
            val heading = root.optString("Heading").trim().ifBlank { query }
            if (absText.isNotBlank() && absUrl.isNotBlank()) {
                items.add(
                    SourceItem(
                        title = heading,
                        url = absUrl,
                        snippet = absText,
                        providerName = providerName,
                        domain = ResearchEngine.extractDomain(absUrl),
                        credibility = ResearchEngine.scoreCredibility(absUrl, providerName)
                    )
                )
            }
            val related = root.optJSONArray("RelatedTopics")
            if (related != null) {
                for (i in 0 until related.length()) {
                    if (items.size >= limit) break
                    val topic = related.optJSONObject(i) ?: continue
                    val text = topic.optString("Text").trim()
                    val firstUrl = topic.optString("FirstURL").trim()
                    if (text.isNotBlank() && firstUrl.isNotBlank()) {
                        items.add(
                            SourceItem(
                                title = text.substringBefore(" - ").take(80),
                                url = firstUrl,
                                snippet = text,
                                providerName = providerName,
                                domain = ResearchEngine.extractDomain(firstUrl),
                                credibility = ResearchEngine.scoreCredibility(firstUrl, providerName)
                            )
                        )
                    }
                }
            }
            items
        }
    }
}

class WikidataSearchProvider(private val client: OkHttpClient) : SearchProvider {
    override val providerName: String = "Wikidata"

    override suspend fun search(query: String, limit: Int): ResearchProviderOutput {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$encoded&language=en&limit=$limit&format=json"
        return executeGet(client, url, providerName) { body ->
            val items = mutableListOf<SourceItem>()
            val arr = JSONObject(body).optJSONArray("search")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val label = obj.optString("label").trim()
                    val desc = obj.optString("description").trim()
                    val conceptUri = obj.optString("concepturi").trim().ifBlank { obj.optString("url").trim() }
                    val fullUrl = if (conceptUri.startsWith("//")) "https:$conceptUri" else conceptUri
                    if (label.isNotBlank() && fullUrl.isNotBlank()) {
                        items.add(
                            SourceItem(
                                title = label,
                                url = fullUrl,
                                snippet = desc.ifBlank { "Structured knowledge graph entity for $label." },
                                providerName = providerName,
                                domain = "www.wikidata.org",
                                credibility = SourceCredibility.HIGH
                            )
                        )
                    }
                }
            }
            items
        }
    }
}

private fun executeGet(
    client: OkHttpClient,
    url: String,
    providerName: String,
    parser: (String) -> List<SourceItem>
): ResearchProviderOutput {
    return try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NOVA-AI-Research/2.1")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ResearchProviderOutput(
                    providerName = providerName,
                    success = false,
                    errorMessage = "HTTP ${resp.code}"
                )
            }
            val body = resp.body?.string().orEmpty()
            val items = parser(body)
            ResearchProviderOutput(
                providerName = providerName,
                success = true,
                items = items
            )
        }
    } catch (e: Exception) {
        ResearchProviderOutput(
            providerName = providerName,
            success = false,
            errorMessage = e.message ?: "Network error"
        )
    }
}
