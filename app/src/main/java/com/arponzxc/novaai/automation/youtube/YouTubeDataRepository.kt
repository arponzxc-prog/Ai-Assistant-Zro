package com.arponzxc.novaai.automation.youtube

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class YouTubeVideoItem(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val description: String,
    val thumbnailUrl: String,
    val publishedAt: String,
    val viewCount: Long = 0L,
    val likeCount: Long = 0L,
    val commentCount: Long = 0L,
    val duration: String = ""
)

data class YouTubeChannelItem(
    val channelId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val subscriberCount: Long = -1L,
    val videoCount: Long = 0L,
    val viewCount: Long = 0L,
    val publishedAt: String = ""
)

data class YouTubeCategoryItem(
    val categoryId: String,
    val title: String
)

enum class YouTubeErrorCode {
    NONE,
    API_KEY_MISSING,
    API_KEY_INVALID,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    TIMEOUT,
    NOT_FOUND,
    HTTP_ERROR
}

data class YouTubeResult<T>(
    val success: Boolean,
    val data: T? = null,
    val errorCode: YouTubeErrorCode = YouTubeErrorCode.NONE,
    val errorMessage: String = "",
    val fromCache: Boolean = false
)

sealed class ChannelIdentifier {
    data class Id(val id: String) : ChannelIdentifier()
    data class Handle(val handle: String) : ChannelIdentifier()
    data class Query(val query: String) : ChannelIdentifier()
}

object YouTubeDataRepository {
    private const val TAG = "YouTubeDataRepo"
    private const val DEFAULT_BASE_URL = "https://www.googleapis.com/youtube/v3"
    private const val CACHE_TTL_STANDARD_MS = 3 * 60 * 1000L
    private const val CACHE_TTL_CATEGORY_MS = 30 * 60 * 1000L

    internal var baseUrl: String = DEFAULT_BASE_URL
    internal var httpClientOverride: OkHttpClient? = null
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    private data class CacheEntry(val payload: Any, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val defaultHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun client(): OkHttpClient = httpClientOverride ?: defaultHttpClient

    fun clearCache() {
        cache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCached(key: String): T? {
        val entry = cache[key] ?: return null
        if (clockMs() > entry.expiresAtMs) {
            cache.remove(key)
            return null
        }
        return entry.payload as? T
    }

    private fun putCached(key: String, value: Any, ttlMs: Long = CACHE_TTL_STANDARD_MS) {
        if (cache.size > 120) {
            val now = clockMs()
            cache.entries.removeIf { it.value.expiresAtMs < now }
            if (cache.size > 120) cache.clear()
        }
        cache[key] = CacheEntry(value, clockMs() + ttlMs)
    }

    private fun safeKeyFingerprint(apiKey: String): String =
        apiKey.trim().hashCode().toString(16)

    private fun executeGet(urlWithoutKey: String, apiKey: String): Result<String> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return Result.failure(YouTubeApiException(YouTubeErrorCode.API_KEY_MISSING, "YouTube Data API key is missing."))
        }
        val sep = if (urlWithoutKey.contains("?")) "&" else "?"
        val fullUrl = "$urlWithoutKey${sep}key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        return try {
            val req = Request.Builder().url(fullUrl).get().build()
            client().newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Result.success(body)
                } else {
                    val (errCode, errMsg) = classifyHttpError(resp.code, body)
                    Result.failure(YouTubeApiException(errCode, errMsg))
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "YouTube API request timed out")
            Result.failure(YouTubeApiException(YouTubeErrorCode.TIMEOUT, "YouTube API request timed out. Please check internet speed and try again."))
        } catch (e: IOException) {
            Log.e(TAG, "YouTube API network error: ${e.javaClass.simpleName}")
            Result.failure(YouTubeApiException(YouTubeErrorCode.NETWORK_ERROR, "Network connection error while contacting YouTube API."))
        } catch (e: Exception) {
            Log.e(TAG, "YouTube API unexpected error: ${e.javaClass.simpleName}")
            Result.failure(YouTubeApiException(YouTubeErrorCode.HTTP_ERROR, "Unexpected error while querying YouTube API."))
        }
    }

    internal fun classifyHttpError(httpCode: Int, responseBody: String): Pair<YouTubeErrorCode, String> {
        val lower = responseBody.lowercase(Locale.ROOT)
        val parsedMsg = try {
            JSONObject(responseBody).optJSONObject("error")?.optString("message", "").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val isQuota = httpCode == 429 ||
            lower.contains("quotaexceeded") ||
            lower.contains("dailylimitexceeded") ||
            lower.contains("ratelimitexceeded") ||
            lower.contains("quota")
        if (isQuota) {
            return YouTubeErrorCode.QUOTA_EXCEEDED to "YouTube API daily quota limit exceeded. Please try again later or update API quota."
        }
        val isInvalidKey = httpCode == 400 || httpCode == 401 ||
            (httpCode == 403 && (lower.contains("keyinvalid") || lower.contains("api_key_invalid") || lower.contains("accessnotconfigured") || lower.contains("iprefererblocked"))) ||
            lower.contains("api key not valid") ||
            lower.contains("keyinvalid")
        if (isInvalidKey) {
            return YouTubeErrorCode.API_KEY_INVALID to "YouTube Data API key is invalid or unauthorized. Please verify the key in API Connection settings."
        }
        if (httpCode == 404) {
            return YouTubeErrorCode.NOT_FOUND to "Requested YouTube resource was not found."
        }
        val safeDetail = parsedMsg.takeIf { it.isNotBlank() && !it.contains("key=", ignoreCase = true) }
            ?: "YouTube API returned HTTP $httpCode."
        return YouTubeErrorCode.HTTP_ERROR to safeDetail
    }

    private class YouTubeApiException(val code: YouTubeErrorCode, override val message: String) : Exception(message)

    private fun <T> failureFrom(throwable: Throwable?): YouTubeResult<T> {
        val ex = throwable as? YouTubeApiException
        return if (ex != null) {
            YouTubeResult(success = false, data = null, errorCode = ex.code, errorMessage = ex.message)
        } else {
            YouTubeResult(
                success = false,
                data = null,
                errorCode = YouTubeErrorCode.NETWORK_ERROR,
                errorMessage = "Unable to complete YouTube request."
            )
        }
    }

    fun testConnectionDetailed(apiKey: String): YouTubeResult<Boolean> {
        if (apiKey.trim().isBlank()) {
            return YouTubeResult(false, false, YouTubeErrorCode.API_KEY_MISSING, "YouTube API key is blank.")
        }
        val url = "$baseUrl/videos?part=id&chart=mostPopular&regionCode=BD&maxResults=1"
        val res = executeGet(url, apiKey)
        return if (res.isSuccess) {
            YouTubeResult(true, true)
        } else {
            val f = failureFrom<Boolean>(res.exceptionOrNull())
            f.copy(data = false)
        }
    }

    fun testConnection(apiKey: String): Boolean = testConnectionDetailed(apiKey).success

    fun searchVideosWithResult(
        apiKey: String,
        query: String,
        maxResults: Int = 5,
        order: String = "relevance"
    ): YouTubeResult<List<YouTubeVideoItem>> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return YouTubeResult(false, emptyList(), YouTubeErrorCode.NOT_FOUND, "Search query is empty.")
        }
        val clampedMax = maxResults.coerceIn(1, 25)
        val cleanOrder = order.trim().lowercase(Locale.ROOT).takeIf {
            it in setOf("relevance", "date", "rating", "viewcount", "title")
        }?.let {
            if (it == "viewcount") "viewCount" else it
        } ?: "relevance"

        val cacheKey = "search:${safeKeyFingerprint(apiKey)}:${clean.lowercase(Locale.ROOT)}:$clampedMax:$cleanOrder"
        getCached<List<YouTubeVideoItem>>(cacheKey)?.let {
            return YouTubeResult(true, it, fromCache = true)
        }

        val encodedQ = URLEncoder.encode(clean, "UTF-8")
        val searchUrl = "$baseUrl/search?part=snippet&type=video&maxResults=$clampedMax&order=$cleanOrder&q=$encodedQ"
        val searchResp = executeGet(searchUrl, apiKey)
        if (searchResp.isFailure) return failureFrom(searchResp.exceptionOrNull())

        return try {
            val json = JSONObject(searchResp.getOrThrow())
            val items = json.optJSONArray("items") ?: JSONArray()
            val videoIds = mutableListOf<String>()
            val fallbackList = mutableListOf<YouTubeVideoItem>()

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val videoId = item.optJSONObject("id")?.optString("videoId").orEmpty()
                if (videoId.isBlank()) continue
                videoIds.add(videoId)
                val snippet = item.optJSONObject("snippet")
                fallbackList.add(
                    YouTubeVideoItem(
                        videoId = videoId,
                        title = snippet?.optString("title").orEmpty().ifBlank { clean },
                        channelTitle = snippet?.optString("channelTitle").orEmpty(),
                        channelId = snippet?.optString("channelId").orEmpty(),
                        description = snippet?.optString("description").orEmpty(),
                        thumbnailUrl = extractBestThumbnail(snippet),
                        publishedAt = snippet?.optString("publishedAt").orEmpty()
                    )
                )
            }

            if (videoIds.isEmpty()) {
                return YouTubeResult(true, emptyList())
            }

            val detailedRes = fetchVideoDetailsWithResult(apiKey, videoIds)
            val finalItems = if (detailedRes.success && !detailedRes.data.isNullOrEmpty()) {
                detailedRes.data
            } else {
                fallbackList
            }
            putCached(cacheKey, finalItems)
            YouTubeResult(true, finalItems)
        } catch (e: Exception) {
            Log.e(TAG, "searchVideos parse error: ${e.message}")
            YouTubeResult(false, null, YouTubeErrorCode.HTTP_ERROR, "Failed to parse YouTube search response.")
        }
    }

    fun searchVideos(apiKey: String, query: String, maxResults: Int = 10, order: String = "relevance"): List<YouTubeVideoItem> {
        return searchVideosWithResult(apiKey, query, maxResults, order).data ?: emptyList()
    }

    fun getPopularVideosWithResult(
        apiKey: String,
        regionCode: String = "BD",
        categoryId: String = "",
        maxResults: Int = 10
    ): YouTubeResult<List<YouTubeVideoItem>> {
        val region = regionCode.trim().uppercase(Locale.ROOT).ifBlank { "BD" }
        val cleanCat = categoryId.trim()
        val clampedMax = maxResults.coerceIn(1, 25)
        val cacheKey = "popular:${safeKeyFingerprint(apiKey)}:$region:$cleanCat:$clampedMax"
        getCached<List<YouTubeVideoItem>>(cacheKey)?.let {
            return YouTubeResult(true, it, fromCache = true)
        }

        val catParam = if (cleanCat.isNotBlank()) "&videoCategoryId=${URLEncoder.encode(cleanCat, "UTF-8")}" else ""
        val url = "$baseUrl/videos?part=snippet,statistics,contentDetails&chart=mostPopular&regionCode=$region&maxResults=$clampedMax$catParam"
        val resp = executeGet(url, apiKey)
        if (resp.isFailure) return failureFrom(resp.exceptionOrNull())

        return try {
            val items = parseVideoItems(JSONObject(resp.getOrThrow()))
            putCached(cacheKey, items)
            YouTubeResult(true, items)
        } catch (e: Exception) {
            Log.e(TAG, "getPopularVideos parse error: ${e.message}")
            YouTubeResult(false, null, YouTubeErrorCode.HTTP_ERROR, "Failed to parse popular YouTube videos.")
        }
    }

    fun getPopularVideos(apiKey: String, regionCode: String = "BD", categoryId: String = "", maxResults: Int = 10): List<YouTubeVideoItem> {
        return getPopularVideosWithResult(apiKey, regionCode, categoryId, maxResults).data ?: emptyList()
    }

    fun getVideoDetailsWithResult(apiKey: String, videoIdOrUrl: String): YouTubeResult<YouTubeVideoItem> {
        val videoId = extractVideoId(videoIdOrUrl)
        if (videoId.isBlank()) {
            return YouTubeResult(false, null, YouTubeErrorCode.NOT_FOUND, "Could not extract a valid YouTube video ID from '$videoIdOrUrl'.")
        }
        val listRes = fetchVideoDetailsWithResult(apiKey, listOf(videoId))
        if (!listRes.success) {
            return YouTubeResult(false, null, listRes.errorCode, listRes.errorMessage)
        }
        val first = listRes.data?.firstOrNull()
        return if (first != null) {
            YouTubeResult(true, first, fromCache = listRes.fromCache)
        } else {
            YouTubeResult(false, null, YouTubeErrorCode.NOT_FOUND, "Video '$videoId' was not found on YouTube.")
        }
    }

    fun getVideoDetails(apiKey: String, videoIdOrUrl: String): YouTubeVideoItem? {
        return getVideoDetailsWithResult(apiKey, videoIdOrUrl).data
    }

    private fun fetchVideoDetailsWithResult(apiKey: String, videoIds: List<String>): YouTubeResult<List<YouTubeVideoItem>> {
        if (videoIds.isEmpty()) return YouTubeResult(true, emptyList())
        val ids = videoIds.joinToString(",")
        val cacheKey = "videos:${safeKeyFingerprint(apiKey)}:$ids"
        getCached<List<YouTubeVideoItem>>(cacheKey)?.let {
            return YouTubeResult(true, it, fromCache = true)
        }

        val url = "$baseUrl/videos?part=snippet,statistics,contentDetails&id=${URLEncoder.encode(ids, "UTF-8")}"
        val resp = executeGet(url, apiKey)
        if (resp.isFailure) return failureFrom(resp.exceptionOrNull())

        return try {
            val list = parseVideoItems(JSONObject(resp.getOrThrow()))
            putCached(cacheKey, list)
            YouTubeResult(true, list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchVideoDetails parse error: ${e.message}")
            YouTubeResult(false, null, YouTubeErrorCode.HTTP_ERROR, "Failed to parse YouTube video details.")
        }
    }

    fun searchChannelsWithResult(apiKey: String, query: String, maxResults: Int = 5): YouTubeResult<List<YouTubeChannelItem>> {
        val clean = query.trim()
        if (clean.isBlank()) {
            return YouTubeResult(false, emptyList(), YouTubeErrorCode.NOT_FOUND, "Channel query is empty.")
        }
        val clampedMax = maxResults.coerceIn(1, 15)
        val cacheKey = "search_channels:${safeKeyFingerprint(apiKey)}:${clean.lowercase(Locale.ROOT)}:$clampedMax"
        getCached<List<YouTubeChannelItem>>(cacheKey)?.let {
            return YouTubeResult(true, it, fromCache = true)
        }

        // Direct resolution if @handle or UC... channel ID was passed
        val identifier = extractChannelIdentifier(clean)
        if (identifier is ChannelIdentifier.Handle || identifier is ChannelIdentifier.Id) {
            val single = getChannelDetailsWithResult(apiKey, clean)
            if (single.success && single.data != null) {
                val list = listOf(single.data)
                putCached(cacheKey, list)
                return YouTubeResult(true, list, fromCache = single.fromCache)
            }
            if (!single.success && single.errorCode != YouTubeErrorCode.NOT_FOUND) {
                return YouTubeResult(false, null, single.errorCode, single.errorMessage)
            }
        }

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = "$baseUrl/search?part=snippet&type=channel&maxResults=$clampedMax&q=$encoded"
        val resp = executeGet(url, apiKey)
        if (resp.isFailure) return failureFrom(resp.exceptionOrNull())

        return try {
            val items = JSONObject(resp.getOrThrow()).optJSONArray("items") ?: JSONArray()
            val ids = mutableListOf<String>()
            val snippets = mutableListOf<YouTubeChannelItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val cid = item.optJSONObject("id")?.optString("channelId").orEmpty()
                if (cid.isBlank()) continue
                ids.add(cid)
                val sn = item.optJSONObject("snippet")
                snippets.add(
                    YouTubeChannelItem(
                        channelId = cid,
                        title = sn?.optString("title").orEmpty(),
                        description = sn?.optString("description").orEmpty(),
                        thumbnailUrl = extractBestThumbnail(sn),
                        publishedAt = sn?.optString("publishedAt").orEmpty()
                    )
                )
            }
            if (ids.isEmpty()) return YouTubeResult(true, emptyList())

            val detailsUrl = "$baseUrl/channels?part=snippet,statistics&id=${URLEncoder.encode(ids.joinToString(","), "UTF-8")}"
            val detailsResp = executeGet(detailsUrl, apiKey)
            val finalChannels = if (detailsResp.isSuccess) {
                parseChannelItems(JSONObject(detailsResp.getOrThrow())).ifEmpty { snippets }
            } else {
                snippets
            }
            putCached(cacheKey, finalChannels)
            YouTubeResult(true, finalChannels)
        } catch (e: Exception) {
            Log.e(TAG, "searchChannels parse error: ${e.message}")
            YouTubeResult(false, null, YouTubeErrorCode.HTTP_ERROR, "Failed to parse YouTube channel search response.")
        }
    }

    fun getChannelDetailsWithResult(apiKey: String, channelQueryOrIdOrHandle: String): YouTubeResult<YouTubeChannelItem> {
        val clean = channelQueryOrIdOrHandle.trim()
        if (clean.isBlank()) {
            return YouTubeResult(false, null, YouTubeErrorCode.NOT_FOUND, "Channel name, handle, or ID is empty.")
        }
        val cacheKey = "channel:${safeKeyFingerprint(apiKey)}:${clean.lowercase(Locale.ROOT)}"
        getCached<YouTubeChannelItem>(cacheKey)?.let {
            return YouTubeResult(true, it, fromCache = true)
        }

        val identifier = extractChannelIdentifier(clean)
        when (identifier) {
            is ChannelIdentifier.Id -> {
                val url = "$baseUrl/channels?part=snippet,statistics&id=${URLEncoder.encode(identifier.id, "UTF-8")}"
                val resp = executeGet(url, apiKey)
                if (resp.isFailure) return failureFrom(resp.exceptionOrNull())
                val item = parseChannelItems(JSONObject(resp.getOrThrow())).firstOrNull()
                if (item != null) {
                    putCached(cacheKey, item)
                    return YouTubeResult(true, item)
                }
                return YouTubeResult(false, null, YouTubeErrorCode.NOT_FOUND, "Channel ID '${identifier.id}' was not found.")
            }
            is ChannelIdentifier.Handle -> {
                val handleNoAt = identifier.handle.removePrefix("@")
                val url = "$baseUrl/channels?part=snippet,statistics&forHandle=${URLEncoder.encode(handleNoAt, "UTF-8")}"
                val resp = executeGet(url, apiKey)
                if (resp.isFailure) return failureFrom(resp.exceptionOrNull())
                val item = parseChannelItems(JSONObject(resp.getOrThrow())).firstOrNull()
                if (item != null) {
                    putCached(cacheKey, item)
                    return YouTubeResult(true, item)
                }
                // Fallback to search by handle string if forHandle returned empty
                val fallback = searchChannelsWithResult(apiKey, handleNoAt, maxResults = 1)
                if (!fallback.success) return YouTubeResult(false, null, fallback.errorCode, fallback.errorMessage)
                val first = fallback.data?.firstOrNull()
                return if (first != null) {
                    putCached(cacheKey, first)
                    YouTubeResult(true, first)
                } else {
                    YouTubeResult(false, null, YouTubeErrorCode.NOT_FOUND, "Channel '@$handleNoAt' was not found.")
                }
            }
            is ChannelIdentifier.Query -> {
                val encoded = URLEncoder.encode(identifier.query, "UTF-8")
                val searchUrl = "$baseUrl/search?part=snippet&type=channel&maxResults=1&q=$encoded"
                val searchResp = executeGet(searchUrl, apiKey)
                if (searchResp.isFailure) return failureFrom(searchResp.exceptionOrNull())
                val items = JSONObject(searchResp.getOrThrow()).optJSONArray("items")
                val channelId = if (items != null && items.length() > 0) {
                    items.getJSONObject(0).optJSONObject("id")?.optString("channelId").orEmpty()
                } else ""
                if (channelId.isBlank()) {
                    return YouTubeResult(false, null, YouTubeErrorCode.NOT_FOUND, "Channel '${identifier.query}' was not found.")
                }
                val detailsRes = getChannelDetailsWithResult(apiKey, channelId)
                if (detailsRes.success && detailsRes.data != null) {
                    putCached(cacheKey, detailsRes.data)
                }
                return detailsRes
            }
        }
    }

    fun searchChannel(apiKey: String, channelQuery: String): YouTubeChannelItem? =
        getChannelDetailsWithResult(apiKey, channelQuery).data

    fun fetchChannelDetails(apiKey: String, channelId: String): YouTubeChannelItem? =
        getChannelDetailsWithResult(apiKey, channelId).data

    fun getCategoriesWithResult(apiKey: String, regionCode: String = "BD"): YouTubeResult<List<YouTubeCategoryItem>> {
        val region = regionCode.trim().uppercase(Locale.ROOT).ifBlank { "BD" }
        val cacheKey = "categories:${safeKeyFingerprint(apiKey)}:$region"
        getCached<List<YouTubeCategoryItem>>(cacheKey)?.let {
            return YouTubeResult(true, it, fromCache = true)
        }

        val url = "$baseUrl/videoCategories?part=snippet&regionCode=$region"
        val resp = executeGet(url, apiKey)
        if (resp.isFailure) return failureFrom(resp.exceptionOrNull())

        return try {
            val json = JSONObject(resp.getOrThrow())
            val items = json.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<YouTubeCategoryItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.optJSONObject("snippet")
                if (snippet?.optBoolean("assignable", true) == true) {
                    list.add(
                        YouTubeCategoryItem(
                            categoryId = item.optString("id"),
                            title = snippet.optString("title").orEmpty()
                        )
                    )
                }
            }
            putCached(cacheKey, list, ttlMs = CACHE_TTL_CATEGORY_MS)
            YouTubeResult(true, list)
        } catch (e: Exception) {
            Log.e(TAG, "getCategories parse error: ${e.message}")
            YouTubeResult(false, null, YouTubeErrorCode.HTTP_ERROR, "Failed to parse YouTube categories.")
        }
    }

    fun getCategories(apiKey: String, regionCode: String = "BD"): List<YouTubeCategoryItem> =
        getCategoriesWithResult(apiKey, regionCode).data ?: emptyList()

    fun getCategoryVideosWithResult(
        apiKey: String,
        categoryNameOrId: String,
        regionCode: String = "BD",
        maxResults: Int = 10
    ): YouTubeResult<List<YouTubeVideoItem>> {
        val clean = categoryNameOrId.trim()
        val region = regionCode.trim().uppercase(Locale.ROOT).ifBlank { "BD" }
        if (clean.isBlank()) {
            return getPopularVideosWithResult(apiKey, regionCode = region, categoryId = "", maxResults = maxResults)
        }
        var categoryId = resolveCategoryId(clean)
        if (categoryId == null) {
            val liveCats = getCategoriesWithResult(apiKey, region)
            if (!liveCats.success) {
                return YouTubeResult(false, null, liveCats.errorCode, liveCats.errorMessage)
            }
            categoryId = liveCats.data?.firstOrNull {
                it.title.equals(clean, ignoreCase = true) || it.title.contains(clean, ignoreCase = true)
            }?.categoryId
        }
        if (categoryId.isNullOrBlank()) {
            // Fall back to keyword search when category doesn't map to an assignable chart category
            return searchVideosWithResult(apiKey, "$clean popular videos", maxResults = maxResults, order = "viewCount")
        }
        val popularRes = getPopularVideosWithResult(apiKey, regionCode = region, categoryId = categoryId, maxResults = maxResults)
        if (!popularRes.success && popularRes.errorCode == YouTubeErrorCode.HTTP_ERROR) {
            // Some regional charts reject specific category IDs; retry general search for that category cleanly
            return searchVideosWithResult(apiKey, clean, maxResults = maxResults, order = "viewCount")
        }
        return popularRes
    }

    internal fun resolveCategoryId(input: String): String? {
        val clean = input.trim().lowercase(Locale.ROOT)
        if (clean.all { it.isDigit() } && clean.isNotEmpty()) return clean
        return when {
            clean in setOf("music", "song", "songs", "গান", "মিউজিক", "সংগীত") -> "10"
            clean in setOf("gaming", "game", "games", "গেমিং", "গেম") -> "20"
            clean in setOf("sports", "sport", "cricket", "football", "খেলা", "খেলাধুলা") -> "17"
            clean in setOf("entertainment", "বিনোদন", "নাটক") -> "24"
            clean in setOf("comedy", "কমেডি") -> "23"
            clean in setOf("news", "politics", "খবর", "নিউজ", "সংবাদ") -> "25"
            clean in setOf("education", "study", "tutorial", "শিক্ষা") -> "27"
            clean in setOf("tech", "technology", "science", "টেক", "বিজ্ঞান", "প্রযুক্তি") -> "28"
            clean in setOf("film", "animation", "movie", "movies", "সিনেমা") -> "1"
            clean in setOf("autos", "vehicles", "car", "cars") -> "2"
            clean in setOf("howto", "style", "cooking", "রান্না") -> "26"
            clean in setOf("people", "blogs", "vlog", "ভ্লগ") -> "22"
            else -> null
        }
    }

    private fun parseVideoItems(json: JSONObject): List<YouTubeVideoItem> {
        val items = json.optJSONArray("items") ?: JSONArray()
        val list = mutableListOf<YouTubeVideoItem>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val videoId = item.optString("id")
            val snippet = item.optJSONObject("snippet")
            val stats = item.optJSONObject("statistics")
            val content = item.optJSONObject("contentDetails")

            list.add(
                YouTubeVideoItem(
                    videoId = videoId,
                    title = snippet?.optString("title").orEmpty(),
                    channelTitle = snippet?.optString("channelTitle").orEmpty(),
                    channelId = snippet?.optString("channelId").orEmpty(),
                    description = snippet?.optString("description").orEmpty(),
                    thumbnailUrl = extractBestThumbnail(snippet),
                    publishedAt = snippet?.optString("publishedAt").orEmpty(),
                    viewCount = stats?.optString("viewCount", "0")?.toLongOrNull() ?: 0L,
                    likeCount = stats?.optString("likeCount", "0")?.toLongOrNull() ?: 0L,
                    commentCount = stats?.optString("commentCount", "0")?.toLongOrNull() ?: 0L,
                    duration = formatIsoDuration(content?.optString("duration").orEmpty())
                )
            )
        }
        return list
    }

    private fun parseChannelItems(json: JSONObject): List<YouTubeChannelItem> {
        val items = json.optJSONArray("items") ?: JSONArray()
        val list = mutableListOf<YouTubeChannelItem>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val snippet = item.optJSONObject("snippet")
            val stats = item.optJSONObject("statistics")
            list.add(
                YouTubeChannelItem(
                    channelId = item.optString("id"),
                    title = snippet?.optString("title").orEmpty(),
                    description = snippet?.optString("description").orEmpty(),
                    thumbnailUrl = extractBestThumbnail(snippet),
                    subscriberCount = if (stats?.has("subscriberCount") == true && !stats.optBoolean("hiddenSubscriberCount", false)) {
                        stats.optString("subscriberCount", "-1").toLongOrNull() ?: -1L
                    } else -1L,
                    videoCount = stats?.optString("videoCount", "0")?.toLongOrNull() ?: 0L,
                    viewCount = stats?.optString("viewCount", "0")?.toLongOrNull() ?: 0L,
                    publishedAt = snippet?.optString("publishedAt").orEmpty()
                )
            )
        }
        return list
    }

    private fun extractBestThumbnail(snippet: JSONObject?): String {
        val thumbs = snippet?.optJSONObject("thumbnails") ?: return ""
        return thumbs.optJSONObject("high")?.optString("url")
            ?: thumbs.optJSONObject("medium")?.optString("url")
            ?: thumbs.optJSONObject("default")?.optString("url")
            ?: ""
    }

    fun formatIsoDuration(iso: String): String {
        val clean = iso.trim()
        if (clean.isBlank() || !clean.startsWith("P", ignoreCase = true)) return clean
        val match = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$", RegexOption.IGNORE_CASE).find(clean)
            ?: return clean
        val h = match.groupValues[1].toIntOrNull() ?: 0
        val m = match.groupValues[2].toIntOrNull() ?: 0
        val s = match.groupValues[3].toIntOrNull() ?: 0
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("${h}h")
        if (m > 0) parts.add("${m}m")
        if (s > 0 || parts.isEmpty()) parts.add("${s}s")
        return parts.joinToString(" ")
    }

    fun extractChannelIdentifier(input: String): ChannelIdentifier {
        val clean = input.trim()
        Regex("youtube\\.com/channel/(UC[a-zA-Z0-9_-]{20,})").find(clean)?.let {
            return ChannelIdentifier.Id(it.groupValues[1])
        }
        if (clean.startsWith("UC") && clean.length >= 22 && clean.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return ChannelIdentifier.Id(clean)
        }
        Regex("youtube\\.com/(@[a-zA-Z0-9._-]+)").find(clean)?.let {
            return ChannelIdentifier.Handle(it.groupValues[1])
        }
        Regex("(^|\\s)(@[a-zA-Z0-9._-]+)").find(clean)?.let {
            return ChannelIdentifier.Handle(it.groupValues[2])
        }
        return ChannelIdentifier.Query(clean)
    }

    fun extractVideoId(input: String): String {
        val clean = input.trim()
        if (clean.length == 11 && clean.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return clean
        }
        val patterns = listOf(
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("[?&]v=([a-zA-Z0-9_-]{11})"),
            Regex("embed/([a-zA-Z0-9_-]{11})"),
            Regex("shorts/([a-zA-Z0-9_-]{11})"),
            Regex("live/([a-zA-Z0-9_-]{11})")
        )
        for (pattern in patterns) {
            val match = pattern.find(clean)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        return ""
    }
}
