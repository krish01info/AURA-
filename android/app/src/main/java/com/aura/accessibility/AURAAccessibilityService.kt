package com.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.aura.R
import com.aura.agent.ActionExecutor
import com.aura.agent.TaskManager
import com.aura.voice.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * AURAAccessibilityService — the core of the AURA agent.
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

        // Android reconnects this service after a reboot, so resume "Hey AURA" here
        // if the user left voice mode on. (If Android refuses to start a microphone
        // service from the background, it resumes next time AURA is opened.)
        if (WakeWordService.isWakeEnabled(this)) {
            WakeWordService.startWake(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow
        uiObserver.update(event, rootNode)
        rootNode?.recycle()
    }

    override fun onInterrupt() {
        taskManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        actionExecutor.unbind()
    }
}
