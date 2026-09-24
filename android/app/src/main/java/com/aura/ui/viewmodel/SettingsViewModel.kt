package com.aura.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

private const val PREFS_FILE = "aura_secure_prefs"
private const val KEY_GROQ = "groq_api_key"
private const val KEY_VOICE = "voice_narration"

/**
 * SettingsViewModel — persists user preferences to EncryptedSharedPreferences.
 *
 * Only one API key is needed: Groq.
 * Fallback between models is handled automatically inside GroqClient.
 *
 * Keys are stored encrypted (AES-256-GCM) on the device and never transmitted
 * to any AURA server.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs: SharedPreferences by lazy {
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
    }

    // ── Exposed state ────────────────────────────────────────────────────────

    private val _groqKey = MutableStateFlow(readString(KEY_GROQ))
    val groqKey: StateFlow<String> = _groqKey

    private val _voiceNarration = MutableStateFlow(readBool(KEY_VOICE, default = false))
    val voiceNarration: StateFlow<Boolean> = _voiceNarration

    // ── Setters ──────────────────────────────────────────────────────────────

    fun setGroqKey(value: String) {
        _groqKey.value = value
        prefs.edit().putString(KEY_GROQ, value).apply()
    }

    fun setVoiceNarration(value: Boolean) {
        _voiceNarration.value = value
        prefs.edit().putBoolean(KEY_VOICE, value).apply()
    }

    // ── Helpers (used by GroqClient injection if needed) ─────────────────────

    /** Returns the stored Groq API key — used when dynamic key override is needed. */
    fun getGroqApiKey(): String = prefs.getString(KEY_GROQ, "") ?: ""

    private fun readString(key: String) = prefs.getString(key, "") ?: ""
    private fun readBool(key: String, default: Boolean) = prefs.getBoolean(key, default)
}
