package com.arponzxc.novaai.network.apimanager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arponzxc.novaai.data.model.GeminiConstants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Hardware-backed encrypted storage for API keys, custom AI providers, key rotation state,
 * and provider usage counters.
 *
 * SECURITY INVARIANT:
 * - Zero plaintext fallback: if Android Keystore / EncryptedSharedPreferences cannot initialize,
 *   storage fails closed (`canStoreSecurely == false`), throws explicit errors on key writes,
 *   and never writes keys into unencrypted SharedPreferences or Logcat.
 */
class SecureKeyStorage(
    private val context: Context,
    private val injectedSecurePrefsForTest: SharedPreferences? = null,
    private val forceUnavailableForTest: Boolean = false
) {
    companion object {
        private const val TAG = "SecureKeyStorage"
        private const val ENCRYPTED_PREF_NAME = "nova_secure_api_keys_v2"
        private const val LEGACY_PLAIN_FALLBACK_PREF = "nova_fallback_keys"
        private const val KEY_PREFIX_SINGLE = "key_"
        private const val KEY_PREFIX_LIST = "keys_list_"
        private const val KEY_SELECTED_PROVIDER = "selected_provider"
        private const val KEY_SELECTED_MODEL_PREFIX = "selected_model_"
        private const val KEY_SELECTED_MODEL_GLOBAL = "selected_model"
        private const val KEY_CUSTOM_PROVIDERS = "custom_providers_json"
        private const val KEY_DISABLED_BUILTINS = "disabled_builtin_providers_json"
        private const val KEY_USAGE_STATS = "provider_usage_stats_json"
    }

    private val gson = Gson()

    private val encryptedPrefs: SharedPreferences? = if (forceUnavailableForTest) {
        null
    } else {
        injectedSecurePrefsForTest ?: run {
            // Scrub any legacy unencrypted fallback prefs left from earlier builds
            runCatching {
                context.applicationContext
                    .getSharedPreferences(LEGACY_PLAIN_FALLBACK_PREF, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
            try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    ENCRYPTED_PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences unavailable; failing closed.", e)
                null
            }
        }
    }

    val canStoreSecurely: Boolean
        get() = encryptedPrefs != null

    private fun requireSecurePrefs(): SharedPreferences {
        return encryptedPrefs ?: throw IllegalStateException(
            "Android Keystore encrypted storage is unavailable on this device. API keys cannot be stored in plaintext."
        )
    }

    fun saveApiKey(providerId: String, key: String, label: String = "Primary Key") {
        val clean = key.trim()
        val prefs = requireSecurePrefs()
        if (clean.isEmpty()) {
            removeAllKeys(providerId)
            return
        }
        val existing = getApiKeyEntries(providerId).toMutableList()
        val matchIdx = existing.indexOfFirst { it.apiKey == clean || it.label == label }
        if (matchIdx >= 0) {
            existing[matchIdx] = existing[matchIdx].copy(
                apiKey = clean,
                label = label.ifBlank { "Key ${matchIdx + 1}" },
                isEnabled = true,
                cooldownUntilMs = 0L,
                failureCount = 0
            )
        } else {
            existing.add(
                0,
                ApiKeyEntry(
                    id = UUID.randomUUID().toString().take(8),
                    label = label.ifBlank { "Key ${existing.size + 1}" },
                    apiKey = clean,
                    isEnabled = true
                )
            )
        }
        saveApiKeyEntries(providerId, existing)
        prefs.edit().putString("$KEY_PREFIX_SINGLE$providerId", existing.first().apiKey).apply()
    }

    fun addApiKeyEntry(providerId: String, label: String, apiKey: String): ApiKeyEntry {
        val clean = apiKey.trim()
        require(clean.isNotBlank()) { "API key cannot be empty." }
        requireSecurePrefs()
        val list = getApiKeyEntries(providerId).toMutableList()
        val existingIdx = list.indexOfFirst { it.apiKey == clean }
        val entry = if (existingIdx >= 0) {
            list[existingIdx].copy(
                label = label.ifBlank { list[existingIdx].label },
                isEnabled = true,
                cooldownUntilMs = 0L,
                failureCount = 0
            ).also { list[existingIdx] = it }
        } else {
            ApiKeyEntry(
                id = UUID.randomUUID().toString().take(8),
                label = label.ifBlank { "Key ${list.size + 1}" },
                apiKey = clean,
                isEnabled = true
            ).also { list.add(it) }
        }
        saveApiKeyEntries(providerId, list)
        return entry
    }

    fun removeApiKeyEntry(providerId: String, keyId: String) {
        val prefs = requireSecurePrefs()
        val filtered = getApiKeyEntries(providerId).filterNot { it.id == keyId }
        saveApiKeyEntries(providerId, filtered)
        val primary = filtered.firstOrNull { it.isEnabled }?.apiKey ?: ""
        prefs.edit().putString("$KEY_PREFIX_SINGLE$providerId", primary).apply()
    }

    fun setApiKeyEnabled(providerId: String, keyId: String, enabled: Boolean) {
        requireSecurePrefs()
        val updated = getApiKeyEntries(providerId).map { entry ->
            if (entry.id == keyId) entry.copy(isEnabled = enabled, cooldownUntilMs = if (enabled) 0L else entry.cooldownUntilMs)
            else entry
        }
        saveApiKeyEntries(providerId, updated)
    }

    fun removeApiKey(providerId: String) {
        removeAllKeys(providerId)
    }

    fun removeAllKeys(providerId: String) {
        val prefs = encryptedPrefs ?: return
        prefs.edit()
            .remove("$KEY_PREFIX_SINGLE$providerId")
            .remove("$KEY_PREFIX_LIST$providerId")
            .apply()
    }

    fun getApiKey(providerId: String): String {
        val entries = getApiKeyEntries(providerId)
        val now = System.currentTimeMillis()
        val ready = entries.firstOrNull { it.isAvailableNow(now) }
            ?: entries.firstOrNull { it.isEnabled && it.apiKey.isNotBlank() }
        if (ready != null) return ready.apiKey
        return encryptedPrefs?.getString("$KEY_PREFIX_SINGLE$providerId", "")?.trim() ?: ""
    }

    fun getApiKeyEntries(providerId: String): List<ApiKeyEntry> {
        val prefs = encryptedPrefs ?: return emptyList()
        val rawJson = prefs.getString("$KEY_PREFIX_LIST$providerId", null)
        if (!rawJson.isNullOrBlank()) {
            val parsed: List<ApiKeyEntry>? = runCatching {
                val type = object : TypeToken<List<ApiKeyEntry>>() {}.type
                gson.fromJson<List<ApiKeyEntry>>(rawJson, type)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed.filter { it.apiKey.isNotBlank() }
        }
        val legacySingle = prefs.getString("$KEY_PREFIX_SINGLE$providerId", "")?.trim().orEmpty()
        if (legacySingle.isNotBlank()) {
            return listOf(
                ApiKeyEntry(
                    id = "primary_$providerId",
                    label = "Primary Key",
                    apiKey = legacySingle,
                    isEnabled = true
                )
            )
        }
        return emptyList()
    }

    private fun saveApiKeyEntries(providerId: String, entries: List<ApiKeyEntry>) {
        val prefs = requireSecurePrefs()
        prefs.edit()
            .putString("$KEY_PREFIX_LIST$providerId", gson.toJson(entries))
            .putString("$KEY_PREFIX_SINGLE$providerId", entries.firstOrNull { it.isEnabled }?.apiKey ?: "")
            .apply()
    }

    fun markKeyOutcome(providerId: String, keyId: String, success: Boolean, rateLimitedOrInvalid: Boolean = false) {
        val prefs = encryptedPrefs ?: return
        val now = System.currentTimeMillis()
        val updated = getApiKeyEntries(providerId).map { entry ->
            if (entry.id != keyId) {
                entry
            } else if (success) {
                entry.copy(lastUsedMs = now, cooldownUntilMs = 0L, failureCount = 0)
            } else {
                val nextFails = entry.failureCount + 1
                val cooldown = if (rateLimitedOrInvalid) now + 60_000L else now + 10_000L
                entry.copy(lastUsedMs = now, failureCount = nextFails, cooldownUntilMs = cooldown)
            }
        }
        prefs.edit().putString("$KEY_PREFIX_LIST$providerId", gson.toJson(updated)).apply()
    }

    fun saveSelectedProvider(providerId: String) {
        val prefs = requireSecurePrefs()
        prefs.edit().putString(KEY_SELECTED_PROVIDER, providerId).apply()
    }

    fun getSelectedProvider(): String {
        return encryptedPrefs?.getString(KEY_SELECTED_PROVIDER, "gemini") ?: "gemini"
    }

    fun saveSelectedModel(model: String, providerId: String = getSelectedProvider()) {
        val prefs = requireSecurePrefs()
        prefs.edit()
            .putString("$KEY_SELECTED_MODEL_PREFIX$providerId", model.trim())
            .putString(KEY_SELECTED_MODEL_GLOBAL, model.trim())
            .apply()
    }

    fun getSelectedModel(providerId: String = getSelectedProvider()): String {
        val prefs = encryptedPrefs ?: return defaultModelFor(providerId)
        val perProvider = prefs.getString("$KEY_SELECTED_MODEL_PREFIX$providerId", null)
        if (!perProvider.isNullOrBlank()) return perProvider
        if (providerId == "gemini") {
            return prefs.getString(KEY_SELECTED_MODEL_GLOBAL, GeminiConstants.DEFAULT_MODEL)
                ?.ifBlank { GeminiConstants.DEFAULT_MODEL } ?: GeminiConstants.DEFAULT_MODEL
        }
        return defaultModelFor(providerId)
    }

    fun getCustomProviders(): List<AiProviderConfig> {
        val prefs = encryptedPrefs ?: return emptyList()
        val raw = prefs.getString(KEY_CUSTOM_PROVIDERS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<AiProviderConfig>>() {}.type
            gson.fromJson<List<AiProviderConfig>>(raw, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun saveCustomProvider(config: AiProviderConfig) {
        val prefs = requireSecurePrefs()
        val list = getCustomProviders().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        val normalized = config.copy(isBuiltin = false)
        if (idx >= 0) {
            list[idx] = normalized
        } else {
            list.add(normalized)
        }
        prefs.edit().putString(KEY_CUSTOM_PROVIDERS, gson.toJson(list)).apply()
    }

    fun deleteCustomProvider(providerId: String) {
        val prefs = encryptedPrefs ?: return
        val remaining = getCustomProviders().filterNot { it.id == providerId }
        prefs.edit().putString(KEY_CUSTOM_PROVIDERS, gson.toJson(remaining)).apply()
        removeAllKeys(providerId)
        if (getSelectedProvider() == providerId) {
            prefs.edit().putString(KEY_SELECTED_PROVIDER, "gemini").apply()
        }
    }

    fun setBuiltinProviderEnabled(providerId: String, enabled: Boolean) {
        val prefs = requireSecurePrefs()
        val disabled = getDisabledBuiltinIds().toMutableSet()
        if (enabled) disabled.remove(providerId) else disabled.add(providerId)
        prefs.edit().putString(KEY_DISABLED_BUILTINS, gson.toJson(disabled)).apply()
    }

    fun isBuiltinProviderEnabled(providerId: String): Boolean {
        return providerId !in getDisabledBuiltinIds()
    }

    private fun getDisabledBuiltinIds(): Set<String> {
        val raw = encryptedPrefs?.getString(KEY_DISABLED_BUILTINS, null) ?: return emptySet()
        return runCatching {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(raw, type) ?: emptySet()
        }.getOrDefault(emptySet())
    }

    fun getUsageStatsMap(): Map<String, ProviderUsageStats> {
        val raw = encryptedPrefs?.getString(KEY_USAGE_STATS, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, ProviderUsageStats>>() {}.type
            gson.fromJson<Map<String, ProviderUsageStats>>(raw, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    fun getUsageStats(providerId: String): ProviderUsageStats {
        return getUsageStatsMap()[providerId] ?: ProviderUsageStats(providerId)
    }

    fun recordRequestUsage(
        providerId: String,
        success: Boolean,
        latencyMs: Long,
        rotated: Boolean = false,
        statusNote: String? = null
    ) {
        val prefs = encryptedPrefs ?: return
        val map = getUsageStatsMap().toMutableMap()
        val prev = map[providerId] ?: ProviderUsageStats(providerId)
        map[providerId] = prev.copy(
            totalRequests = prev.totalRequests + 1,
            successfulRequests = prev.successfulRequests + if (success) 1 else 0,
            failedRequests = prev.failedRequests + if (!success) 1 else 0,
            keyRotations = prev.keyRotations + if (rotated) 1 else 0,
            lastUsedMs = System.currentTimeMillis(),
            lastLatencyMs = if (latencyMs >= 0) latencyMs else prev.lastLatencyMs,
            lastStatus = statusNote ?: if (success) "OK (${latencyMs}ms)" else "Error"
        )
        prefs.edit().putString(KEY_USAGE_STATS, gson.toJson(map)).apply()
    }

    fun resetUsageStats(providerId: String? = null) {
        val prefs = encryptedPrefs ?: return
        if (providerId == null) {
            prefs.edit().remove(KEY_USAGE_STATS).apply()
        } else {
            val map = getUsageStatsMap().toMutableMap()
            map.remove(providerId)
            prefs.edit().putString(KEY_USAGE_STATS, gson.toJson(map)).apply()
        }
    }

    private fun defaultModelFor(providerId: String): String {
        return when (providerId) {
            "gemini" -> GeminiConstants.DEFAULT_MODEL
            "openai" -> "gpt-4o-mini"
            "anthropic" -> "claude-3-5-sonnet-20241022"
            else -> getCustomProviders().firstOrNull { it.id == providerId }?.defaultModel ?: ""
        }
    }
}
