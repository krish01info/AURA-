package com.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.aura.agent.ActionExecutor
import com.aura.agent.TaskManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * AURAAccessibilityService — the core of the AURA agent.
 *
 * This service is granted permission to:
 * - Read the UI tree of ANY app on the device
 * - Perform taps, swipes, text input on behalf of the user
 * - Observe navigation events
 *
 * It runs as a long-lived system service declared in AndroidManifest.xml.
 * The user must enable it at: Settings → Accessibility → AURA → Enable
 *
 * Architecture:
 *   - Delegates UI events to [UIObserver] (maintains live screen snapshot)
 *   - Delegates action execution to [ActionExecutor]
 *   - [TaskManager] orchestrates the planning + execution loop
 */
@AndroidEntryPoint
class AURAAccessibilityService : AccessibilityService() {

    @Inject lateinit var uiObserver: UIObserver
    @Inject lateinit var actionExecutor: ActionExecutor
    @Inject lateinit var taskManager: TaskManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Bind the ActionExecutor so it can call service APIs
        actionExecutor.bind(this)
    }

    /**
     * Called on every UI event from any app (window change, text change, click, etc.)
     * Updates the live UI snapshot so the planner always has fresh context.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        uiObserver.update(event, rootInActiveWindow)
    }

    /**
     * Called when the system interrupts the service (e.g., phone call).
     * Pauses task execution.
     */
    override fun onInterrupt() {
        taskManager.emergencyStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        actionExecutor.unbind()
    }
}
