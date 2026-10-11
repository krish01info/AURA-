package com.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UIObserver — reads the current screen for the LLM and tracks when it last changed.
 *
 * The snapshot is built **on demand** (only when a plan is made), not on every
 * accessibility event, and describes each visible element with its role so the
 * model knows what it can tap or type into:
 *
 *     [button] Send #com.whatsapp:id/send
 *     [input] Message #com.whatsapp:id/entry
 *     [text] Priya Sharma
 *     [toggle on] Wi-Fi
 *
 * Duplicate lines are dropped and the result is capped at [MAX_CHARS] (prompt budget).
 *
 * Security: this text is screen data. It is always wrapped in
 * [UI_CONTENT_START]…[UI_CONTENT_END] in the prompt and never treated as instructions.
 */
@Singleton
class UIObserver @Inject constructor() {

    private var service: AccessibilityService? = null

    private val _currentPackage = MutableStateFlow("")
    val currentPackage: StateFlow<String> = _currentPackage.asStateFlow()

    /** uptimeMillis of the last event that changed what's on screen. */
    @Volatile private var lastChangeAt = 0L
    @Volatile private var ownScreenInFront = false

    /** Number of screen changes seen so far — compare before/after an action. */
    @Volatile var changeCount = 0L
        private set

    fun attach(accessibilityService: AccessibilityService) { service = accessibilityService }
    fun detach() { service = null }

    /** Called from [AURAAccessibilityService.onAccessibilityEvent] — must stay cheap. */
    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        val own = pkg != null && pkg == service?.packageName
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            // Track whether AURA's own screen is in front (vs. only its popup overlay).
            val cls = event.className?.toString().orEmpty()
            if (!own) ownScreenInFront = false
            else if (cls.endsWith("Activity")) ownScreenInFront = true
        }
        // AURA's popup updates while a task runs in another app — that isn't the app changing.
        if (own && !ownScreenInFront) return
        pkg?.let { _currentPackage.value = it }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                lastChangeAt = SystemClock.uptimeMillis()
                changeCount++
            }
        }
    }

    /**
     * Wait until the screen has stopped changing for [quietMs] (e.g. an app finished
     * loading), waiting at least [minMs] and at most [maxMs]. Faster than fixed sleeps
     * on quick phones, and safer on slow ones.
     */
    suspend fun waitForStable(quietMs: Long = 500, minMs: Long = 300, maxMs: Long = 4000) {
        val start = SystemClock.uptimeMillis()
        if (minMs > 0) delay(minMs.coerceAtMost(maxMs))
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - start >= maxMs) return
            if (now - lastChangeAt >= quietMs) return
            delay(100)
        }
    }

    /**
     * Wait up to [timeoutMs] for any screen change after [sinceCount] (see [changeCount]).
     * Note: a click by itself (TYPE_VIEW_CLICKED) is not a change — the screen must react.
     */
    suspend fun waitForChange(sinceCount: Long, timeoutMs: Long = 2000): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (changeCount > sinceCount) return true
            delay(50)
        }
        return changeCount > sinceCount
    }

    /** A role-tagged description of the screen in front, built now. */
    fun getTextSnapshot(): String {
        val root = service?.rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        collect(root, 0, lines)
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length + line.length + 1 > MAX_CHARS) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo, depth: Int, out: MutableSet<String>) {
        if (depth > MAX_DEPTH || out.size >= MAX_LINES) return
        if (node.isVisibleToUser) {
            describe(node)?.let { out += it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, depth + 1, out)
        }
    }

    private fun describe(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim()?.replace('\n', ' ')
        val desc = node.contentDescription?.toString()?.trim()?.replace('\n', ' ')
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim() else null
        val label = (text?.takeIf { it.isNotEmpty() }
            ?: desc?.takeIf { it.isNotEmpty() && it.length <= 80 }
            ?: hint?.takeIf { it.isNotEmpty() })?.take(120)
        val id = node.viewIdResourceName

        val role = when {
            node.isEditable -> "input"
            node.isCheckable -> if (node.isChecked) "toggle on" else "toggle off"
            node.isClickable || node.isLongClickable -> "button"
            else -> "text"
        }
        // Plain text needs a label; actionable elements are kept if they have a label or an id.
        if (label == null && (role == "text" || id == null)) return null
        return buildString {
            append('[').append(role).append(']')
            if (label != null) append(' ').append(label)
            if (id != null && role != "text") append(" #").append(id)
        }
    }

    private companion object {
        const val MAX_CHARS = 2000
        const val MAX_LINES = 150
        const val MAX_DEPTH = 30
    }
}
