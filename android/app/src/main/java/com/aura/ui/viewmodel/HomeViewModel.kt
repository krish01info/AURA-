package com.aura.ui.viewmodel

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.accessibility.AURAAccessibilityService
import com.aura.agent.TaskManager
import com.aura.agent.TaskState
import com.aura.data.model.ActionStep
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
 * Bridges UI with [TaskManager] and handles voice recognition.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskManager: TaskManager
) : ViewModel() {

    val taskState: StateFlow<TaskState> = taskManager.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TaskState.Created
    )

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    init {
        pollAccessibilityStatus()
    }

    private fun pollAccessibilityStatus() {
        viewModelScope.launch {
            while (true) {
                _isAccessibilityEnabled.value = isAccessibilityServiceEnabled()
                delay(2000)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Task execution
    // ──────────────────────────────────────────────────────────────

    /** Execute a natural language command — goes through Groq LLM planner. */
    fun executeCommand(command: String) {
        if (command.isBlank()) return
        Log.i(TAG, "Executing command: $command")
        taskManager.execute(command)
    }

    /**
     * Phase 1 hard-coded test: "Open WhatsApp"
     *
     * This bypasses the LLM entirely and runs a pre-built action plan directly.
     * It proves the AccessibilityService, ActionExecutor, and TaskManager
     * state machine all work end-to-end — before any API key is needed.
     *
     * Remove or gate behind a debug flag once Phase 2 is complete.
     */
    fun runWhatsAppTest() {
        Log.i(TAG, "Phase 1 test: Open WhatsApp (direct steps, no LLM)")
        val steps = listOf(
            ActionStep(
                action = ActionStep.OPEN_APP,
                pkg = "com.whatsapp"
            )
        )
        taskManager.executeDirectSteps(
            label = "Test: Open WhatsApp",
            steps = steps
        )
    }

    /**
     * Phase 1 extended test: Full "Open WhatsApp → find Rahul → type → confirm send"
     * Demonstrates the complete execution loop including the confirmation gate.
     *
     * Replace "Rahul" with an actual contact name on the test device.
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
        taskManager.executeDirectSteps(
            label = "Test: WhatsApp → $contact",
            steps = steps
        )
    }

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

    /** Phase 4: integrate VoiceManager with foreground service for full wake-word + STT. */
    fun startVoiceInput() {
        Log.i(TAG, "Voice input requested — implement in Phase 4")
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${context.packageName}/${AURAAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(expected)
    }
}
