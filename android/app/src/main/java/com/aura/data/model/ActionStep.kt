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
    val clarification: String? = null,

    // ── Shortcuts (Android intents — no screen tapping) ─────
    /** Link for open_link: "https://…", "geo:…", "https://wa.me/<number>?text=…". */
    val uri: String? = null,

    /** Phone number for dial / sms. */
    val number: String? = null,

    /** "HH:MM" (24 h) for alarm; "HH:MM:SS", "MM:SS" or seconds for timer. */
    val time: String? = null,

    // ── Generic Android intent (action "intent") ────────────
    /** e.g. "android.intent.action.VIEW", "android.media.action.MEDIA_PLAY_FROM_SEARCH". */
    @SerialName("intent_action") val intentAction: String? = null,

    /** MIME type for the intent data / share, e.g. "text/plain". */
    val mime: String? = null,

    /**
     * Intent extras. Values are strings; prefix "i:" for Int, "l:" for Long,
     * "b:" for Boolean (e.g. "b:true"). `{slot}`s are filled like other fields.
     */
    val extras: Map<String, String>? = null,

    // ── Live task (action "listen") ─────────────────────────
    /** Words that each run some steps while AURA keeps listening (see [Trigger]). */
    val triggers: List<Trigger>? = null,

    /** Words that end the live task (default "stop"). */
    @SerialName("stop_words") val stopWords: List<String>? = null,

    /** Steps run once when a stop word is heard (e.g. tap "Stop recording"). */
    @SerialName("on_stop") val onStop: List<ActionStep>? = null,

    /** Optional limit in seconds; null = until a stop word. */
    @SerialName("timeout_s") val timeoutS: Int? = null
) {
    /** This step plus every step nested inside it (trigger steps, on_stop), depth-first. */
    fun flatten(): List<ActionStep> =
        listOf(this) + triggers.orEmpty().flatMap { t -> t.then.flatMap { it.flatten() } } +
            onStop.orEmpty().flatMap { it.flatten() }

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

        /** Check the task worked: app [pkg] in front and/or [text] visible. Fails the step if not. */
        const val VERIFY            = "verify"

        // Shortcuts — launched as Android intents
        const val DIAL              = "dial"       // opens the dialer with [number] (doesn't call)
        const val CALL              = "call"       // places a call to [number] (dialer + Call button without the permission)
        const val SMS               = "sms"        // opens SMS composer: [number], [inputText]
        const val ALARM             = "alarm"      // sets an alarm at [time], label [message]
        const val TIMER             = "timer"      // starts a timer for [time], label [message]
        const val OPEN_LINK         = "open_link"  // opens [uri] (web, maps, wa.me, youtube…)

        /** Any allowed Android intent: [intentAction] + [uri] / [mime] / [extras], optional app [pkg]. */
        const val INTENT            = "intent"

        /** Media keys for whatever is playing: [text] = play | pause | play_pause | next | previous | stop | volume_up | volume_down | mute. */
        const val MEDIA             = "media"

        /** Say what's playing now (needs notification access). */
        const val NOW_PLAYING       = "now_playing"

        /** Reply from a notification: app [pkg], person [text], message [inputText]. Needs notification access. */
        const val REPLY             = "reply"

        /** Read recent notifications aloud: optional app [pkg], optional person [text]. Needs notification access. */
        const val READ_NOTIFICATIONS = "read_notifications"

        /**
         * Live task: keep listening (offline) and run [triggers] each time their word is heard,
         * until a [stopWords] word (then [onStop]). Always the last step of a plan.
         */
        const val LISTEN = "listen"

        /** Every action this app version can run. Shortcuts using anything else are skipped (newer file, older app). */
        val KNOWN_ACTIONS = setOf(
            OPEN_APP,
            TAP,
            TYPE,
            SWIPE,
            SCROLL,
            BACK,
            HOME,
            FIND_ELEMENT,
            WAIT,
            CONFIRM_SEND,
            READ_TEXT,
            PRESS_KEY,
            CLARIFY,
            VERIFY,
            DIAL,
            CALL,
            SMS,
            ALARM,
            TIMER,
            OPEN_LINK,
            INTENT,
            MEDIA,
            NOW_PLAYING,
            REPLY,
            READ_NOTIFICATIONS,
            LISTEN
        )
    }
}

/**
 * One word of a live task: when any of [words] is heard, run [then].
 * [once] = only the first time (e.g. "start" recording); [cooldownMs] ignores repeats
 * of the same word heard within that time.
 */
@Serializable
data class Trigger(
    val words: List<String>,
    val then: List<ActionStep>,
    val once: Boolean = false,
    @SerialName("cooldown_ms") val cooldownMs: Long = 800L
)

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
