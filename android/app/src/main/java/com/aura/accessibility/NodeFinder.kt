package com.aura.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NodeFinder — utility for locating UI elements in the accessibility node tree.
 *
 * Extracted into its own injectable class so both [ActionExecutor] and future
 * components (SkillRouter, verifiers) can share the same lookup logic without
 * duplication.
 *
 * Security: Node text is NEVER forwarded to the LLM directly — it is always
 * wrapped in [UI_CONTENT_START] / [UI_CONTENT_END] by UIObserver.
 */
@Singleton
class NodeFinder @Inject constructor() {

    /**
     * Find a node by visible text OR resource view ID.
     * View ID is preferred when provided (more stable than text).
     *
     * Caller is responsible for recycling the returned node.
     */
    fun findNode(
        root: AccessibilityNodeInfo,
        text: String?,
        viewId: String?
    ): AccessibilityNodeInfo? = findBest(root, text, viewId, Purpose.FIND)

    /** What the node is needed for — decides which matches are acceptable. */
    enum class Purpose {
        /** Something to tap: never a text box (a search box showing "Priya" is not Priya's chat). */
        TAP,
        /** Proof a task worked: text the app shows, not text sitting in an input box. */
        VERIFY,
        /** Any visible match. */
        FIND
    }

    /**
     * Pick the best visible node for [text] / [viewId].
     *
     * View ID wins when it's on screen. For text, every match is considered and ranked:
     * exact label (case-insensitive) beats "contains"; tappable (or inside something
     * tappable) beats plain text. For [Purpose.TAP] / [Purpose.VERIFY], editable fields
     * are skipped — they echo whatever was just typed and caused wrong taps before.
     */
    fun findBest(
        root: AccessibilityNodeInfo,
        text: String?,
        viewId: String?,
        purpose: Purpose
    ): AccessibilityNodeInfo? {
        if (!viewId.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByViewId(viewId)
                .firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        if (text.isNullOrBlank()) return null
        val wanted = text.trim().lowercase()
        return root.findAccessibilityNodeInfosByText(text)
            .asSequence()
            .filter { it.isVisibleToUser }
            .filter { purpose == Purpose.FIND || !it.isEditable }
            .map { node -> node to score(node, wanted) }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(node: AccessibilityNodeInfo, wanted: String): Int {
        val label = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
        var s = 0
        if (label == wanted) s += 100 else if (label.startsWith(wanted)) s += 40
        if (node.isClickable) s += 20 else if (findClickableAncestorOrSelf(node) != null) s += 10
        if (node.isEditable) s -= 50
        return s
    }

    /** Editable field by view ID, or by its label / hint / description. Null if not on screen. */
    fun findInput(root: AccessibilityNodeInfo, label: String?, viewId: String?): AccessibilityNodeInfo? {
        if (!viewId.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByViewId(viewId)
                .firstOrNull { it.isVisibleToUser && it.isEditable }
                ?.let { return it }
        }
        if (label.isNullOrBlank()) return null
        val wanted = label.trim().lowercase()
        return allEditables(root).firstOrNull { node ->
            val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                node.hintText?.toString() else null
            listOfNotNull(hint, node.contentDescription?.toString(), node.text?.toString())
                .any { it.trim().lowercase().let { v -> v == wanted || v.startsWith(wanted) || v.contains(wanted) } }
        }
    }

    private fun allEditables(node: AccessibilityNodeInfo?, depth: Int = 0, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (node == null || depth > 30) return out
        if (node.isEditable && node.isVisibleToUser) out += node
        for (i in 0 until node.childCount) allEditables(node.getChild(i), depth + 1, out)
        return out
    }

    /**
     * Walk up the accessibility tree from [node] to find the nearest ancestor
     * (or the node itself) that is clickable.
     *
     * Returns null if no clickable ancestor exists.
     * Caller owns and must recycle the returned node.
     */
    fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable) return current
            val parent = current.parent
            if (current != node) current.recycle()
            current = parent
        }
        return null
    }

    /**
     * Depth-first search for the first scrollable node in the tree.
     * Returns an [AccessibilityNodeInfo.obtain] copy — caller must recycle it.
     */
    fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findFirstScrollable(child)
            child?.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * Walk up from [node] to find the nearest scrollable ancestor.
     * Returns null if none found.
     */
    fun findScrollableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node?.parent
        while (current != null) {
            if (current.isScrollable) return current
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return null
    }

    /**
     * Check whether a node with the given text exists anywhere in the tree.
     * Useful for post-action verification.
     */
    fun nodeExists(root: AccessibilityNodeInfo, text: String): Boolean =
        root.findAccessibilityNodeInfosByText(text).isNotEmpty()
}
