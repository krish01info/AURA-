package com.aura.agent

import android.content.Context
import android.util.Log
import com.aura.data.model.ActionStep
import com.aura.policy.PolicyEngine
import com.aura.voice.KeywordSpotter
import com.aura.voice.VoiceManager
import com.aura.voice.WakeWordService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiveTaskRunner"

/**
 * LiveTaskRunner — runs a `listen` step: AURA keeps listening (offline) and runs a trigger's
 * steps each time its word is heard ("record on *start*, photo on *capture*, stop on *stop*").
 *
 *  1. Pre-flight: no CRITICAL step may be inside a trigger; HIGH ones are approved once.
 *  2. Makes sure the offline voice model is downloaded (once, ~36 MB).
 *  3. Listens with [KeywordSpotter]; [TriggerMatcher] turns words into actions;
 *     trigger steps run through [ActionExecutor] in quick mode (no waiting for the screen).
 *  4. Ends on: a stop word (runs on_stop first), leaving the app for [LEAVE_APP_MS],
 *     [MAX_FAILED_TRIGGERS] failed triggers in a row, the mic being blocked by the app,
 *     the optional timeout, or Stop (popup / notification), which cancels the task.
 *
 * Progress goes to [TaskManager] through [onState] as [TaskState.Live].
 */
@Singleton
class LiveTaskRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionExecutor: ActionExecutor,
    private val policyEngine: PolicyEngine,
    private val spotter: KeywordSpotter,
    private val voiceManager: VoiceManager
) {
    sealed class Result {
        /** Ended normally; [fired] = how many words were acted on. */
        data class Done(val fired: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun run(
        step: ActionStep,
        approve: suspend (ActionStep) -> Boolean,
        onState: (TaskState.Live) -> Unit
    ): Result {
        val triggers = step.triggers.orEmpty().filter { t -> t.words.any { it.isNotBlank() } && t.then.isNotEmpty() }
        if (triggers.isEmpty()) return Result.Failed("I didn't get which words to listen for.")
        val stopWords = step.stopWords?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf(DEFAULT_STOP_WORD)
        val matcher = TriggerMatcher(triggers, stopWords, ignoreNow = { voiceManager.isSpeaking.value })
        val words = (triggers.flatMap { it.words } + stopWords).map { it.lowercase() }.distinct()

        // ── 1. Pre-flight risk check ───────────────────────────────
        val nested = triggers.flatMap { t -> t.then.flatMap { it.flatten() } } + step.onStop.orEmpty().flatMap { it.flatten() }
        if (nested.any { it.action == ActionStep.LISTEN }) return Result.Failed("A live task can't contain another one.")
        val risks = nested.map { policyEngine.classify(it) }
        if (RiskLevel.CRITICAL in risks) {
            return Result.Failed("For safety, a live task can't pay or delete things on a spoken word.")
        }
        if (RiskLevel.HIGH in risks) {
            val what = triggers.joinToString("; ") { t -> "\"${t.words.first()}\" → ${t.then.joinToString(", ") { describe(it) }}" }
            val ok = approve(ActionStep(
                action = ActionStep.CONFIRM_SEND, risk = RiskLevel.HIGH.name,
                message = "While I listen, each word will act right away without asking: $what. Allow?"
            ))
            if (!ok) return Result.Failed("Okay, I won't start listening.")
        }

        // ── 2. Offline voice model ─────────────────────────────────
        var state = TaskState.Live(words, fired = 0, lastHeard = null, status = null)
        fun publish(s: TaskState.Live) { state = s; onState(s) }
        if (spotter.modelState.value !is KeywordSpotter.ModelState.Ready) {
            publish(state.copy(status = "Getting the offline voice model ready (one time, about 36 MB)…"))
            coroutineScope {
                val progress = launch {
                    spotter.modelState.collect { ms ->
                        if (ms is KeywordSpotter.ModelState.Downloading) {
                            publish(state.copy(status = "Downloading the offline voice model… ${ms.percent}%"))
                        }
                    }
                }
                val ready = spotter.ensureModel()
                progress.cancel()
                if (!ready) return@coroutineScope
            }
            if (spotter.modelState.value !is KeywordSpotter.ModelState.Ready) {
                return Result.Failed("I couldn't download the offline voice model. Check the internet and try again.")
            }
        }

        // The mic may only be used in the background while AURA's mic service runs.
        WakeWordService.keepRunningForLive(context)

        // ── 3. Listen ──────────────────────────────────────────────
        val targetApp = actionExecutor.foregroundPackage()
        Log.i(TAG, "Live task in ${targetApp ?: "?"}: words=$words")
        publish(state.copy(status = null))

        var fired = 0
        var failedInRow = 0
        var endReason: String? = null      // null = ended normally
        var stopped = false
        var silencedSince = 0L
        var awaySince = 0L
        val deadline = step.timeoutS?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000L }

        coroutineScope {
            val listening = launch {
                spotter.listen(matcher.vocabulary).takeWhile { heard ->
                    when (heard) {
                        is KeywordSpotter.Heard.Error -> { endReason = heard.reason; false }
                        is KeywordSpotter.Heard.Silenced -> {
                            silencedSince = if (heard.silenced) System.currentTimeMillis() else 0L
                            publish(state.copy(status = if (heard.silenced) "The microphone is blocked by this app…" else null))
                            true
                        }
                        is KeywordSpotter.Heard.Text -> {
                            for (event in matcher.onHeard(heard.text, heard.isFinal)) {
                                if (event is TriggerMatcher.Event.Stop) { stopped = true; break }
                                val fire = event as TriggerMatcher.Event.Fire
                                Log.i(TAG, "Heard \"${fire.word}\"")
                                val ok = fire.trigger.then.all { actionExecutor.execute(it, quick = true).ok }
                                if (ok) { fired++; failedInRow = 0 } else failedInRow++
                                publish(state.copy(fired = fired, lastHeard = fire.word,
                                    status = if (ok) null else "Couldn't do \"${fire.word}\" — is the button on screen?"))
                                if (failedInRow >= MAX_FAILED_TRIGGERS) {
                                    endReason = "\"${fire.word}\" didn't work $MAX_FAILED_TRIGGERS times in a row, so I stopped listening."
                                    break
                                }
                            }
                            !stopped && endReason == null
                        }
                    }
                }.collect()
            }
            // Leaving the app, a blocked mic or the time limit end listening even when nothing is said.
            val watcher = launch {
                while (listening.isActive) {
                    delay(WATCH_MS)
                    val now = System.currentTimeMillis()
                    val fg = actionExecutor.foregroundPackage()
                    if (targetApp != null && fg != null && fg != targetApp && fg != context.packageName) {
                        if (awaySince == 0L) awaySince = now
                        if (now - awaySince > LEAVE_APP_MS) { stopped = true; break }
                    } else {
                        awaySince = 0L
                    }
                    if (silencedSince > 0 && now - silencedSince > SILENCED_GRACE_MS) {
                        endReason = "This app doesn't let other apps use the microphone while it's open, " +
                            "so I can't hear your words here."
                        break
                    }
                    if (deadline != null && now > deadline) { stopped = true; break }
                }
                listening.cancel()
            }
            listening.join()
            watcher.cancel()
        }

        endReason?.let { return Result.Failed(it) }
        // Stop word, timeout or leaving the app: finish cleanly (e.g. stop the recording).
        for (st in step.onStop.orEmpty()) actionExecutor.execute(st, quick = true)
        Log.i(TAG, "Live task ended after $fired word(s)")
        return Result.Done(fired)
    }

    private fun describe(st: ActionStep): String =
        listOfNotNull(st.action.replace('_', ' '), st.text?.substringBefore('|') ?: st.direction).joinToString(" ")

    companion object {
        const val DEFAULT_STOP_WORD = "stop"
        private const val WATCH_MS = 1000L
        /** Leaving the app for longer than this ends the live task. */
        private const val LEAVE_APP_MS = 5000L
        /** The mic must be unblocked again within this time. */
        private const val SILENCED_GRACE_MS = 3000L
        private const val MAX_FAILED_TRIGGERS = 3
    }
}
