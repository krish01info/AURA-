package com.aura.ui.viewmodel

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.accessibility.AURAAccessibilityService
import com.aura.agent.RiskLevel
import com.aura.agent.TaskManager
import com.aura.agent.TaskState
import com.aura.data.model.ActionStep
import com.aura.policy.BiometricGate
import com.aura.voice.FloatingBubble
import com.aura.voice.VoiceManager
import com.aura.voice.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.aura.voice.KeywordSpotter
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel for [HomeScreen].
 * Bridges UI with [TaskManager], [VoiceManager], [BiometricGate], and [FloatingBubble].
 *
 * All voice input goes through [WakeWordService] (the assistant service), so the
 * in-app mic button, the floating bubble and "Hey AURA" share one listening path
 * and the same popup.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskManager: TaskManager,
    private val voiceManager: VoiceManager,
    private val biometricGate: BiometricGate,
    private val floatingBubble: FloatingBubble,
    private val keywordSpotter: KeywordSpotter
) : ViewModel() {

    // ── Offline voice model (live tasks), offered during first-time setup ──
    val voiceModelState: StateFlow<KeywordSpotter.ModelState> = keywordSpotter.modelState

    private val setupPrefs = context.getSharedPreferences("aura_setup", Context.MODE_PRIVATE)
    private val _voiceModelOfferDismissed = MutableStateFlow(setupPrefs.getBoolean(PREF_MODEL_OFFER_DISMISSED, false))
    val voiceModelOfferDismissed: StateFlow<Boolean> = _voiceModelOfferDismissed

    /** Download the ~36 MB offline voice model now, so the first live task starts at once. */
    fun downloadVoiceModel() {
        viewModelScope.launch { keywordSpotter.ensureModel() }
    }

    /** "Not now": hide the offer; the model still downloads on the first live task. */
    fun dismissVoiceModelOffer() {
        setupPrefs.edit().putBoolean(PREF_MODEL_OFFER_DISMISSED, true).apply()
        _voiceModelOfferDismissed.value = true
    }

    val taskState: StateFlow<TaskState> = taskManager.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TaskState.Created
    )

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isVoiceEnabled = MutableStateFlow(WakeWordService.isWakeEnabled(context))
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        pollAccessibilityStatus()
        // Voice mode was left on last time — resume listening for "Hey AURA".
        // (Started here because the app is in the foreground, which Android requires
        // for a microphone service.)
        if (_isVoiceEnabled.value) {
            WakeWordService.startWake(context)
            if (floatingBubble.canShow()) floatingBubble.show(onTap = { startVoiceInput() })
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The bubble and the assistant service keep running in the background on purpose.
    }

    // ──────────────────────────────────────────────────────────────
    // Task execution
    // ──────────────────────────────────────────────────────────────

    /** Execute a natural language command — goes through Groq LLM planner. */
    fun executeCommand(command: String) {
        if (command.isBlank()) return
        Log.i(TAG, "Executing command: $command")
        voiceManager.speak("Got it. $command")
        taskManager.execute(command)
    }

    /**
     * Phase 1 hard-coded test: "Open WhatsApp"
     */
    fun runWhatsAppTest() {
        Log.i(TAG, "Phase 1 test: Open WhatsApp (direct steps, no LLM)")
        val steps = listOf(
            ActionStep(action = ActionStep.OPEN_APP, pkg = "com.whatsapp")
        )
        taskManager.executeDirectSteps(label = "Test: Open WhatsApp", steps = steps)
    }

    /**
     * Phase 1 extended test: Full WhatsApp message flow.
     */
    fun runWhatsAppMessageTest(contact: String = "Rahul", message: String = "I am on my way!") {
        Log.i(TAG, "Phase 1 test: WhatsApp message to $contact")
        val steps = listOf(
            ActionStep(action = ActionStep.OPEN_APP, pkg = "com.whatsapp"),
            ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
            ActionStep(action = ActionStep.FIND_ELEMENT, text = contact),
            ActionStep(action = ActionStep.TAP, text = contact),
            ActionStep(action = ActionStep.WAIT, waitMs = 1500L),
            ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/entry"),
            ActionStep(action = ActionStep.TYPE, inputText = message),
            ActionStep(
                action = ActionStep.CONFIRM_SEND,
                message = "Send \"$message\" to $contact on WhatsApp?",
                risk = "HIGH"
            ),
            ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/send"),
        )
        taskManager.executeDirectSteps(label = "Test: WhatsApp → $contact", steps = steps)
    }

    // ──────────────────────────────────────────────────────────────
    // Voice input (Phase 4)
    // ──────────────────────────────────────────────────────────────

    /**
     * Listen for one spoken command. The assistant service shows the popup,
     * captures the command and runs it — same flow as saying "Hey AURA".
     */
    fun startVoiceInput() {
        if (!WakeWordService.listenNow(context)) {
            _statusMessage.value = "Couldn't start the microphone — check AURA's permissions"
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Wake word service (Phase 4)
    // ──────────────────────────────────────────────────────────────

    /** Turn "Hey AURA" always-listening (and the floating bubble) on or off. Remembered. */
    fun setVoiceEnabled(enabled: Boolean) {
        _isVoiceEnabled.value = enabled
        if (enabled) {
            if (!WakeWordService.startWake(context)) {
                _statusMessage.value = "Couldn't start voice mode — check AURA's permissions"
            }
            if (floatingBubble.canShow()) {
                floatingBubble.show(onTap = { startVoiceInput() })
            }
            voiceManager.speak("Voice mode on. Say Hey AURA from any app.")
        } else {
            WakeWordService.turnOff(context)
            floatingBubble.hide()
            voiceManager.speak("Voice mode off.")
        }
    }

    /** Request SYSTEM_ALERT_WINDOW permission for the floating bubble. */
    fun requestOverlayPermission() {
        floatingBubble.requestPermission(context)
    }

    val canShowOverlay: Boolean get() = floatingBubble.canShow()

    // ──────────────────────────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────────────────────────

    fun emergencyStop() = taskManager.emergencyStop()
    fun reset() = taskManager.reset()

    fun approveAction() {
        val state = taskState.value
        if (state is TaskState.WaitingForUser) {
            viewModelScope.launch { state.onAllow() }
        }
    }

    fun denyAction() {
        val state = taskState.value
        if (state is TaskState.WaitingForUser) {
            state.onDeny()
        }
    }

    /**
     * For CRITICAL risk actions — launch BiometricPrompt before allowing.
     * The activity reference is needed for FragmentActivity.
     */
    fun approveWithBiometric(activity: FragmentActivity) {
        val state = taskState.value as? TaskState.WaitingForUser ?: return
        if (state.riskLevel != RiskLevel.CRITICAL) {
            approveAction()
            return
        }
        viewModelScope.launch {
            val title = "Confirm Critical Action"
            val subtitle = state.riskAction.message ?: "This action cannot be undone"
            val authenticated = biometricGate.authenticate(activity, title, subtitle)
            if (authenticated) {
                state.onAllow()
            } else {
                voiceManager.speak("Biometric authentication failed. Action denied.")
                state.onDeny()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun pollAccessibilityStatus() {
        viewModelScope.launch {
            while (true) {
                _isAccessibilityEnabled.value = isAccessibilityServiceEnabled()
                delay(2000)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${context.packageName}/${AURAAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(expected)
    }

    private companion object {
        const val PREF_MODEL_OFFER_DISMISSED = "voice_model_offer_dismissed"
    }
}
