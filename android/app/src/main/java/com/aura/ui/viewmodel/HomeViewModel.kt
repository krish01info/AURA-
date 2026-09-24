package com.aura.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.accessibility.AURAAccessibilityService
import com.aura.agent.TaskManager
import com.aura.agent.TaskState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/**
 * ViewModel for HomeScreen.
 * Bridges the UI with [TaskManager] and handles voice recognition.
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

    /** Checks if the AURA accessibility service is currently active. */
    val isAccessibilityEnabled: StateFlow<kotlinx.coroutines.flow.Flow<Boolean>> get() = TODO()
    // TODO: implement properly using AccessibilityManager check
    // For now, use a simpler check in the UI

    fun executeCommand(command: String) {
        if (command.isBlank()) return
        Log.i(TAG, "Executing command: $command")
        taskManager.execute(command)
    }

    fun emergencyStop() {
        taskManager.emergencyStop()
    }

    fun approveAction() {
        val state = taskState.value
        if (state is TaskState.WaitingForUser) {
            viewModelScope.launch {
                state.onAllow()
            }
        }
    }

    fun denyAction() {
        val state = taskState.value
        if (state is TaskState.WaitingForUser) {
            state.onDeny()
        }
    }

    fun reset() {
        taskManager.reset()
    }

    /**
     * Starts voice recognition using Android's built-in SpeechRecognizer.
     * Free, on-device, no API key needed.
     */
    fun startVoiceInput() {
        // TODO (Phase 4): Implement full VoiceManager with foreground service
        // For Phase 0 skeleton, this is a placeholder
        Log.i(TAG, "Voice input requested — implement in Phase 4")
    }

    /** Check if AccessibilityService is running. */
    fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(
            context.packageName + "/" + AURAAccessibilityService::class.java.name
        )
    }
}
