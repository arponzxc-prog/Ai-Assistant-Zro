package com.arponzxc.novaai.network.apimanager

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiProviderApiManagerTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removals.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) ->
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun fakeContext(prefsMap: MutableMap<String, SharedPreferences> = mutableMapOf()): Context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return prefsMap.getOrPut(name ?: "default") { InMemorySharedPreferences() }
            }
        }

    @Test
    fun `api key masking never leaks full secret`() {
        val masked = ApiKeyEntry.maskApiKey("AIzaSySuperSecretKey1234567890")
        assertTrue(masked.startsWith("AIza"))
        assertTrue(masked.endsWith("7890"))
        assertFalse(masked.contains("SuperSecret"))

        assertEquals("Not configured", ApiKeyEntry.maskApiKey("   "))
        assertEquals("••••78", ApiKeyEntry.maskApiKey("12345678"))
    }

    @Test
    fun `secure key storage fails closed when keystore unavailable and never writes plaintext`() {
        val backingMap = mutableMapOf<String, SharedPreferences>()
        val ctx = fakeContext(backingMap)
        val storage = SecureKeyStorage(
            context = ctx,
            injectedSecurePrefsForTest = null,
            forceUnavailableForTest = true
        )

        assertFalse(storage.canStoreSecurely)
        var threwIllegalState = false
        try {
            storage.saveApiKey("openai", "sk-secret-test-key", "Main")
        } catch (_: IllegalStateException) {
            threwIllegalState = true
        }
        assertTrue("Expected fail-closed IllegalStateException on key write", threwIllegalState)
        assertEquals("", storage.getApiKey("openai"))
        val fallbackPrefs = backingMap["nova_fallback_keys"]
        assertTrue(fallbackPrefs == null || fallbackPrefs.all.isEmpty())
    }

    @Test
    fun `multi key crud enable disable cooldown rotation and usage stats work`() {
        val memPrefs = InMemorySharedPreferences()
        val storage = SecureKeyStorage(fakeContext(), injectedSecurePrefsForTest = memPrefs)
        assertTrue(storage.canStoreSecurely)

        val k1 = storage.addApiKeyEntry("openai", "Primary", "sk-primary-1111")
        val k2 = storage.addApiKeyEntry("openai", "Backup", "sk-backup-2222")
        assertEquals(2, storage.getApiKeyEntries("openai").size)
        assertEquals("sk-primary-1111", storage.getApiKey("openai"))

        // Cooldown primary due to rate limit -> rotates to backup key
        storage.markKeyOutcome("openai", k1.id, success = false, rateLimitedOrInvalid = true)
        assertEquals("sk-backup-2222", storage.getApiKey("openai"))

        // Disable backup -> primary still falls back when enabled if nothing else is ready
        storage.setApiKeyEnabled("openai", k2.id, enabled = false)
        assertFalse(storage.getApiKeyEntries("openai").first { it.id == k2.id }.isEnabled)

        storage.removeApiKeyEntry("openai", k1.id)
        assertEquals(1, storage.getApiKeyEntries("openai").size)

        // Custom provider CRUD & model selection
        val custom = AiProviderConfig(
            id = "groq_custom",
            name = "Groq Llama",
            baseUrl = "https://api.groq.com/openai/v1/",
            defaultModel = "llama-3.3-70b-versatile",
            apiFormat = AiProviderFormat.OPENAI,
            isBuiltin = false
        )
        storage.saveCustomProvider(custom)
        storage.saveSelectedProvider("groq_custom")
        storage.saveSelectedModel("llama-3.3-70b-versatile", "groq_custom")
        assertEquals("groq_custom", storage.getSelectedProvider())
        assertEquals("llama-3.3-70b-versatile", storage.getSelectedModel("groq_custom"))
        assertEquals(1, storage.getCustomProviders().size)

        // Usage counters and reset
        storage.recordRequestUsage("openai", success = true, latencyMs = 320L, rotated = true)
        val stats = storage.getUsageStats("openai")
        assertEquals(1L, stats.totalRequests)
        assertEquals(1L, stats.successfulRequests)
        assertEquals(1L, stats.keyRotations)
        assertEquals(320L, stats.lastLatencyMs)

        storage.resetUsageStats("openai")
        assertEquals(0L, storage.getUsageStats("openai").totalRequests)
    }

    @Test
    fun `endpoint validation enforces https unless local loopback or explicit confirmation`() {
        assertTrue(MultiProviderRouter.validateEndpointPolicy("https://api.openai.com/v1/").isValid)
        assertFalse(MultiProviderRouter.validateEndpointPolicy("https://api.openai.com/v1/").requiresHttpsWarning)

        val local = MultiProviderRouter.validateEndpointPolicy("http://127.0.0.1:11434/v1/")
        assertTrue(local.isValid)
        assertFalse(local.requiresHttpsWarning)

        val unconfirmedHttp = MultiProviderRouter.validateEndpointPolicy(
            "http://my-remote-server.example.com/v1/",
            allowInsecureHttpConfirmed = false
        )
        assertFalse(unconfirmedHttp.isValid)
        assertTrue(unconfirmedHttp.requiresHttpsWarning)

        val confirmedHttp = MultiProviderRouter.validateEndpointPolicy(
            "http://my-remote-server.example.com/v1/",
            allowInsecureHttpConfirmed = true
        )
        assertTrue(confirmedHttp.isValid)
        assertTrue(confirmedHttp.requiresHttpsWarning)
    }

    @Test
    fun `request builders and response extractors work across gemini openai and anthropic`() {
        val textReq = UnifiedAiTextRequest(
            systemPrompt = "You are NOVA.",
            userMessage = "Explain Kotlin Flow",
            history = listOf(AiChatMessageInput("user", "Hi"), AiChatMessageInput("assistant", "Hello!"))
        )

        val gemBody = MultiProviderRouter.buildGeminiTextBody(textReq)
        assertEquals(3, gemBody.getJSONArray("contents").length())
        assertTrue(gemBody.has("systemInstruction"))

        val oaBody = MultiProviderRouter.buildOpenAiBody("gpt-4o-mini", textReq)
        assertEquals("gpt-4o-mini", oaBody.getString("model"))
        assertEquals(4, oaBody.getJSONArray("messages").length())

        val claudeBody = MultiProviderRouter.buildAnthropicBody("claude-3-5-sonnet-20241022", textReq)
        assertEquals("claude-3-5-sonnet-20241022", claudeBody.getString("model"))
        assertEquals("You are NOVA.", claudeBody.getString("system"))

        val gemReply = MultiProviderRouter.extractTextFromResponse(
            AiProviderFormat.GEMINI,
            """{"candidates":[{"content":{"parts":[{"text":"Flow is cold stream."}]}}]}"""
        )
        assertEquals("Flow is cold stream.", gemReply)

        val oaReply = MultiProviderRouter.extractTextFromResponse(
            AiProviderFormat.OPENAI,
            """{"choices":[{"message":{"content":"GPT response ok"}}]}"""
        )
        assertEquals("GPT response ok", oaReply)

        val claudeReply = MultiProviderRouter.extractTextFromResponse(
            AiProviderFormat.ANTHROPIC,
            """{"content":[{"type":"text","text":"Claude response ok"}]}"""
        )
        assertEquals("Claude response ok", claudeReply)

        // Vision disabled provider check
        val visionCheck = runBlocking {
            val noVisionCtx = fakeContext()
            val customNoVision = AiProviderConfig(
                id = "text_only_test",
                name = "TextOnly",
                baseUrl = "https://example.com/v1/",
                defaultModel = "m1",
                supportsVision = false,
                isBuiltin = false
            )
            val mem = InMemorySharedPreferences()
            val sec = SecureKeyStorage(noVisionCtx, injectedSecurePrefsForTest = mem)
            sec.saveCustomProvider(customNoVision)
            sec.saveSelectedProvider("text_only_test")
            assertFalse(customNoVision.supportedCapabilities().contains(AiCapability.VISION))
            assertNotNull(customNoVision.supportedCapabilities().firstOrNull { it == AiCapability.TEXT_CHAT })
            true
        }
        assertTrue(visionCheck)
    }
}
