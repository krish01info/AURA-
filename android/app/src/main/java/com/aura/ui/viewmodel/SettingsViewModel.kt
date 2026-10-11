package com.aura.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.data.model.UpdateInfo
import com.aura.llm.ApiKeyStore
import com.aura.voice.VoicePrefs
import com.aura.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val KEY_VOICE  = "voice_narration"

/**
 * SettingsViewModel — persists user preferences and handles update checks.
 *
 * API keys are stored encrypted (AES-256-GCM) via [ApiKeyStore], which the LLM
 * clients read on every request — a new key works immediately.
 * Keys are never transmitted to any AURA server.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateRepository: UpdateRepository,
    private val apiKeyStore: ApiKeyStore
) : ViewModel() {

    private val prefs: SharedPreferences get() = apiKeyStore.settings()

    // ── Preferences ───────────────────────────────────────────────────────────

    private val _groqKey = MutableStateFlow(apiKeyStore.userGroqKey())
    val groqKey: StateFlow<String> = _groqKey

    private val _geminiKey = MutableStateFlow(apiKeyStore.userGeminiKey())
    val geminiKey: StateFlow<String> = _geminiKey

    private val _voiceNarration = MutableStateFlow(readBool(KEY_VOICE, default = false))
    val voiceNarration: StateFlow<Boolean> = _voiceNarration

    private val _speechLanguage = MutableStateFlow(VoicePrefs.speechLanguage(context))
    val speechLanguage: StateFlow<String> = _speechLanguage

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
        apiKeyStore.setGroqKey(value)
    }

    fun setGeminiKey(value: String) {
        _geminiKey.value = value
        apiKeyStore.setGeminiKey(value)
    }

    /** "" = phone default; e.g. "en-IN", "hi-IN". Used by the next recognition. */
    fun setSpeechLanguage(tag: String) {
        _speechLanguage.value = tag
        VoicePrefs.setSpeechLanguage(context, tag)
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

    fun getGroqApiKey(): String = apiKeyStore.groqKey()
    private fun readBool(key: String, default: Boolean) = prefs.getBoolean(key, default)
}
