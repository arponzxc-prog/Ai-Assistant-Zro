package com.arponzxc.novaai.automation.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class YouTubeIntelligenceTest {

    private var fakeNowMs = AtomicLong(1_000_000L)

    @Before
    fun setUp() {
        YouTubeDataRepository.clearCache()
        YouTubeDataRepository.clockMs = { fakeNowMs.get() }
    }

    @After
    fun tearDown() {
        YouTubeDataRepository.httpClientOverride = null
        YouTubeDataRepository.clockMs = System::currentTimeMillis
        YouTubeDataRepository.clearCache()
    }

    private fun mockHttp(handler: (url: String) -> Pair<Int, String>): Pair<OkHttpClient, AtomicInteger> {
        val callCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount.incrementAndGet()
                val req = chain.request()
                val (code, body) = handler(req.url.toString())
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Mock")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        return client to callCount
    }

    @Test
    fun extractVideoId_supportsStandardShortsAndShortUrls() {
        assertEquals("dQw4w9WgXcQ", YouTubeDataRepository.extractVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeDataRepository.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=12s"))
        assertEquals("dQw4w9WgXcQ", YouTubeDataRepository.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", YouTubeDataRepository.extractVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeDataRepository.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun extractChannelIdentifier_resolvesHandlesUrlsIdsAndNames() {
        val handleDirect = YouTubeDataRepository.extractChannelIdentifier("@nexastudiox")
        assertTrue(handleDirect is ChannelIdentifier.Handle && handleDirect.handle == "@nexastudiox")

        val handleUrl = YouTubeDataRepository.extractChannelIdentifier("https://www.youtube.com/@mkbhd/videos")
        assertTrue(handleUrl is ChannelIdentifier.Handle && handleUrl.handle == "@mkbhd")

        val cid = "UCBcRF18a7Qf58cCRy5xuWwQ"
        val idDirect = YouTubeDataRepository.extractChannelIdentifier(cid)
        assertTrue(idDirect is ChannelIdentifier.Id && idDirect.id == cid)

        val idUrl = YouTubeDataRepository.extractChannelIdentifier("https://youtube.com/channel/$cid")
        assertTrue(idUrl is ChannelIdentifier.Id && idUrl.id == cid)

        val name = YouTubeDataRepository.extractChannelIdentifier("Marques Brownlee")
        assertTrue(name is ChannelIdentifier.Query && name.query == "Marques Brownlee")
    }

    @Test
    fun formatIsoDuration_formatsHoursMinutesSeconds() {
        assertEquals("1h 2m 3s", YouTubeDataRepository.formatIsoDuration("PT1H2M3S"))
        assertEquals("4m 5s", YouTubeDataRepository.formatIsoDuration("PT4M5S"))
        assertEquals("45s", YouTubeDataRepository.formatIsoDuration("PT45S"))
        assertEquals("12m", YouTubeDataRepository.formatIsoDuration("PT12M"))
        assertEquals("INVALID", YouTubeDataRepository.formatIsoDuration("INVALID"))
    }

    @Test
    fun resolveCategoryId_supportsEnglishAndBengali() {
        assertEquals("10", YouTubeDataRepository.resolveCategoryId("Music"))
        assertEquals("10", YouTubeDataRepository.resolveCategoryId("গান"))
        assertEquals("17", YouTubeDataRepository.resolveCategoryId("খেলা"))
        assertEquals("25", YouTubeDataRepository.resolveCategoryId("খবর"))
        assertEquals("28", YouTubeDataRepository.resolveCategoryId("প্রযুক্তি"))
        assertEquals("20", YouTubeDataRepository.resolveCategoryId("Gaming"))
    }

    @Test
    fun missingApiKey_returnsApiKeyMissingWithoutNetworkCall() {
        val (_, calls) = mockHttp { 200 to "{}" }
        val result = YouTubeToolExecutor.execute(
            "youtube_search_videos",
            org.json.JSONObject().put("query", "Kotlin tutorial"),
            ""
        )
        assertFalse(result.optBoolean("success"))
        assertEquals("API_KEY_MISSING", result.optString("error_code"))
        assertEquals(0, calls.get())
    }

    @Test
    fun invalidKey_returnsApiKeyInvalidAndNeverLeaksKey() {
        val secretKey = "AIzaSySecretUnitTestKey999"
        val (client, _) = mockHttp {
            400 to """{"error":{"code":400,"message":"API key not valid for $secretKey"}}"""
        }
        YouTubeDataRepository.httpClientOverride = client
        val res = YouTubeDataRepository.searchVideosWithResult(secretKey, "AI news")
        assertFalse(res.success)
        assertEquals(YouTubeErrorCode.API_KEY_INVALID, res.errorCode)
        assertFalse(res.errorMessage.contains(secretKey))
    }

    @Test
    fun quotaExceeded_returnsQuotaError() {
        val (client, _) = mockHttp {
            403 to """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}],"message":"The request cannot be completed because you have exceeded your quota."}}"""
        }
        YouTubeDataRepository.httpClientOverride = client
        val res = YouTubeDataRepository.getPopularVideosWithResult("fake-key", "BD")
        assertFalse(res.success)
        assertEquals(YouTubeErrorCode.QUOTA_EXCEEDED, res.errorCode)
    }

    @Test
    fun timeout_returnsTimeoutErrorCode() {
        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("Read timed out") }
            .build()
        YouTubeDataRepository.httpClientOverride = timeoutClient
        val res = YouTubeDataRepository.getVideoDetailsWithResult("fake-key", "dQw4w9WgXcQ")
        assertFalse(res.success)
        assertEquals(YouTubeErrorCode.TIMEOUT, res.errorCode)
    }

    @Test
    fun searchAndVideoDetailsAndCaching_workEndToEnd() {
        val (client, calls) = mockHttp { url ->
            when {
                url.contains("/search?") && url.contains("type=video") -> {
                    200 to """{"items":[{"id":{"videoId":"vid12345678"}}]}"""
                }
                url.contains("/videos?") -> {
                    200 to """{
                      "items":[{
                        "id":"vid12345678",
                        "snippet":{"title":"NOVA AI Demo","channelTitle":"Nova Labs","channelId":"UC123","description":"Test desc","publishedAt":"2026-01-01T00:00:00Z"},
                        "contentDetails":{"duration":"PT5M30S"},
                        "statistics":{"viewCount":"15000","likeCount":"900","commentCount":"42"}
                      }]
                    }"""
                }
                else -> 404 to "{}"
            }
        }
        YouTubeDataRepository.httpClientOverride = client

        val r1 = YouTubeToolExecutor.execute(
            "youtube_search_videos",
            org.json.JSONObject().put("query", "NOVA AI").put("max_results", 3),
            "fake-key"
        )
        assertTrue(r1.optBoolean("success"))
        assertTrue(r1.optBoolean("metadata_only"))
        assertFalse(r1.optBoolean("transcript_available"))
        assertEquals(1, r1.optInt("count"))
        val firstVid = r1.getJSONArray("videos").getJSONObject(0)
        assertEquals("NOVA AI Demo", firstVid.optString("title"))
        assertEquals("5m 30s", firstVid.optString("duration"))
        assertEquals(2, calls.get()) // search + videos hydrate

        // Second identical search hits TTL cache
        val r2 = YouTubeToolExecutor.execute(
            "youtube_search_videos",
            org.json.JSONObject().put("query", "NOVA AI").put("max_results", 3),
            "fake-key"
        )
        assertTrue(r2.optBoolean("success"))
        assertTrue(r2.optBoolean("from_cache"))
        assertEquals(2, calls.get())

        // Advance time beyond 3-minute TTL -> refreshes from network
        fakeNowMs.addAndGet(4 * 60_000L)
        val r3 = YouTubeToolExecutor.execute(
            "youtube_search_videos",
            org.json.JSONObject().put("query", "NOVA AI").put("max_results", 3),
            "fake-key"
        )
        assertTrue(r3.optBoolean("success"))
        assertFalse(r3.optBoolean("from_cache"))
        assertEquals(4, calls.get())
    }

    @Test
    fun channelDetailsPopularAndCategoryTools_returnStructuredData() {
        val (client, _) = mockHttp { url ->
            when {
                url.contains("/channels?") -> {
                    200 to """{
                      "items":[{
                        "id":"UC999",
                        "snippet":{"title":"Dhaka Tech","description":"Bangla tech channel","publishedAt":"2020-05-01T00:00:00Z"},
                        "statistics":{"subscriberCount":"250000","videoCount":"320","viewCount":"18000000"}
                      }]
                    }"""
                }
                url.contains("/search?") && url.contains("type=channel") -> {
                    200 to """{"items":[{"id":{"channelId":"UC999"}}]}"""
                }
                url.contains("/videos?") && url.contains("chart=mostPopular") -> {
                    200 to """{
                      "items":[{
                        "id":"pop1",
                        "snippet":{"title":"Trending Track","channelTitle":"Dhaka Music","channelId":"UC77","description":"Popular","publishedAt":"2026-02-01T00:00:00Z"},
                        "contentDetails":{"duration":"PT3M15S"},
                        "statistics":{"viewCount":"980000","likeCount":"45000","commentCount":"1200"}
                      }]
                    }"""
                }
                else -> 404 to "{}"
            }
        }
        YouTubeDataRepository.httpClientOverride = client

        val chRes = YouTubeToolExecutor.execute(
            "youtube_get_channel_statistics",
            org.json.JSONObject().put("channel_query", "@DhakaTech"),
            "fake-key"
        )
        assertTrue(chRes.optBoolean("success"))
        assertEquals(250000L, chRes.getJSONObject("channel").optLong("subscriber_count"))

        val searchCh = YouTubeToolExecutor.execute(
            "youtube_search_channels",
            org.json.JSONObject().put("query", "Dhaka Tech"),
            "fake-key"
        )
        assertTrue(searchCh.optBoolean("success"))
        assertEquals(1, searchCh.optInt("count"))

        val popRes = YouTubeToolExecutor.execute(
            "youtube_get_popular_videos",
            org.json.JSONObject().put("region_code", "BD"),
            "fake-key"
        )
        assertTrue(popRes.optBoolean("success"))
        assertEquals("Trending Track", popRes.getJSONArray("videos").getJSONObject(0).optString("title"))

        val catRes = YouTubeToolExecutor.execute(
            "youtube_get_category_videos",
            org.json.JSONObject().put("category", "গান").put("region_code", "BD"),
            "fake-key"
        )
        assertTrue(catRes.optBoolean("success"))
        assertEquals("3m 15s", catRes.getJSONArray("videos").getJSONObject(0).optString("duration"))
    }

    @Test
    fun intentHandler_handlesBengaliAndEnglishQueriesHonesty() = runBlocking {
        val (client, _) = mockHttp { url ->
            when {
                url.contains("/videos?") && url.contains("chart=mostPopular") -> {
                    200 to """{
                      "items":[{
                        "id":"bn1",
                        "snippet":{"title":"বাংলা নতুন গান","channelTitle":"Bangla Studio","channelId":"UC1","description":"Desc","publishedAt":"2026-01-01T00:00:00Z"},
                        "contentDetails":{"duration":"PT4M00S"},
                        "statistics":{"viewCount":"120000","likeCount":"5000","commentCount":"100"}
                      }]
                    }"""
                }
                else -> 200 to """{"items":[]}"""
            }
        }
        YouTubeDataRepository.httpClientOverride = client

        val intent = YouTubeIntentHandler.parse("বাংলাদেশের ট্রেন্ডিং গান দেখাও")
        assertTrue(intent is YouTubeIntentResult.ShowPopularVideos)
        val (reply, _) = YouTubeIntentHandler.generateNaturalAnswer(intent!!, "fake-key")
        assertTrue(reply.contains("বাংলা নতুন গান"))

        assertTrue(YouTubeIntentHandler.parse("ইউটিউব চ্যানেল @DhakaTech এর সাবস্ক্রাইবার কত?") is YouTubeIntentResult.ChannelInfo)
        assertTrue(YouTubeIntentHandler.parse("Show trending videos on YouTube") is YouTubeIntentResult.ShowPopularVideos)
    }
}
