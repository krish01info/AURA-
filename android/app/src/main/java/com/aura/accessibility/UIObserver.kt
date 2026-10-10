package com.aura.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UIObserver — maintains a live snapshot of the current screen's UI tree.
 *
 * Updated on every [AccessibilityEvent] from [AURAAccessibilityService].
 * The ActionExecutor and TaskPlanner read from this to understand screen state.
 *
 * Security: Raw UI text is isolated and NEVER passed as instructions to the LLM.
 * It is always wrapped in [UI_CONTENT_START]...[UI_CONTENT_END] tags in the prompt.
 */
@Singleton
class UIObserver @Inject constructor() {

    private val _currentPackage = MutableStateFlow("")
    val currentPackage: StateFlow<String> = _currentPackage.asStateFlow()

    private val _uiSnapshot = MutableStateFlow("")
    val uiSnapshot: StateFlow<String> = _uiSnapshot.asStateFlow()

    /**
     * Called from [AURAAccessibilityService.onAccessibilityEvent].
     * Updates the live UI snapshot.
     */
    fun update(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        event.packageName?.let { _currentPackage.value = it.toString() }
        root?.let { _uiSnapshot.value = extractText(it) }
    }

    /**
     * Returns a sanitised text snapshot of the current UI tree.
     * Used as context for LLM planning.
     */
    fun getTextSnapshot(): String = _uiSnapshot.value

    /**
     * Recursively extracts visible text from the accessibility node tree.
     * Limits depth to avoid huge outputs; caps total length.
     */
    private fun extractText(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 5) return ""  // Keep shallow — deep nodes are rarely actionable
        val builder = StringBuilder()

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        // Prefer visible text; only fall back to a short content-description when
        // there is no text at all (avoids duplicating icon labels and long tooltips).
        if (!text.isNullOrEmpty()) {
            builder.appendLine(text)
        } else if (!desc.isNullOrEmpty() && desc.length <= 60) {
            builder.appendLine(desc)
        }
        // [id:] lines are omitted — resource IDs are internal and not useful to the LLM.

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            builder.append(extractText(child, depth + 1))
            child.recycle()
        }

        return builder.toString().take(2000) // Tight cap — matched to prompt budget
    }
}
