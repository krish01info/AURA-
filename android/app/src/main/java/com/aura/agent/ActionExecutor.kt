package com.aura.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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

/** How long to keep looking for a node while a screen is still loading. */
private const val FIND_TIMEOUT_MS = 4000L
private const val FIND_POLL_MS = 400L

/** Result of one step. [error] is a short, user-readable reason when [ok] is false. */
data class StepResult(val ok: Boolean, val error: String? = null, val output: String? = null) {
    companion object {
        val OK = StepResult(true)
        fun fail(reason: String) = StepResult(false, reason)
    }
}

/**
 * ActionExecutor — translates [ActionStep] commands into real Android UI interactions.
 *
 * Every primitive returns a [StepResult], so TaskManager can stop and tell the user
 * what went wrong instead of reporting success after a missed tap.
 *
 * Node lookup is delegated to [NodeFinder]; lookups retry for up to
 * [FIND_TIMEOUT_MS] because the target app is often still loading.
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

    /** The connected AccessibilityService (used for accessibility overlays), or null. */
    val boundService: AccessibilityService? get() = service

    fun getUiSnapshot(): String = uiObserver.getTextSnapshot()

    // ──────────────────────────────────────────────────────────────
    // Dispatch
    // ──────────────────────────────────────────────────────────────

    suspend fun execute(step: ActionStep): StepResult {
        Log.d(TAG, "Executing: ${step.action} | ${step.text ?: step.pkg ?: step.inputText ?: ""}")
        if (service == null && step.action !in NO_SERVICE_NEEDED) {
            return StepResult.fail("AURA's Accessibility service is off. Turn it on in Settings → Accessibility.")
        }
        val result = try {
            when (step.action) {
                ActionStep.OPEN_APP     -> openApp(step.pkg, step.text)
                ActionStep.TAP          -> tap(step.text, step.viewId)
                ActionStep.TYPE         -> typeText(step.inputText ?: step.text)
                ActionStep.SWIPE        -> swipe(step.direction ?: "up")
                ActionStep.SCROLL       -> scroll(step.direction ?: "down", step.text)
                ActionStep.BACK         -> global(AccessibilityService.GLOBAL_ACTION_BACK)
                ActionStep.HOME         -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                ActionStep.FIND_ELEMENT -> findElement(step.text, step.viewId)
                ActionStep.WAIT         -> { delay((step.waitMs ?: 1000L).coerceIn(0L, 10_000L)); StepResult.OK }
                ActionStep.CONFIRM_SEND -> StepResult.OK // gate handled by TaskManager
                ActionStep.READ_TEXT    -> StepResult(true, output = readText(step.text))
                else -> StepResult.fail("I don't know how to do \"${step.action}\" yet.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step ${step.action} crashed: ${e.message}", e)
            StepResult.fail("Something went wrong while doing ${step.action.replace('_', ' ')}.")
        }
        if (!result.ok) Log.w(TAG, "Step failed: ${step.action} → ${result.error}")
        delay(300L) // Brief pause between steps for UI to settle
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // Primitives
    // ──────────────────────────────────────────────────────────────

    /**
     * Launch an app. Tries [pkg] as a package name first; if that isn't installed
     * (LLMs often guess package names), matches [pkg] or [appName] against the
     * labels of installed launcher apps ("YouTube", "WhatsApp", …).
     */
    fun openApp(pkg: String?, appName: String?): StepResult {
        val svc = service ?: return noService()
        val pm = svc.packageManager

        val launch = pkg?.takeIf { it.isNotBlank() }?.let { pm.getLaunchIntentForPackage(it.trim()) }
            ?: findLaunchIntentByLabel(svc, listOfNotNull(appName, pkg))
            ?: return StepResult.fail("I couldn't find the app ${appName ?: pkg ?: ""} on this phone.")

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        svc.startActivity(launch)
        Log.i(TAG, "Opened app: ${launch.component?.packageName}")
        return StepResult.OK
    }

    private fun findLaunchIntentByLabel(svc: AccessibilityService, names: List<String>): Intent? {
        val pm = svc.packageManager
        val wanted = names
            .map { it.substringAfterLast('.').lowercase().replace(" ", "") }
            .filter { it.length >= 2 }
        if (wanted.isEmpty()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
        val labelled = apps.map { it to it.loadLabel(pm).toString().lowercase().replace(" ", "") }

        val match = labelled.firstOrNull { (_, label) -> label in wanted }
            ?: labelled.firstOrNull { (_, label) -> wanted.any { label.contains(it) || it.contains(label) } }
            ?: return null
        return pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
    }

    /**
     * Tap the first node matching [text] or [viewId], walking up to a clickable
     * ancestor if needed. Retries while the screen loads.
     */
    suspend fun tap(text: String?, viewId: String?): StepResult {
        if (text.isNullOrBlank() && viewId.isNullOrBlank()) return StepResult.fail("Tap step had no target.")
        val node = waitForNode(text, viewId)
            ?: return StepResult.fail("I couldn't find \"${text ?: viewId}\" on the screen.")
        val clickable = nodeFinder.findClickableAncestorOrSelf(node)
        val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (!clicked) {
            // Fall back to a real tap gesture at the node's position.
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (!bounds.isEmpty && tapAt(bounds.exactCenterX(), bounds.exactCenterY())) {
                delay(500L)
                return StepResult.OK
            }
            return StepResult.fail("I found \"${text ?: viewId}\" but couldn't tap it.")
        }
        delay(500L)
        return StepResult.OK
    }

    /**
     * Type into the focused input field, or the first editable field on screen.
     */
    suspend fun typeText(text: String?): StepResult {
        if (text.isNullOrEmpty()) return StepResult.fail("Type step had no text.")
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        while (true) {
            val root = service?.rootInActiveWindow ?: return noService()
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                ?: findFirstEditable(root)
            if (target != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(300L)
                return if (ok) StepResult.OK else StepResult.fail("I couldn't type into the text box.")
            }
            if (System.currentTimeMillis() > deadline) {
                return StepResult.fail("There's no text box on the screen to type into.")
            }
            delay(FIND_POLL_MS)
        }
    }

    /** Swipe in the given direction using a gesture across 30%–70% of the display. */
    suspend fun swipe(direction: String): StepResult {
        val svc = service ?: return noService()
        val metrics = svc.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        val path = Path()
        when (direction.lowercase()) {
            "up"    -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
            "down"  -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "left"  -> { path.moveTo(w * 0.8f, h / 2); path.lineTo(w * 0.2f, h / 2) }
            "right" -> { path.moveTo(w * 0.2f, h / 2); path.lineTo(w * 0.8f, h / 2) }
            else    -> return StepResult.fail("Unknown swipe direction: $direction")
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        val sent = svc.dispatchGesture(gesture, null, null)
        delay(400L)
        return if (sent) StepResult.OK else StepResult.fail("The swipe gesture was blocked.")
    }

    /** Scroll the first scrollable container (or the one near [targetText]). */
    suspend fun scroll(direction: String, targetText: String?): StepResult {
        val root = service?.rootInActiveWindow ?: return noService()
        val scrollNode = if (!targetText.isNullOrBlank()) {
            val target = nodeFinder.findNode(root, targetText, null)
            nodeFinder.findScrollableParent(target)
        } else {
            nodeFinder.findFirstScrollable(root)
        }
        val action = if (direction.lowercase() == "up")
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

        if (scrollNode?.performAction(action) == true) {
            delay(300L)
            return StepResult.OK
        }
        // No scrollable node exposed — use a swipe gesture instead.
        return swipe(if (direction.lowercase() == "up") "down" else "up")
    }

    private fun global(action: Int): StepResult {
        val svc = service ?: return noService()
        return if (svc.performGlobalAction(action)) StepResult.OK
        else StepResult.fail("The system button press was blocked.")
    }

    /** Check that an element is on screen (waits while the screen loads). */
    suspend fun findElement(text: String?, viewId: String?): StepResult {
        val node = waitForNode(text, viewId)
            ?: return StepResult.fail("I couldn't find \"${text ?: viewId}\" on the screen.")
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        return StepResult.OK
    }

    /** Read visible text from a specific node, or return the full UI snapshot. */
    fun readText(targetText: String?): String {
        if (targetText.isNullOrBlank()) return uiObserver.getTextSnapshot()
        val root = service?.rootInActiveWindow ?: return ""
        val node = nodeFinder.findNode(root, targetText, null) ?: return ""
        return node.text?.toString() ?: node.contentDescription?.toString() ?: ""
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    /** Poll the active window for a node until it appears or the timeout passes. */
    private suspend fun waitForNode(text: String?, viewId: String?): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        while (true) {
            val root = service?.rootInActiveWindow
            if (root != null) {
                nodeFinder.findNode(root, text, viewId)?.let { return it }
            }
            if (System.currentTimeMillis() > deadline) return null
            delay(FIND_POLL_MS)
        }
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val sent = svc.dispatchGesture(gesture, null, null)
        delay(200L)
        return sent
    }

    private fun noService(): StepResult {
        Log.e(TAG, "AccessibilityService not bound! Is the service enabled?")
        return StepResult.fail("AURA's Accessibility service is off. Turn it on in Settings → Accessibility.")
    }

    private companion object {
        val NO_SERVICE_NEEDED = setOf(ActionStep.WAIT, ActionStep.CONFIRM_SEND)
    }
}
