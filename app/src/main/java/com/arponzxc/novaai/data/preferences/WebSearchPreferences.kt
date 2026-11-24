package com.arponzxc.novaai.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the optional "Free Web Search" API key (Tavily / Serper / Exa).
 * One key is stored per provider so switching providers never loses a saved key.
 */
class WebSearchPreferences(context: Context) {

    companion object {
        private const val TAG = "WebSearchPreferences"
        private const val ENCRYPTED_FILE = "nova_web_search_secure_prefs"
        private const val KEY_SELECTED = "selected_provider"
        private const val KEY_PREFIX = "api_key_"
    }

    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted web-search key storage could not be initialized", e)
        null
    }

    val isSecureStorageAvailable: Boolean get() = securePrefs != null

    /** Provider id (see WebSearchProviderType.id) the assistant should use. */
    var selectedProviderId: String
        get() = securePrefs?.getString(KEY_SELECTED, "") ?: ""
        set(value) {
            securePrefs?.edit()?.putString(KEY_SELECTED, value)?.apply()
        }

    fun getKey(providerId: String): String =
        securePrefs?.getString(KEY_PREFIX + providerId, "")?.trim().orEmpty()

    fun setKey(providerId: String, value: String) {
        val secure = checkNotNull(securePrefs) { "Secure web-search key storage is unavailable on this device." }
        val clean = value.trim()
        val saved = if (clean.isEmpty()) secure.edit().remove(KEY_PREFIX + providerId).commit()
        else secure.edit().putString(KEY_PREFIX + providerId, clean).commit()
        check(saved) { "Could not save the web-search API key to encrypted storage." }
    }

    /** The provider to use right now: the selected one if it has a key, otherwise any provider with a key. */
    fun activeProviderIdOrNull(allIds: List<String>): String? {
        val selected = selectedProviderId
        if (selected.isNotEmpty() && getKey(selected).isNotEmpty()) return selected
        return allIds.firstOrNull { getKey(it).isNotEmpty() }
    }
}
