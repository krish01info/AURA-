package com.aura.ui.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.aura.voice.VoskEngine
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
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel for [HomeScreen].
 * Bridges UI with [TaskManager], [VoiceManager], [BiometricGate], and [FloatingBubble].
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskManager: TaskManager,
    private val voiceManager: VoiceManager,
    private val voskEngine: VoskEngine,
    private val biometricGate: BiometricGate,
    private val floatingBubble: FloatingBubble
) : ViewModel() {

    val taskState: StateFlow<TaskState> = taskManager.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TaskState.Created
    )

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isVoiceEnabled = MutableStateFlow(false)
    val isVoiceEnabled: StateFlow<Boolean> = _isVoiceEnabled.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // Wake word broadcast receiver
    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WakeWordService.ACTION_WAKE_WORD_DETECTED -> {
                    Log.i(TAG, "Wake word detected — starting voice input")
                    startVoiceInput()
                }
                WakeWordService.ACTION_STOP_COMMAND -> {
                    Log.i(TAG, "Stop command received via voice")
                    emergencyStop()
                }
            }
        }
    }

    init {
        pollAccessibilityStatus()
        registerWakeWordReceiver()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(wakeWordReceiver)
        } catch (_: Exception) { }
        floatingBubble.hide()
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
     * Start voice input. Tries online SpeechRecognizer first;
     * falls back to VoskEngine (offline) if device has no internet.
     */
    fun startVoiceInput() {
        viewModelScope.launch {
            val transcript = if (isOnline()) {
                voiceManager.recognizeSpeech()
            } else {
                Log.i(TAG, "Offline — trying Vosk STT")
                voskEngine.recognizeSpeech() ?: voiceManager.recognizeSpeech()
            }

            if (transcript != null) {
                Log.i(TAG, "Voice command: \"$transcript\"")
                executeCommand(transcript)
            } else {
                voiceManager.speak("Sorry, I didn't catch that.")
                _statusMessage.value = "Didn't catch that — try again"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Wake word service (Phase 4)
    // ──────────────────────────────────────────────────────────────

    /** Start/stop the WakeWordService and the floating bubble. */
    fun setVoiceEnabled(enabled: Boolean) {
        _isVoiceEnabled.value = enabled
        if (enabled) {
            context.startForegroundService(WakeWordService.startIntent(context))
            if (floatingBubble.canShow()) {
                floatingBubble.show(onTap = { startVoiceInput() })
            }
            voiceManager.speak("Voice mode on. Say Hey AURA to activate me.")
        } else {
            context.stopService(WakeWordService.startIntent(context))
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

    private fun registerWakeWordReceiver() {
        val filter = IntentFilter().apply {
            addAction(WakeWordService.ACTION_WAKE_WORD_DETECTED)
            addAction(WakeWordService.ACTION_STOP_COMMAND)
        }
        try {
            context.registerReceiver(wakeWordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register wake word receiver: ${e.message}")
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

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
