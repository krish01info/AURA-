package com.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.aura.R
import com.aura.agent.ActionExecutor
import com.aura.agent.AURAForegroundService
import com.aura.agent.TaskManager
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
        
        // Start the foreground service to keep the process alive
        val intent = Intent(this, AURAForegroundService::class.java)
        startService(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow
        uiObserver.update(event, rootNode)
        rootNode?.recycle()
    }

    override fun onInterrupt() {
        taskManager.emergencyStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        actionExecutor.unbind()
        // Stop foreground service when accessibility is disabled
        stopService(Intent(this, AURAForegroundService::class.java))
    }
}
