package com.aura.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.data.model.UpdateInfo
import com.aura.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PREFS_FILE = "aura_secure_prefs"
private const val KEY_GROQ   = "groq_api_key"
private const val KEY_VOICE  = "voice_narration"

/**
 * SettingsViewModel — persists user preferences and handles update checks.
 *
 * API keys are stored encrypted (AES-256-GCM) via EncryptedSharedPreferences.
 * Keys are never transmitted to any AURA server.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository
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

    // ── Preferences ───────────────────────────────────────────────────────────

    private val _groqKey = MutableStateFlow(readString(KEY_GROQ))
    val groqKey: StateFlow<String> = _groqKey

    private val _voiceNarration = MutableStateFlow(readBool(KEY_VOICE, default = false))
    val voiceNarration: StateFlow<Boolean> = _voiceNarration

    // ── Update state ──────────────────────────────────────────────────────────

    val updateInfo: StateFlow<UpdateInfo?> = updateRepository.updateInfo.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val isCheckingUpdate: StateFlow<Boolean> = updateRepository.isChecking.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setGroqKey(value: String) {
        _groqKey.value = value
        prefs.edit().putString(KEY_GROQ, value).apply()
    }

    fun setVoiceNarration(value: Boolean) {
        _voiceNarration.value = value
        prefs.edit().putBoolean(KEY_VOICE, value).apply()
    }

    // ── Update actions ────────────────────────────────────────────────────────

    /** User tapped "Check for updates" — runs immediately on IO. */
    fun checkForUpdates() {
        viewModelScope.launch { updateRepository.checkNow() }
    }

    /** User tapped "Install Update" — triggers download + installer. */
    fun installUpdate() = updateRepository.installUpdate()

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun getGroqApiKey(): String = prefs.getString(KEY_GROQ, "") ?: ""
    private fun readString(key: String) = prefs.getString(key, "") ?: ""
    private fun readBool(key: String, default: Boolean) = prefs.getBoolean(key, default)
}
