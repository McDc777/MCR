package com.mcr.pdfstudio.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Settings store. The AI key lives in an encrypted preference file; everything
 * else is ordinary preferences.
 */
class Prefs(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences("mcr_prefs", Context.MODE_PRIVATE)

    private val secure: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mcr_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }.getOrElse {
        // Some devices ship a broken keystore; degrade rather than crash on launch.
        context.getSharedPreferences("mcr_secure_fallback", Context.MODE_PRIVATE)
    }

    var aiApiKey: String
        get() = secure.getString(KEY_AI_KEY, "").orEmpty()
        set(value) = secure.edit().putString(KEY_AI_KEY, value.trim()).apply()

    var aiModel: String
        get() = plain.getString(KEY_AI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = plain.edit().putString(KEY_AI_MODEL, value).apply()

    var darkMode: Boolean
        get() = plain.getBoolean(KEY_DARK, false)
        set(value) = plain.edit().putBoolean(KEY_DARK, value).apply()

    var invertPages: Boolean
        get() = plain.getBoolean(KEY_INVERT, false)
        set(value) = plain.edit().putBoolean(KEY_INVERT, value).apply()

    val hasAiKey: Boolean get() = aiApiKey.isNotBlank()

    /** Most-recently-opened list, newest first, stored as URI strings. */
    var recents: List<String>
        get() = plain.getString(KEY_RECENTS, "")
            .orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
        set(value) = plain.edit()
            .putString(KEY_RECENTS, value.take(20).joinToString("\n"))
            .apply()

    fun pushRecent(uri: String) {
        recents = (listOf(uri) + recents.filter { it != uri })
    }

    fun removeRecent(uri: String) {
        recents = recents.filter { it != uri }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"

        val MODELS = listOf(
            "claude-opus-5" to "Opus 5 — highest quality",
            "claude-sonnet-5" to "Sonnet 5 — balanced",
            "claude-haiku-4-5-20251001" to "Haiku 4.5 — fastest",
        )

        private const val KEY_AI_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_INVERT = "invert_pages"
        private const val KEY_RECENTS = "recents"
    }
}
