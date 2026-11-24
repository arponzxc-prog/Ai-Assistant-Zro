package com.arponzxc.novaai.voice

import com.arponzxc.novaai.audio.AudioUtils
import com.arponzxc.novaai.data.model.ChatTurn
import com.arponzxc.novaai.network.GeminiLiveWebSocket
import com.arponzxc.novaai.network.GeminiTextChatEngine
import com.arponzxc.novaai.network.GeminiTextChatError
import com.arponzxc.novaai.util.PromptGenerator
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceAndFaceIntelligenceTest {

    @Test
    fun audioUtils_silenceClosesMouthZero_and24kVoiceOpensMouth() {
        val silence = ByteArray(1024)
        val silentData = AudioUtils.analyzeFrequency(silence, silence.size, sampleRate = 24000)
        assertEquals(0f, silentData.amplitude, 0.0001f)
        assertEquals(0f, silentData.bass, 0.0001f)
        assertEquals(0f, silentData.mid, 0.0001f)
        assertEquals(0f, silentData.high, 0.0001f)

        // Generate 24kHz 16-bit PCM ~220Hz tone at moderate speech level
        val samples = 480
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val s = (sin(2.0 * PI * 220.0 * i / 24000.0) * 6000.0).toInt().toShort()
            pcm[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        val voiceData = AudioUtils.analyzeFrequency(pcm, pcm.size, sampleRate = 24000)
        assertTrue(voiceData.amplitude > 0.2f)
        assertTrue(voiceData.bass > 0.1f)
        assertTrue(voiceData.mid > 0.1f)
    }

    @Test
    fun geminiLiveSetupPayload_configuresModalitiesAndAllYouTubeTools() {
        val audioSetup = GeminiLiveWebSocket.buildSetupPayload(
            model = "models/gemini-3.1-flash-live-preview",
            voiceName = "Puck",
            systemPrompt = "Test prompt",
            responseModalities = listOf("AUDIO")
        ).getJSONObject("setup")

        val audioGenCfg = audioSetup.getJSONObject("generation_config")
        assertEquals("AUDIO", audioGenCfg.getJSONArray("response_modalities").getString(0))
        assertTrue(audioGenCfg.has("speech_config"))

        val textSetup = GeminiLiveWebSocket.buildSetupPayload(
            model = "models/gemini-3.1-flash-live-preview",
            voiceName = "Puck",
            systemPrompt = "Test prompt",
            responseModalities = listOf("TEXT")
        ).getJSONObject("setup")

        val textGenCfg = textSetup.getJSONObject("generation_config")
        assertEquals("TEXT", textGenCfg.getJSONArray("response_modalities").getString(0))
        assertFalse(textGenCfg.has("speech_config"))

        val fnArray = audioSetup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val toolNames = (0 until fnArray.length()).map { fnArray.getJSONObject(it).getString("name") }.toSet()
        assertTrue("youtube_search_videos" in toolNames)
        assertTrue("youtube_get_video_details" in toolNames)
        assertTrue("youtube_get_channel_details" in toolNames)
        assertTrue("youtube_get_channel_statistics" in toolNames)
        assertTrue("youtube_get_popular_videos" in toolNames)
        assertTrue("youtube_search_channels" in toolNames)
        assertTrue("youtube_get_category_videos" in toolNames)
    }

    @Test
    fun promptGenerator_containsYouTubeIntelligenceToolsAndHonestyMandate() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = "Nova Pro",
            userName = "Sir"
        )
        assertTrue(prompt.contains("youtube_search_videos"))
        assertTrue(prompt.contains("youtube_get_video_details"))
        assertTrue(prompt.contains("youtube_get_channel_statistics"))
        assertTrue(prompt.contains("transcript_available=false"))
    }

    @Test
    fun textChatEngine_resolvesLiveModelsToRestAndExecutesTools() = runBlocking {
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"),
            GeminiTextChatEngine.resolveCandidateRestModels("models/gemini-3.1-flash-live-preview")
        )

        var step = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                step++
                val body = if (step == 1) {
                    """{
                      "candidates":[{
                        "content":{
                          "parts":[{
                            "functionCall":{
                              "name":"youtube_search_videos",
                              "args":{"query":"Android Compose"}
                            }
                          }]
                        }
                      }]
                    }"""
                } else {
                    """{
                      "candidates":[{
                        "content":{
                          "parts":[{
                            "text":"Here is the top Jetpack Compose video on YouTube."
                          }]
                        }
                      }]
                    }"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()

        val engine = GeminiTextChatEngine(httpClient = client, baseUrl = "https://mock.local/v1beta")
        val res = engine.generateReply(
            apiKey = "fake-gemini-key",
            configuredModel = "models/gemini-3.1-flash-live-preview",
            systemPrompt = "You are NOVA AI",
            recentTurns = listOf(ChatTurn(userTranscript = "Hi", jarvisResponse = "Hello Sir")),
            userMessage = "Find Compose videos",
            toolExecutor = { name, _ ->
                JSONObject().put("success", true).put("tool", name)
            }
        )

        assertTrue(res.success)
        assertEquals("Here is the top Jetpack Compose video on YouTube.", res.replyText)
        assertEquals(listOf("youtube_search_videos"), res.executedTools)
        assertEquals(2, step)
    }

    @Test
    fun textChatEngine_missingAndInvalidKeyReturnCleanErrors() = runBlocking {
        val engine = GeminiTextChatEngine()
        val missing = engine.generateReply("", "gemini-2.5-flash", "Sys", emptyList(), "Hello")
        assertFalse(missing.success)
        assertEquals(GeminiTextChatError.API_KEY_MISSING, missing.error)

        val badClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("""{"error":{"code":403,"message":"API_KEY_INVALID"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val invalid = GeminiTextChatEngine(badClient, "https://mock.local/v1beta")
            .generateReply("bad-key", "gemini-2.5-flash", "Sys", emptyList(), "Hello")
        assertFalse(invalid.success)
        assertEquals(GeminiTextChatError.API_KEY_INVALID, invalid.error)
    }
}
