package com.aura.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.aura.accessibility.NodeFinder
import com.aura.accessibility.UIObserver
import com.aura.data.model.ActionStep
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ActionExecutor"

/**
 * ActionExecutor — translates [ActionStep] commands into real Android UI interactions.
 *
 * All primitive actions live here:
 *  openApp, tap, typeText, swipe, scroll, pressBack, pressHome,
 *  findAndHighlight, readText, wait
 *
 * Node lookup is delegated to [NodeFinder] — this class stays focused on
 * executing gestures and accessibility actions.
 */
@Singleton
class ActionExecutor @Inject constructor(
    private val uiObserver: UIObserver,
    private val nodeFinder: NodeFinder
) {
    private var service: AccessibilityService? = null

    fun bind(accessibilityService: AccessibilityService) {
        service = accessibilityService
        Log.i(TAG, "ActionExecutor bound to AccessibilityService")
    }

    fun unbind() {
        service = null
    }

    val isBound: Boolean get() = service != null

    fun getUiSnapshot(): String = uiObserver.getTextSnapshot()

    // ──────────────────────────────────────────────────────────────
    // Dispatch
    // ──────────────────────────────────────────────────────────────

    suspend fun execute(step: ActionStep) {
        Log.d(TAG, "Executing: ${step.action} | ${step.text ?: step.pkg ?: step.inputText ?: ""}")
        when (step.action) {
            ActionStep.OPEN_APP     -> openApp(step.pkg ?: return)
            ActionStep.TAP          -> tap(step.text, step.viewId)
            ActionStep.TYPE         -> typeText(step.inputText ?: return)
            ActionStep.SWIPE        -> swipe(step.direction ?: "up")
            ActionStep.SCROLL       -> scroll(step.direction ?: "down", step.text)
            ActionStep.BACK         -> pressBack()
            ActionStep.HOME         -> pressHome()
            ActionStep.FIND_ELEMENT -> findAndHighlight(step.text, step.viewId)
            ActionStep.WAIT         -> delay(step.waitMs ?: 1000L)
            ActionStep.CONFIRM_SEND -> { /* Handled by PolicyEngine / TaskManager gate */ }
            ActionStep.READ_TEXT    -> readText(step.text)
            else -> Log.w(TAG, "Unknown action: ${step.action}")
        }
        delay(300L) // Brief pause between steps for UI to settle
    }

    // ──────────────────────────────────────────────────────────────
    // Primitives
    // ──────────────────────────────────────────────────────────────

    /**
     * Launch any installed app by package name.
     */
    fun openApp(packageName: String) {
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

    /**
     * Tap the first node matching [text] or [viewId].
     * Walks up the tree to find a clickable ancestor if the matched node itself
     * isn't clickable.
     */
    suspend fun tap(text: String?, viewId: String?) {
        val root = service?.rootInActiveWindow ?: return logNoService()
        val node = nodeFinder.findNode(root, text, viewId)
        if (node != null) {
            val clickable = nodeFinder.findClickableAncestorOrSelf(node)
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickable.recycle()
                delay(500L)
            } else {
                Log.w(TAG, "No clickable ancestor found for tap target: text=$text id=$viewId")
            }
            node.recycle()
        } else {
            Log.w(TAG, "Tap target not found: text=$text, id=$viewId")
        }
        root.recycle()
    }

    /**
     * Type text into the currently focused input field.
     */
    suspend fun typeText(text: String) {
        val root = service?.rootInActiveWindow ?: return logNoService()
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            delay(300L)
        } else {
            Log.w(TAG, "No focused input field to type into")
        }
        root.recycle()
    }

    /**
     * Swipe in the given direction using a gesture across the screen.
     * Uses 30%–70% of the display for a reliable, consistent swipe path.
     */
    suspend fun swipe(direction: String) {
        val svc = service ?: return logNoService()
        val metrics = svc.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        val path = Path()
        when (direction.lowercase()) {
            "up"    -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
            "down"  -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "left"  -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            "right" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            else    -> { Log.w(TAG, "Unknown swipe direction: $direction"); return }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        svc.dispatchGesture(gesture, null, null)
        delay(400L)
    }

    /**
     * Scroll the first scrollable container (or the one near [targetText]).
     */
    fun scroll(direction: String, targetText: String?) {
        val root = service?.rootInActiveWindow ?: return logNoService()
        val scrollNode = if (targetText != null) {
            val target = nodeFinder.findNode(root, targetText, null)
            val parent = nodeFinder.findScrollableParent(target)
            target?.recycle()
            parent
        } else {
            nodeFinder.findFirstScrollable(root)
        }

        val action = if (direction == "down")
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

        scrollNode?.performAction(action)
        scrollNode?.recycle()
        root.recycle()
    }

    /** Press the system Back button. */
    fun pressBack() {
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /** Press the system Home button. */
    fun pressHome() {
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** Focus / highlight a UI element without clicking it. */
    fun findAndHighlight(text: String?, viewId: String?) {
        val root = service?.rootInActiveWindow ?: return logNoService()
        val node = nodeFinder.findNode(root, text, viewId)
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node.recycle()
        } else {
            Log.w(TAG, "find_element: not found text=$text id=$viewId")
        }
        root.recycle()
    }

    /** Read visible text from a specific node, or return the full UI snapshot. */
    fun readText(targetText: String?): String {
        if (targetText == null) return uiObserver.getTextSnapshot()
        val root = service?.rootInActiveWindow ?: return ""
        val node = nodeFinder.findNode(root, targetText, null)
        val text = node?.text?.toString() ?: ""
        node?.recycle()
        root.recycle()
        return text
    }

    private fun logNoService() {
        Log.e(TAG, "AccessibilityService not bound! Is the service enabled?")
    }
}
