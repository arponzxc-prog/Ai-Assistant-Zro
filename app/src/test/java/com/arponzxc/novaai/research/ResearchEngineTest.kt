package com.arponzxc.novaai.research

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchEngineTest {

    private class MemPrefs : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val edits = linkedMapOf<String, Any?>()
            private val removed = linkedSetOf<String>()
            private var clear = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) edits[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) edits[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) edits[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) edits[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) edits[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) edits[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removed.add(key) }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) map.clear()
                removed.forEach { map.remove(it) }
                edits.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun fakeContext(): Context {
        val mem = MemPrefs()
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = mem
        }
    }

    @Test
    fun `deduplicate and credibility scoring ranks trusted domains higher`() {
        val raw = listOf(
            SourceItem("General Blog", "https://randomblog.example.com/post", "Blog snippet", "DuckDuckGo Knowledge"),
            SourceItem("Kotlin Coroutines", "https://en.wikipedia.org/?curid=101", "Official overview", "Wikipedia"),
            SourceItem("Kotlin Coroutines", "https://en.wikipedia.org/?curid=101/", "Duplicate URL", "Wikipedia")
        )
        val ranked = ResearchEngine.deduplicateAndRankSources(raw)
        assertEquals(2, ranked.size)
        assertEquals(SourceCredibility.HIGH, ranked.first().credibility)
        assertEquals("en.wikipedia.org", ranked.first().domain)
    }

    @Test
    fun `offline or failing providers fail honestly without fabricating fake sources`() = runBlocking {
        val failingProvider = object : SearchProvider {
            override val providerName: String = "Wikipedia"
            override suspend fun search(query: String, limit: Int) = ResearchProviderOutput(
                providerName = providerName,
                success = false,
                errorMessage = "No internet connection"
            )
        }
        val engine = ResearchEngine(
            context = fakeContext(),
            providers = listOf(failingProvider),
            aiSynthesizer = { _, _, _, _ -> false to "Should not synthesize offline" }
        )

        val result = engine.performResearch("Quantum Computing", ResearchMode.DETAILED.label, saveHistory = true)
        assertFalse(result.success)
        assertTrue(result.sources.isEmpty())
        assertTrue(result.errorMessage.contains("offline", ignoreCase = true) || result.errorMessage.contains("unreachable", ignoreCase = true))
        assertFalse(result.summary.contains("ai.google.dev"))
        assertTrue(engine.getHistory().isEmpty())
    }

    @Test
    fun `multi source aggregation synthesis fallback and history CRUD work`() = runBlocking {
        val p1 = object : SearchProvider {
            override val providerName: String = "Wikipedia"
            override suspend fun search(query: String, limit: Int) = ResearchProviderOutput(
                providerName = providerName,
                success = true,
                items = listOf(
                    SourceItem("Jetpack Compose", "https://en.wikipedia.org/?curid=99", "Declarative UI toolkit for Android.", "Wikipedia")
                )
            )
        }
        val p2 = object : SearchProvider {
            override val providerName: String = "Wikidata"
            override suspend fun search(query: String, limit: Int) = ResearchProviderOutput(
                providerName = providerName,
                success = true,
                items = listOf(
                    SourceItem("Jetpack Compose", "https://www.wikidata.org/wiki/Q99", "Duplicate title filtered.", "Wikidata"),
                    SourceItem("Material Design 3", "https://developer.android.com/jetpack/compose/designsystems/material3", "M3 design system for Compose.", "Wikidata")
                )
            )
        }

        val engineWithAi = ResearchEngine(
            context = fakeContext(),
            providers = listOf(p1, p2),
            aiSynthesizer = { _, query, mode, sources ->
                true to "Synthesized (${mode.label}) answer for $query using ${sources.size} sources [1][2]."
            }
        )

        val res = engineWithAi.performResearch("Jetpack Compose", ResearchMode.TECHNICAL.label, saveHistory = true)
        assertTrue(res.success)
        assertTrue(res.aiSynthesized)
        assertEquals(2, res.sources.size)
        assertTrue(res.summary.contains("Technical"))
        assertEquals(1, engineWithAi.getHistory().size)

        val savedTs = engineWithAi.getHistory().first().timestamp
        engineWithAi.deleteHistoryEntry(savedTs)
        assertEquals(0, engineWithAi.getHistory().size)

        // When AI key is unavailable, excerpts are still shown honestly
        val engineNoAi = ResearchEngine(
            context = fakeContext(),
            providers = listOf(p1),
            aiSynthesizer = { _, _, _, _ -> false to "No API key configured" }
        )
        val noAiRes = engineNoAi.performResearch("Jetpack Compose", ResearchMode.QUICK.label, saveHistory = true)
        assertTrue(noAiRes.success)
        assertFalse(noAiRes.aiSynthesized)
        assertTrue(noAiRes.summary.contains("AI Synthesis Unavailable"))
        assertTrue(noAiRes.summary.contains("Jetpack Compose"))
        engineNoAi.clearHistory()
        assertTrue(engineNoAi.getHistory().isEmpty())
    }
}
