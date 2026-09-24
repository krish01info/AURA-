package com.aura.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single action step output by the LLM planner.
 * This is the core unit of execution for the AURA agent.
 *
 * The LLM outputs a JSON array of these — AURA executes them one by one.
 */
@Serializable
data class ActionStep(
    /** The action type — must be one of the supported primitives below. */
    val action: String,

    // ── Target / navigation ────────────────────────────────
    /** App package name — used with open_app action. */
    val pkg: String? = null,

    /** UI text to find — used with find_element, tap. */
    val text: String? = null,

    /** Resource view ID — used with find_element. */
    val viewId: String? = null,

    // ── Input ─────────────────────────────────────────────
    /** Text to type — used with type action. */
    @SerialName("input_text") val inputText: String? = null,

    // ── Swipe / scroll direction ────────────────────────────
    val direction: String? = null,   // "up" | "down" | "left" | "right"

    // ── Wait ───────────────────────────────────────────────
    @SerialName("wait_ms") val waitMs: Long? = null,

    // ── Confirmation gate ───────────────────────────────────
    /** Human-readable message shown to the user in the confirmation dialog. */
    val message: String? = null,

    /** Risk level override — LOW | MEDIUM | HIGH | CRITICAL */
    val risk: String? = null,

    // ── Clarification ──────────────────────────────────────
    /** Set when the LLM cannot determine the goal and needs to ask the user. */
    val clarification: String? = null
) {
    companion object {
        // Supported action primitives
        const val OPEN_APP          = "open_app"
        const val TAP               = "tap"
        const val TYPE              = "type"
        const val SWIPE             = "swipe"
        const val SCROLL            = "scroll"
        const val BACK              = "back"
        const val HOME              = "home"
        const val FIND_ELEMENT      = "find_element"
        const val WAIT              = "wait"
        const val CONFIRM_SEND      = "confirm_send"
        const val READ_TEXT         = "read_text"
        const val PRESS_KEY         = "press_key"
        const val CLARIFY           = "clarify"
    }
}

/**
 * A structured goal parsed from the user's natural language input.
 */
@Serializable
data class Goal(
    val rawInput: String,         // original user text
    val intent: String,           // e.g. "send_whatsapp_message"
    val targetApp: String? = null,  // e.g. "com.whatsapp"
    val parameters: Map<String, String> = emptyMap()  // e.g. {contact: "Rahul", message: "..."}
)
