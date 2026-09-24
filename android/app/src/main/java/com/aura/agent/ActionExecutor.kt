package com.aura.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.aura.accessibility.UIObserver
import com.aura.data.model.ActionStep
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ActionExecutor"

/**
 * ActionExecutor — translates ActionStep commands into real Android UI interactions.
 *
 * Uses AccessibilityService APIs:
 * - [AccessibilityNodeInfo.performAction] for taps, type, scroll
 * - [AccessibilityService.dispatchGesture] for swipes
 * - [AccessibilityService.performGlobalAction] for back, home
 * - [AccessibilityService.startActivity] for open_app
 */
@Singleton
class ActionExecutor @Inject constructor(
    private val uiObserver: UIObserver
) {
    /** Reference to the live AccessibilityService — set on service connected. */
    private var service: AccessibilityService? = null

    fun bind(accessibilityService: AccessibilityService) {
        service = accessibilityService
        Log.i(TAG, "ActionExecutor bound to AccessibilityService")
    }

    fun unbind() {
        service = null
    }

    /** Get a sanitised snapshot of the current screen's UI tree as text. */
    fun getUiSnapshot(): String = uiObserver.getTextSnapshot()

    /**
     * Execute a single action step.
     * All actions are suspend functions — they wait for completion before returning.
     */
    suspend fun execute(step: ActionStep) {
        Log.d(TAG, "Executing: ${step.action} | ${step.text ?: step.pkg ?: step.inputText ?: ""}")
        when (step.action) {
            ActionStep.OPEN_APP    -> openApp(step.pkg ?: return)
            ActionStep.TAP         -> tap(step.text, step.viewId)
            ActionStep.TYPE        -> typeText(step.inputText ?: return)
            ActionStep.SWIPE       -> swipe(step.direction ?: "up")
            ActionStep.SCROLL      -> scroll(step.direction ?: "down", step.text)
            ActionStep.BACK        -> pressBack()
            ActionStep.HOME        -> pressHome()
            ActionStep.FIND_ELEMENT -> findAndHighlight(step.text, step.viewId)
            ActionStep.WAIT        -> delay(step.waitMs ?: 1000L)
            ActionStep.CONFIRM_SEND -> { /* Handled by PolicyEngine/TaskManager */ }
            ActionStep.READ_TEXT   -> readText(step.text)
            else -> Log.w(TAG, "Unknown action: ${step.action}")
        }
        delay(300L) // Small stabilisation pause between actions
    }

    // ──────────────────────────────────────────────────────────────
    // Action implementations
    // ──────────────────────────────────────────────────────────────

    private fun openApp(packageName: String) {
        val svc = service ?: return logNoService()
        val intent = svc.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            Log.i(TAG, "Opened app: $packageName")
        } else {
            Log.e(TAG, "App not installed: $packageName")
        }
    }

    private suspend fun tap(text: String?, viewId: String?) {
        val node = findNode(text, viewId) ?: run {
            Log.w(TAG, "Tap target not found: text=$text, id=$viewId")
            return
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        delay(500L)
    }

    private suspend fun typeText(text: String) {
        val focusedNode = service?.rootInActiveWindow?.findFocus(
            AccessibilityNodeInfo.FOCUS_INPUT
        ) ?: run {
            Log.w(TAG, "No focused input field to type into")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focusedNode.recycle()
        delay(300L)
    }

    private suspend fun swipe(direction: String) {
        val svc = service ?: return logNoService()
        val displayMetrics = svc.resources.displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()

        val path = Path()
        when (direction.lowercase()) {
            "up"    -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
            "down"  -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "left"  -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            "right" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        svc.dispatchGesture(gesture, null, null)
        delay(400L)
    }

    private fun scroll(direction: String, targetText: String?) {
        val root = service?.rootInActiveWindow ?: return logNoService()
        val scrollNode = if (targetText != null) {
            findScrollableParent(findNode(targetText, null))
        } else {
            findFirstScrollable(root)
        }
        val action = if (direction == "down")
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        scrollNode?.performAction(action)
        scrollNode?.recycle()
    }

    private fun pressBack() {
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun pressHome() {
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun findAndHighlight(text: String?, viewId: String?) {
        val node = findNode(text, viewId)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node.recycle()
        } else {
            Log.w(TAG, "find_element: not found text=$text id=$viewId")
        }
    }

    private fun readText(targetText: String?): String {
        val root = service?.rootInActiveWindow ?: return ""
        return if (targetText != null) {
            findNode(targetText, null)?.text?.toString() ?: ""
        } else {
            uiObserver.getTextSnapshot()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helper: node finders
    // ──────────────────────────────────────────────────────────────

    private fun findNode(text: String?, viewId: String?): AccessibilityNodeInfo? {
        val root = service?.rootInActiveWindow ?: return null
        return when {
            viewId != null -> root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
            text != null   -> root.findAccessibilityNodeInfosByText(text).firstOrNull()
            else           -> null
        }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val result = findFirstScrollable(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun findScrollableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node?.parent
        while (current != null) {
            if (current.isScrollable) return current
            current = current.parent
        }
        return null
    }

    private fun logNoService(): Unit = Log.e(TAG, "AccessibilityService not bound!")
}
