package com.arponzxc.novaai.automation.youtube

import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the 7 structured YouTube Data API v3 intelligence tools shared by
 * Gemini Live (WebSocket function calling) and Gemini Text Chat (REST function calling).
 */
object YouTubeToolExecutor {

    val TOOL_NAMES: Set<String> = setOf(
        "youtube_search_videos",
        "youtube_get_video_details",
        "youtube_get_channel_details",
        "youtube_get_channel_statistics",
        "youtube_get_popular_videos",
        "youtube_search_channels",
        "youtube_get_category_videos"
    )

    fun isYouTubeTool(name: String): Boolean = name in TOOL_NAMES

    fun execute(name: String, args: JSONObject, apiKey: String): JSONObject {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return errorJson(
                YouTubeErrorCode.API_KEY_MISSING,
                "YouTube Data API key is missing. Ask the user to add their YouTube API key in Settings -> API Connection."
            )
        }

        return when (name) {
            "youtube_search_videos" -> {
                val query = args.optString("query", "").trim()
                val maxResults = args.optInt("max_results", 5).coerceIn(1, 20)
                val order = args.optString("order", "relevance").trim().ifBlank { "relevance" }
                val res = YouTubeDataRepository.searchVideosWithResult(cleanKey, query, maxResults, order)
                videoListToJson(res, emptyMsg = "No videos found on YouTube for '$query'.")
            }

            "youtube_get_video_details" -> {
                val videoRef = args.optString("video_id_or_url", "")
                    .ifBlank { args.optString("video_id", "") }
                    .ifBlank { args.optString("url", "") }
                    .trim()
                val res = YouTubeDataRepository.getVideoDetailsWithResult(cleanKey, videoRef)
                if (!res.success || res.data == null) {
                    errorJson(res.errorCode, res.errorMessage)
                } else {
                    JSONObject().apply {
                        put("success", true)
                        put("from_cache", res.fromCache)
                        put("metadata_only", true)
                        put("transcript_available", false)
                        put("video", videoToJson(res.data))
                        put("message", "Retrieved YouTube video metadata (title, channel, description, statistics, duration). Full audio/caption transcript is not provided by this API.")
                    }
                }
            }

            "youtube_get_channel_details", "youtube_get_channel_statistics" -> {
                val query = args.optString("channel_query", "")
                    .ifBlank { args.optString("channel_name_or_handle", "") }
                    .ifBlank { args.optString("channel_id", "") }
                    .ifBlank { args.optString("query", "") }
                    .trim()
                val res = YouTubeDataRepository.getChannelDetailsWithResult(cleanKey, query)
                if (!res.success || res.data == null) {
                    errorJson(res.errorCode, res.errorMessage)
                } else {
                    JSONObject().apply {
                        put("success", true)
                        put("from_cache", res.fromCache)
                        put("channel", channelToJson(res.data))
                        put("message", "Retrieved verified YouTube channel details and statistics.")
                    }
                }
            }

            "youtube_get_popular_videos" -> {
                val region = args.optString("region_code", "BD").trim().ifBlank { "BD" }
                val categoryId = args.optString("category_id", "").trim()
                val maxResults = args.optInt("max_results", 8).coerceIn(1, 20)
                val res = YouTubeDataRepository.getPopularVideosWithResult(cleanKey, region, categoryId, maxResults)
                videoListToJson(res, emptyMsg = "No trending/popular YouTube videos returned for region '$region'.")
            }

            "youtube_search_channels" -> {
                val query = args.optString("query", "").ifBlank { args.optString("channel_query", "") }.trim()
                val maxResults = args.optInt("max_results", 5).coerceIn(1, 15)
                val res = YouTubeDataRepository.searchChannelsWithResult(cleanKey, query, maxResults)
                if (!res.success) {
                    errorJson(res.errorCode, res.errorMessage)
                } else {
                    val arr = JSONArray()
                    res.data.orEmpty().forEach { arr.put(channelToJson(it)) }
                    JSONObject().apply {
                        put("success", true)
                        put("from_cache", res.fromCache)
                        put("count", arr.length())
                        put("channels", arr)
                        put("message", if (arr.length() == 0) "No channels found for '$query'." else "Found ${arr.length()} YouTube channel(s).")
                    }
                }
            }

            "youtube_get_category_videos" -> {
                val category = args.optString("category", "")
                    .ifBlank { args.optString("category_name_or_id", "") }
                    .trim()
                val region = args.optString("region_code", "BD").trim().ifBlank { "BD" }
                val maxResults = args.optInt("max_results", 8).coerceIn(1, 20)
                val res = YouTubeDataRepository.getCategoryVideosWithResult(cleanKey, category, region, maxResults)
                videoListToJson(res, emptyMsg = "No videos found for category '$category' in region '$region'.")
            }

            else -> errorJson(YouTubeErrorCode.NOT_FOUND, "Unknown YouTube tool: $name")
        }
    }

    private fun videoListToJson(res: YouTubeResult<List<YouTubeVideoItem>>, emptyMsg: String): JSONObject {
        if (!res.success) {
            return errorJson(res.errorCode, res.errorMessage)
        }
        val items = res.data.orEmpty()
        val arr = JSONArray()
        items.forEach { arr.put(videoToJson(it)) }
        return JSONObject().apply {
            put("success", true)
            put("from_cache", res.fromCache)
            put("metadata_only", true)
            put("transcript_available", false)
            put("count", arr.length())
            put("videos", arr)
            put("message", if (items.isEmpty()) emptyMsg else "Retrieved ${items.size} YouTube video(s).")
        }
    }

    private fun videoToJson(v: YouTubeVideoItem): JSONObject = JSONObject().apply {
        put("video_id", v.videoId)
        put("url", "https://www.youtube.com/watch?v=${v.videoId}")
        put("title", v.title)
        put("channel_title", v.channelTitle)
        put("channel_id", v.channelId)
        put("description", v.description.take(450))
        put("published_at", v.publishedAt)
        put("duration", v.duration)
        put("view_count", v.viewCount)
        put("like_count", v.likeCount)
        put("comment_count", v.commentCount)
    }

    private fun channelToJson(c: YouTubeChannelItem): JSONObject = JSONObject().apply {
        put("channel_id", c.channelId)
        put("url", "https://www.youtube.com/channel/${c.channelId}")
        put("title", c.title)
        put("description", c.description.take(400))
        put("subscriber_count", if (c.subscriberCount >= 0) c.subscriberCount else JSONObject.NULL)
        put("subscribers_hidden", c.subscriberCount < 0)
        put("video_count", c.videoCount)
        put("view_count", c.viewCount)
        put("published_at", c.publishedAt)
    }

    private fun errorJson(code: YouTubeErrorCode, message: String): JSONObject = JSONObject().apply {
        put("success", false)
        put("error_code", code.name)
        put("message", message)
    }
}
