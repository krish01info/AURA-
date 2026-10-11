package com.aura.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ApiKeyStore"
private const val PREFS_FILE = "aura_secure_prefs"
private const val KEY_GROQ = "groq_api_key"
private const val KEY_GEMINI = "gemini_api_key"

/**
 * ApiKeyStore — single source of the LLM API keys at runtime.
 *
 * The key typed in Settings wins; the build-time key from `local.properties`
 * (BuildConfig) is only a fallback. Release APKs from CI are built with empty
 * BuildConfig keys, so without this the app could never reach an LLM.
 *
 * Keys are stored with EncryptedSharedPreferences (AES-256-GCM, Android Keystore).
 */
@Singleton
class ApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Rare keystore failures on some devices — keep the app usable.
            Log.e(TAG, "Encrypted prefs unavailable, using private prefs: ${e.message}")
            context.getSharedPreferences("${PREFS_FILE}_fallback", Context.MODE_PRIVATE)
        }
    }

    /** Groq key: Settings value, else build-time key, else "". */
    fun groqKey(): String =
        userGroqKey().ifBlank { BuildConfig.GROQ_API_KEY.trim() }

    /** Gemini key: Settings value, else build-time key, else "". */
    fun geminiKey(): String =
        userGeminiKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }

    fun hasAnyKey(): Boolean = groqKey().isNotBlank() || geminiKey().isNotBlank()

    /** Raw values the user typed in Settings (for showing in the text fields). */
    fun userGroqKey(): String = prefs.getString(KEY_GROQ, "")?.trim() ?: ""
    fun userGeminiKey(): String = prefs.getString(KEY_GEMINI, "")?.trim() ?: ""

    fun setGroqKey(value: String) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()
    fun setGeminiKey(value: String) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    /** Shared encrypted prefs for other small settings (e.g. voice toggles). */
    fun settings(): SharedPreferences = prefs
}
