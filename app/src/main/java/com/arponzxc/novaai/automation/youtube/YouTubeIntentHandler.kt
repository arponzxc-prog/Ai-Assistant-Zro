package com.arponzxc.novaai.automation.youtube

import java.util.Locale

sealed class YouTubeIntentResult {
    data class ShowPopularVideos(val regionCode: String = "BD", val categoryId: String = "") : YouTubeIntentResult()
    data class SearchVideos(val query: String, val sortOrder: String = "relevance") : YouTubeIntentResult()
    data class ChannelInfo(val channelQuery: String) : YouTubeIntentResult()
    data class VideoStats(val videoUrlOrId: String) : YouTubeIntentResult()
    data class CategoryPopular(val categoryName: String, val regionCode: String = "BD") : YouTubeIntentResult()
}

object YouTubeIntentHandler {

    fun parse(utterance: String): YouTubeIntentResult? {
        val raw = utterance.trim()
        val clean = raw.lowercase(Locale.ROOT)
        if (clean.isBlank()) return null

        val detectedRegion = when {
            clean.contains("india") || clean.contains("ভারত") -> "IN"
            clean.contains("usa") || clean.contains("america") || clean.contains("us ") -> "US"
            else -> "BD"
        }

        // 1. Category / Popular / Trending videos (Bangladesh & multilingual)
        val categories = mapOf(
            "gaming" to "20", "game" to "20", "গেমিং" to "20", "গেম" to "20",
            "music" to "10", "gane" to "10", "গান" to "10", "মিউজিক" to "10",
            "entertainment" to "24", "বিনোদন" to "24", "নাটক" to "24",
            "comedy" to "23", "কমেডি" to "23",
            "news" to "25", "খবর" to "25", "সংবাদ" to "25",
            "tech" to "28", "technology" to "28", "টেক" to "28",
            "education" to "27", "শিক্ষা" to "27",
            "sports" to "17", "cricket" to "17", "খেলা" to "17"
        )
        var detectedCategoryName = ""
        var catId = ""
        for ((key, id) in categories) {
            if (clean.contains(key)) {
                catId = id
                detectedCategoryName = key
                break
            }
        }

        val isPopularQuery = clean.contains("জনপ্রিয়") || clean.contains("জনপ্রিয়") ||
            clean.contains("popular") || clean.contains("trending") || clean.contains("ট্রেন্ডিং")
        if (isPopularQuery) {
            if (detectedCategoryName.isNotBlank() && clean.contains("category") || clean.contains("ক্যাটাগরি")) {
                return YouTubeIntentResult.CategoryPopular(detectedCategoryName, regionCode = detectedRegion)
            }
            return YouTubeIntentResult.ShowPopularVideos(regionCode = detectedRegion, categoryId = catId)
        }

        // 2. Channel statistics / subscribers / @handle lookup
        val handleMatch = Regex("@[a-zA-Z0-9._-]+").find(raw)
        if (clean.contains("সাবস্ক্রাইবার") || clean.contains("subscriber") || clean.contains("channel info") ||
            clean.contains("চ্যানেলের তথ্য") || clean.contains("channel stats") || handleMatch != null
        ) {
            val query = handleMatch?.value ?: raw.replace(
                Regex("(?i)(কতজন|কতো|subscriber|subscribers|subs|সাবস্ক্রাইবার|channel|চ্যানেলের|চ্যানেল|info|stats|statistics|তথ্য|এর|কি|কীভাবে|কিভাবে|show|tell|me|batao|dekhao)\\s*"),
                ""
            ).trim()
            return YouTubeIntentResult.ChannelInfo(query.ifBlank { raw })
        }

        // 3. Video view / statistics lookup
        if (clean.contains("ভিউ") || clean.contains("view") || clean.contains("like") ||
            clean.contains("কমেন্ট") || clean.contains("comment") || clean.contains("ভিডিওর তথ্য") ||
            YouTubeDataRepository.extractVideoId(raw).isNotBlank()
        ) {
            val urlOrId = YouTubeDataRepository.extractVideoId(raw)
            if (urlOrId.isNotBlank()) {
                return YouTubeIntentResult.VideoStats(urlOrId)
            }
            return YouTubeIntentResult.VideoStats(raw)
        }

        // 4. Video search requests
        if (clean.startsWith("search ") || clean.contains("সার্চ করো") || clean.contains("খুঁজে দাও") ||
            clean.contains("খুঁজে বের করো") || clean.contains("video search") || clean.contains("ইউটিউবে")
        ) {
            val query = raw.replace(
                Regex("(?i)(youtube-এ|youtube এ|youtube|ইউটিউবে|search|video|videos|সার্চ|করো|খুঁজে|দাও|বের|about|for|er)\\s*"),
                ""
            ).trim()
            return YouTubeIntentResult.SearchVideos(query.ifBlank { raw })
        }

        return null
    }

    fun generateNaturalAnswer(result: YouTubeIntentResult, apiKey: String): Pair<String, Any?> {
        if (apiKey.isBlank()) {
            return Pair("ইউটিউব ডেটা API কী কনফিগার করা নেই। দয়া করে Settings -> API Connection থেকে YouTube API Key দিন।", null)
        }

        return when (result) {
            is YouTubeIntentResult.ShowPopularVideos -> {
                val res = YouTubeDataRepository.getPopularVideosWithResult(apiKey, regionCode = result.regionCode, categoryId = result.categoryId)
                if (!res.success) return Pair(formatErrorSpeech(res.errorCode, res.errorMessage), null)
                val videos = res.data.orEmpty()
                if (videos.isEmpty()) return Pair("ইউটিউব থেকে জনপ্রিয় ভিডিও পাওয়া যায়নি।", null)
                val top = videos.first()
                val speech = "ইউটিউবে (${result.regionCode}) বর্তমানে জনপ্রিয় শীর্ষ ভিডিও '${top.title}', চ্যানেল: ${top.channelTitle} (${formatCount(top.viewCount)} ভিউ)।"
                Pair(speech, videos)
            }
            is YouTubeIntentResult.SearchVideos -> {
                val res = YouTubeDataRepository.searchVideosWithResult(apiKey, result.query, order = result.sortOrder)
                if (!res.success) return Pair(formatErrorSpeech(res.errorCode, res.errorMessage), null)
                val videos = res.data.orEmpty()
                if (videos.isEmpty()) return Pair("'${result.query}' বিষয়ে কোনো ভিডিও পাওয়া যায়নি।", null)
                val top = videos.first()
                val speech = "'${result.query}' সার্চে শীর্ষ ভিডিও '${top.title}' (${top.channelTitle}, ${formatCount(top.viewCount)} ভিউ)।"
                Pair(speech, videos)
            }
            is YouTubeIntentResult.ChannelInfo -> {
                val res = YouTubeDataRepository.getChannelDetailsWithResult(apiKey, result.channelQuery)
                if (!res.success || res.data == null) return Pair(formatErrorSpeech(res.errorCode, res.errorMessage), null)
                val channel = res.data
                val subsText = if (channel.subscriberCount >= 0) "${formatCount(channel.subscriberCount)} জন" else "লুকানো"
                val speech = "${channel.title} চ্যানেলে সাবস্ক্রাইবার $subsText, মোট ভিডিও ${formatCount(channel.videoCount)}টি এবং মোট ভিউ ${formatCount(channel.viewCount)}।"
                Pair(speech, channel)
            }
            is YouTubeIntentResult.VideoStats -> {
                val res = YouTubeDataRepository.getVideoDetailsWithResult(apiKey, result.videoUrlOrId)
                if (!res.success || res.data == null) return Pair(formatErrorSpeech(res.errorCode, res.errorMessage), null)
                val video = res.data
                val dur = if (video.duration.isNotBlank()) ", দৈর্ঘ্য ${video.duration}" else ""
                val speech = "'${video.title}' ভিডিওতে ${formatCount(video.viewCount)} ভিউ, ${formatCount(video.likeCount)} লাইক এবং ${formatCount(video.commentCount)} কমেন্ট রয়েছে$dur।"
                Pair(speech, video)
            }
            is YouTubeIntentResult.CategoryPopular -> {
                val res = YouTubeDataRepository.getCategoryVideosWithResult(apiKey, result.categoryName, regionCode = result.regionCode)
                if (!res.success) return Pair(formatErrorSpeech(res.errorCode, res.errorMessage), null)
                val videos = res.data.orEmpty()
                if (videos.isEmpty()) return Pair("'${result.categoryName}' ক্যাটাগরিতে কোনো ভিডিও পাওয়া যায়নি।", null)
                val top = videos.first()
                Pair("'${result.categoryName}' ক্যাটাগরির জনপ্রিয় ভিডিও '${top.title}' (${top.channelTitle})।", videos)
            }
        }
    }

    internal fun formatErrorSpeech(code: YouTubeErrorCode, fallbackMsg: String): String = when (code) {
        YouTubeErrorCode.API_KEY_MISSING -> "ইউটিউব API কী সেট করা নেই। Settings -> API Connection থেকে কী যোগ করুন।"
        YouTubeErrorCode.API_KEY_INVALID -> "ইউটিউব API কী সঠিক নয় বা অনুমতি নেই। অনুগ্রহ করে API Connection-এ গিয়ে কী যাচাই করুন।"
        YouTubeErrorCode.QUOTA_EXCEEDED -> "ইউটিউব API-এর আজকের কোটা শেষ হয়ে গেছে। কিছু সময় পর আবার চেষ্টা করুন।"
        YouTubeErrorCode.TIMEOUT, YouTubeErrorCode.NETWORK_ERROR -> "ইন্টারনেট সংযোগের সমস্যার কারণে ইউটিউবের সাথে যুক্ত হওয়া যায়নি।"
        YouTubeErrorCode.NOT_FOUND -> fallbackMsg.ifBlank { "চিহ্নিত ইউটিউব তথ্যটি খুঁজে পাওয়া যায়নি।" }
        else -> fallbackMsg.ifBlank { "ইউটিউব ডেটা সংগ্রহ করতে সমস্যা হয়েছে।" }
    }

    private fun formatCount(count: Long): String {
        if (count < 0) return "N/A"
        return when {
            count >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1fB", count / 1_000_000_000.0)
            count >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(Locale.ROOT, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
