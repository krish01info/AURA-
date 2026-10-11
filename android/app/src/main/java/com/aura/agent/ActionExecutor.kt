package com.aura.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.aura.accessibility.NodeFinder
import com.aura.accessibility.UIObserver
import com.aura.data.model.ActionStep
import com.aura.notifications.NotificationStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val appContext: Context,
    private val uiObserver: UIObserver,
    private val nodeFinder: NodeFinder,
    private val notifications: NotificationStore
) {
    private var service: AccessibilityService? = null

    /** Last app AURA launched: what the plan asked for → what actually opened. */
    private var lastLaunch: Pair<String?, String?> = null to null

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

    /**
     * Called with true right before AURA performs a gesture and false after, so the
     * assistant banner can let the touch through instead of catching it.
     */
    @Volatile var gestureListener: ((Boolean) -> Unit)? = null

    private suspend fun <T> touchThrough(block: suspend () -> T): T {
        val listener = gestureListener ?: return block()
        listener(true)
        delay(TOUCH_THROUGH_SETTLE_MS) // let the banner's window flags update
        try { return block() } finally { listener(false) }
    }

    /**
     * True when the phone is locked or an app lock (fingerprint / PIN / pattern prompt)
     * is on screen. Looks at every window, because the fingerprint prompt is often a
     * system window above the app. AURA's own windows are skipped.
     */
    fun lockScreenInFront(): Boolean {
        val km = appContext.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return true
        val svc = service ?: return false
        val roots = try { svc.windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }
            .ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
        for (root in roots) {
            val pkg = root.packageName?.toString()
            if (pkg == appContext.packageName) continue // our own popup says "Unlock the app"
            if (pkg in LockDetector.LOCK_PACKAGES) return true
            if (LockDetector.looksLocked(collectText(root))) return true
        }
        return false
    }

    private fun collectText(root: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || visited > 400) return
            visited++
            node.text?.let { out.append(it).append('\n') }
            node.contentDescription?.let { out.append(it).append('\n') }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.toString()
    }

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
                // type: input_text = what to type; viewId / text = which field (its id or label)
                ActionStep.TYPE         -> if (step.inputText != null) typeText(step.inputText, step.text, step.viewId)
                                           else typeText(step.text, null, step.viewId)
                ActionStep.SWIPE        -> swipe(step.direction ?: "up")
                ActionStep.SCROLL       -> scroll(step.direction ?: "down", step.text)
                ActionStep.BACK         -> global(AccessibilityService.GLOBAL_ACTION_BACK)
                ActionStep.HOME         -> global(AccessibilityService.GLOBAL_ACTION_HOME)
                ActionStep.FIND_ELEMENT -> findElement(step.text, step.viewId)
                // A wait is a maximum: it ends as soon as the screen has settled.
                ActionStep.WAIT         -> {
                    val max = (step.waitMs ?: 1000L).coerceIn(0L, 10_000L)
                    uiObserver.waitForStable(quietMs = 600, minMs = minOf(600L, max), maxMs = max)
                    StepResult.OK
                }
                ActionStep.CONFIRM_SEND -> StepResult.OK // gate handled by TaskManager
                ActionStep.READ_TEXT    -> StepResult(true, output = readText(step.text))
                ActionStep.VERIFY       -> verify(step.pkg, step.text)
                ActionStep.DIAL         -> dial(step.number)
                ActionStep.CALL         -> call(step.number)
                ActionStep.SMS          -> sms(step.number, step.inputText ?: step.message)
                ActionStep.ALARM        -> alarm(step.time, step.message)
                ActionStep.TIMER        -> timer(step.time, step.message)
                ActionStep.OPEN_LINK    -> openLink(step.uri, step.pkg)
                ActionStep.INTENT       -> genericIntent(step)
                ActionStep.MEDIA        -> media(step.text)
                ActionStep.NOW_PLAYING  -> notifications.nowPlaying().let { msg ->
                    if (msg == NotificationStore.NEEDS_ACCESS) StepResult.fail(msg) else StepResult(true, output = msg)
                }
                ActionStep.REPLY        -> reply(step.pkg, step.text, step.inputText ?: step.message)
                ActionStep.READ_NOTIFICATIONS -> readNotifications(step.pkg, step.text)
                else -> StepResult.fail("I don't know how to do \"${step.action}\" yet.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step ${step.action} crashed: ${e.message}", e)
            StepResult.fail("Something went wrong while doing ${step.action.replace('_', ' ')}.")
        }
        if (!result.ok) Log.w(TAG, "Step failed: ${step.action} → ${result.error}")
        // Let the UI settle before the next step (ends early when the screen is quiet).
        if (result.ok && step.action !in NO_SETTLE) uiObserver.waitForStable(quietMs = 300, minMs = 150, maxMs = 1500)
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
        lastLaunch = pkg to (launch.component?.packageName ?: launch.`package`)
        Log.i(TAG, "Opened app: ${lastLaunch.second}")
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

        // Exact label first, then close matches ("youtube" ↔ "youtubemusic"). A long phrase
        // that merely contains an app name ("whatsappandsendhitorahul") is NOT a match.
        val match = labelled.firstOrNull { (_, label) -> label in wanted }
            ?: labelled.filter { (_, label) ->
                label.length >= 3 && wanted.any { w ->
                    (label.contains(w) && w.length >= 4) ||
                        (w.contains(label) && w.length - label.length <= MAX_EXTRA_LABEL_CHARS)
                }
            }.maxByOrNull { it.second.length }
            ?: return null
        return pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
    }

    /**
     * Tap the best match for [text] or [viewId] (never a text box when matching by
     * text — see [NodeFinder.Purpose.TAP]), walking up to a clickable ancestor if needed.
     * Retries while the screen loads, and FAILS if the screen doesn't react to the tap,
     * so later steps never run on the wrong screen.
     */
    suspend fun tap(text: String?, viewId: String?): StepResult {
        if (text.isNullOrBlank() && viewId.isNullOrBlank()) return StepResult.fail("Tap step had no target.")
        val node = waitForNode(text, viewId, NodeFinder.Purpose.TAP)
            ?: return StepResult.fail("I couldn't find \"${text ?: viewId}\" on the screen.")
        val before = uiObserver.changeCount
        val clickable = nodeFinder.findClickableAncestorOrSelf(node)
        val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (!clicked) {
            // Fall back to a real tap gesture at the node's position.
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (bounds.isEmpty || !tapAt(bounds.exactCenterX(), bounds.exactCenterY())) {
                return StepResult.fail("I found \"${text ?: viewId}\" but couldn't tap it.")
            }
        }
        if (!uiObserver.waitForChange(before, TAP_EFFECT_TIMEOUT_MS)) {
            return StepResult.fail("Tapping \"${text ?: viewId}\" didn't change anything on the screen.")
        }
        return StepResult.OK
    }

    /**
     * Type [text] into a specific field: the one with [fieldViewId], else the one whose
     * label/hint matches [fieldLabel]. If a field was named and isn't on screen, the step
     * FAILS — it never types into some other box (e.g. a search bar). With no field named,
     * the focused field (or the only/first field) is used.
     */
    suspend fun typeText(text: String?, fieldLabel: String?, fieldViewId: String?): StepResult {
        if (text.isNullOrEmpty()) return StepResult.fail("Type step had no text.")
        val named = !fieldViewId.isNullOrBlank() || !fieldLabel.isNullOrBlank()
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        while (true) {
            val root = service?.rootInActiveWindow ?: return noService()
            val target = if (named) {
                nodeFinder.findInput(root, fieldLabel, fieldViewId)
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                    ?: findFirstEditable(root)
            }
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
                return StepResult.fail(
                    if (named) "The \"${fieldLabel ?: fieldViewId}\" box isn't on the screen, so I didn't type."
                    else "There's no text box on the screen to type into."
                )
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
        val sent = touchThrough {
            svc.dispatchGesture(gesture, null, null).also { delay(400L) }
        }
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
        val node = waitForNode(text, viewId, NodeFinder.Purpose.FIND)
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
    private suspend fun waitForNode(
        text: String?,
        viewId: String?,
        purpose: NodeFinder.Purpose
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        while (true) {
            val root = service?.rootInActiveWindow
            if (root != null) {
                nodeFinder.findBest(root, text, viewId, purpose)?.let { return it }
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
        return touchThrough {
            svc.dispatchGesture(gesture, null, null).also { delay(200L) }
        }
    }

    private fun noService(): StepResult {
        Log.e(TAG, "AccessibilityService not bound! Is the service enabled?")
        return StepResult.fail("AURA's Accessibility service is off. Turn it on in Settings → Accessibility.")
    }

    // ──────────────────────────────────────────────────────────────
    // Verification
    // ──────────────────────────────────────────────────────────────

    /**
     * Pass when app [pkg] is in front (or the app AURA opened for [pkg]) and [text]
     * is visible. Waits for the screen to catch up; fails with a readable reason.
     */
    suspend fun verify(pkg: String?, text: String?): StepResult {
        if (pkg.isNullOrBlank() && text.isNullOrBlank()) return StepResult.OK
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        var foreground: String? = null
        while (true) {
            val root = service?.rootInActiveWindow ?: return noService()
            foreground = root.packageName?.toString()
            val pkgOk = pkg.isNullOrBlank() || foreground == pkg ||
                (lastLaunch.first == pkg && foreground == lastLaunch.second)
            // Text in an input box doesn't count (a typed-but-unsent message isn't proof).
            val textOk = text.isNullOrBlank() ||
                nodeFinder.findBest(root, text, null, NodeFinder.Purpose.VERIFY) != null
            if (pkgOk && textOk) return StepResult.OK
            if (System.currentTimeMillis() > deadline) break
            delay(FIND_POLL_MS)
        }
        val what = listOfNotNull(
            pkg?.takeIf { it.isNotBlank() && foreground != pkg }?.let { "the app ${it.substringAfterLast('.')}" },
            text?.takeIf { it.isNotBlank() }?.let { "\"$it\" on the screen" }
        ).joinToString(" and ")
        return StepResult.fail("I did the steps but couldn't confirm it worked (expected $what).")
    }

    // ──────────────────────────────────────────────────────────────
    // Shortcuts — Android intents, no screen tapping
    // ──────────────────────────────────────────────────────────────

    private fun dial(number: String?): StepResult {
        val n = number?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 3 }
            ?: return StepResult.fail("I need a phone number to call.")
        return startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")), "the dialer")
    }

    /**
     * Place a call. With the "Phone calls" permission the call starts directly; without it
     * the dialer opens with the number and its Call button is pressed through Accessibility.
     */
    private suspend fun call(number: String?): StepResult {
        val n = number?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 3 }
            ?: return StepResult.fail("I need a phone number to call.")
        val uri = Uri.parse("tel:" + Uri.encode(n))
        val canCall = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (canCall) return startIntent(Intent(Intent.ACTION_CALL, uri), "the phone app")

        val opened = startIntent(Intent(Intent.ACTION_DIAL, uri), "the dialer")
        if (!opened.ok) return opened
        if (service == null) return StepResult.fail(NEEDS_CALL_PERMISSION)
        val button = waitForCallButton() ?: return StepResult.fail(NEEDS_CALL_PERMISSION)
        val clickable = nodeFinder.findClickableAncestorOrSelf(button)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return StepResult.OK
        val bounds = android.graphics.Rect().also { button.getBoundsInScreen(it) }
        return if (!bounds.isEmpty && tapAt(bounds.exactCenterX(), bounds.exactCenterY())) StepResult.OK
            else StepResult.fail(NEEDS_CALL_PERMISSION)
    }

    /** The dialer's green Call button (ids/labels differ between Google, Samsung, Xiaomi…). */
    private suspend fun waitForCallButton(): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        while (true) {
            val root = service?.rootInActiveWindow
            if (root != null) findCallButton(root)?.let { return it }
            if (System.currentTimeMillis() > deadline) return null
            delay(FIND_POLL_MS)
        }
    }

    private fun findCallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var byLabel: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 40) return null
            if (node.isVisibleToUser && node.isEnabled) {
                val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase()
                if (id != null && CALL_BUTTON_IDS.any { id == it }) return node
                val label = (node.contentDescription ?: node.text)?.toString()?.trim()?.lowercase()
                if (byLabel == null && label != null && label in CALL_BUTTON_LABELS) byLabel = node
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return walk(root, 0) ?: byLabel
    }

    private fun sms(number: String?, body: String?): StepResult {
        val n = number?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 3 }
            ?: return StepResult.fail("I need a phone number to text.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n"))
        if (!body.isNullOrBlank()) intent.putExtra("sms_body", body)
        return startIntent(intent, "the messaging app")
    }

    private fun alarm(time: String?, label: String?): StepResult {
        val (h, m) = TimeParser.parseClock(time) ?: return StepResult.fail("I need a time like 07:30 for the alarm.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startIntent(intent, "the clock app")
    }

    private fun timer(time: String?, label: String?): StepResult {
        val seconds = TimeParser.parseDuration(time)?.takeIf { it in 1..86_400 }
            ?: return StepResult.fail("I need a length like 5 minutes for the timer.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!label.isNullOrBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startIntent(intent, "the clock app")
    }

    private fun openLink(uri: String?, pkg: String?): StepResult {
        val link = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return StepResult.fail("I need a link to open.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        // Prefer the named app (e.g. YouTube, Maps) when it's installed.
        if (!pkg.isNullOrBlank() && appContext.packageManager.getLaunchIntentForPackage(pkg) != null) {
            intent.setPackage(pkg)
        }
        val result = startIntent(intent, "that link")
        if (result.ok) lastLaunch = pkg to (intent.`package` ?: pkg)
        return result
    }

    /**
     * Any Android intent described as data — this is how app shortcuts are added
     * without code. Only safe actions are allowed (see [isAllowedIntent]): nothing
     * that calls, pays, deletes or installs by itself.
     */
    private fun genericIntent(step: ActionStep): StepResult {
        val action = step.intentAction?.trim()?.takeIf { it.isNotEmpty() } ?: Intent.ACTION_VIEW
        val uri = step.uri?.trim()?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        val mime = step.mime?.trim()?.takeIf { it.isNotEmpty() }
        if (!isAllowedIntent(action, uri, mime)) {
            return StepResult.fail("For safety I can't do that kind of action automatically.")
        }
        val intent = Intent(action)
        when {
            uri != null && mime != null -> intent.setDataAndType(uri, mime)
            uri != null -> intent.data = uri
            mime != null -> intent.type = mime
        }
        step.extras?.forEach { (key, value) -> putTypedExtra(intent, key, value) }

        val pm = appContext.packageManager
        val pkg = step.pkg?.trim()?.takeIf { it.isNotEmpty() }
        if (pkg != null) {
            intent.setPackage(pkg)
            // App not installed or doesn't handle this — let any app that can, handle it.
            if (intent.resolveActivity(pm) == null) intent.setPackage(null)
        }
        val result = startIntent(intent, if (pkg != null) pkg.substringAfterLast('.') else "that")
        if (result.ok) lastLaunch = pkg to (intent.`package` ?: intent.resolveActivity(pm)?.packageName)
        return result
    }

    private fun putTypedExtra(intent: Intent, key: String, value: String) {
        when {
            value.startsWith("i:") -> value.drop(2).toIntOrNull()?.let { intent.putExtra(key, it) }
            value.startsWith("l:") -> value.drop(2).toLongOrNull()?.let { intent.putExtra(key, it) }
            value.startsWith("b:") -> intent.putExtra(key, value.drop(2).equals("true", ignoreCase = true))
            else -> intent.putExtra(key, value)
        }
    }

    /** Media keys and volume for whatever app is playing — no app needs to be opened. */
    private fun media(command: String?): StepResult {
        val am = appContext.getSystemService(AudioManager::class.java)
            ?: return StepResult.fail("I can't control media on this phone.")
        val cmd = command?.trim()?.lowercase()?.replace(' ', '_').orEmpty()
        when (cmd) {
            "volume_up", "louder" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down", "quieter" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> {
                val key = when (cmd) {
                    "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    else -> return StepResult.fail("I don't know the media command \"$command\".")
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }
        return StepResult.OK
    }

    private fun reply(pkg: String?, person: String?, message: String?): StepResult {
        if (person.isNullOrBlank()) return StepResult.fail("Who should I reply to?")
        if (message.isNullOrBlank()) return StepResult.fail("What should the reply say?")
        val problem = notifications.reply(pkg, person, message)
        return if (problem == null) StepResult.OK else StepResult.fail(problem)
    }

    private fun readNotifications(pkg: String?, person: String?): StepResult {
        if (!notifications.isEnabled()) return StepResult.fail(NotificationStore.NEEDS_ACCESS)
        val items = notifications.recent(pkg, person)
        val text = if (items.isEmpty()) {
            "No new messages" + (person?.let { " from $it" } ?: "") + "."
        } else {
            items.joinToString(". ") { "${it.title}: ${it.text}".take(200) }
        }
        return StepResult(true, output = text)
    }

    private fun startIntent(intent: Intent, what: String): StepResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (service ?: appContext).startActivity(intent)
        StepResult.OK
    } catch (_: ActivityNotFoundException) {
        StepResult.fail("No app on this phone can open $what.")
    } catch (e: SecurityException) {
        StepResult.fail("Android blocked opening $what.")
    }

    companion object {
        val NO_SERVICE_NEEDED = setOf(
            ActionStep.WAIT, ActionStep.CONFIRM_SEND,
            ActionStep.DIAL, ActionStep.CALL, ActionStep.SMS, ActionStep.ALARM, ActionStep.TIMER, ActionStep.OPEN_LINK,
            ActionStep.INTENT, ActionStep.MEDIA, ActionStep.NOW_PLAYING, ActionStep.REPLY,
            ActionStep.READ_NOTIFICATIONS
        )

        /** Intent actions AURA may fire on its own. Nothing here sends, calls or pays by itself. */
        private val ALLOWED_INTENT_ACTIONS = setOf(
            Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SENDTO, Intent.ACTION_DIAL,
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH, Intent.ACTION_INSERT, Intent.ACTION_MAIN,
            "android.media.action.MEDIA_PLAY_FROM_SEARCH",
            "android.media.action.STILL_IMAGE_CAMERA", "android.media.action.VIDEO_CAMERA",
            AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER, AlarmClock.ACTION_SHOW_ALARMS,
            Intent.ACTION_POWER_USAGE_SUMMARY
        )

        fun isAllowedIntent(action: String, uri: Uri?, mime: String?): Boolean {
            if (mime == "application/vnd.android.package-archive") return false // no installs
            val scheme = uri?.scheme?.lowercase()
            if (scheme == "file" || scheme == "content" && action == Intent.ACTION_VIEW && mime == null) return false
            return action in ALLOWED_INTENT_ACTIONS || action.startsWith("android.settings.")
        }
        const val VERIFY_TIMEOUT_MS = 5000L
        private const val TOUCH_THROUGH_SETTLE_MS = 80L
        private const val NEEDS_CALL_PERMISSION =
            "I opened the dialer but couldn't press Call. Allow AURA to make phone calls (Settings → Apps → AURA → Permissions)."
        private val CALL_BUTTON_IDS = setOf(
            "dialpad_floating_action_button", "dialbutton", "dial_button", "call_button",
            "dialpad_voice_call_button", "btn_call", "call", "dial_btn", "dialpad_call_button"
        )
        private val CALL_BUTTON_LABELS = setOf("call", "dial", "voice call", "make a call", "make voice call", "audio call")
        /** "open youtube app" still finds YouTube; a whole sentence around an app name doesn't. */
        private const val MAX_EXTRA_LABEL_CHARS = 6
        /** How long a tap may take before the screen visibly reacts. */
        const val TAP_EFFECT_TIMEOUT_MS = 2500L
        /** Steps that don't change the screen, so there's nothing to wait for afterwards. */
        val NO_SETTLE = setOf(
            ActionStep.WAIT, ActionStep.CONFIRM_SEND, ActionStep.VERIFY, ActionStep.READ_TEXT,
            ActionStep.FIND_ELEMENT, ActionStep.ALARM, ActionStep.TIMER, ActionStep.MEDIA,
            ActionStep.NOW_PLAYING, ActionStep.REPLY, ActionStep.READ_NOTIFICATIONS
        )
    }
}
