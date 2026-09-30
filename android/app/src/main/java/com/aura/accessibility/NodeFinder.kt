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
    ): AccessibilityNodeInfo? = when {
        viewId != null -> root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()
        text != null   -> root.findAccessibilityNodeInfosByText(text).firstOrNull()
        else           -> null
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
