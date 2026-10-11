package com.aura.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aura.agent.RiskLevel
import com.aura.agent.TaskManager
import com.aura.agent.TaskState
import com.aura.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val TAG = "WakeWordService"
private const val CHANNEL_ID = "aura_wake_word"
private const val NOTIF_ID = 2001
private const val PREFS = "aura_assistant"
private const val PREF_WAKE_ENABLED = "wake_word_enabled"

/**
 * WakeWordService — AURA's assistant. Owns the whole voice flow, so it works over
 * any app, with AURA's own screen closed:
 *
 *   "Hey AURA" ─► popup + beep ─► listen for the command ─► TaskManager.execute
 *        │                                     ▲
 *        └─ "Hey AURA, open YouTube" ──────────┘  (command in the same sentence)
 *
 *   The popup shows live text while you speak, then what was heard (with Cancel),
 *   task progress, Allow/Deny approvals (tap or "yes"/"no") and the result.
 *   A running task is stopped with Cancel in the popup or Stop in the notification.
 *
 * Wake detection uses Android's SpeechRecognizer in a loop that restarts only after
 * each session ends. Smoothness measures: on-device recognizer when available
 * (falls back to online), system start-beeps muted on restarts (never while music
 * plays), no listening while a task runs (it would hear AURA and the app's audio),
 * one retry when nothing was heard. A dedicated wake-word engine (Picovoice
 * Porcupine) is still the planned upgrade for battery.
 *
 * Start it with the intents built by the companion helpers:
 *  - [startWake]  — turn on always-listening (persisted)
 *  - [listenNow]  — one command right now (in-app mic button, floating bubble)
 *  - [turnOff]    — stop listening and remove the notification
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var voiceManager: VoiceManager
    @Inject lateinit var taskManager: TaskManager
    @Inject lateinit var overlay: AssistantOverlay

    private enum class Mode { IDLE, WAKE, COMMAND, TASK }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mode = Mode.IDLE
    private var wakeEnabled = false

    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeHeardInPartial = false
    private var restartJob: Job? = null
    private var flowJob: Job? = null
    private var hideJob: Job? = null
    private var errorStreak = 0

    /** Last task state handled (identity), so duplicates are ignored. */
    private var lastHandledState: TaskState? = null
    private var tone: ToneGenerator? = null
    private var started = false

    /** Use Android's on-device recognizer for the wake loop (faster, offline, fewer beeps). */
    private var useOnDevice = false
    private var audioManager: AudioManager? = null
    /** Streams we muted to hide the recognizer's start beep (restored right after). */
    private val mutedStreams = mutableSetOf<Int>()
    private var unmuteJob: Job? = null

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Say \"Hey AURA\" to activate"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // e.g. Android 14 refuses a microphone service started from the background
            Log.e(TAG, "Cannot start foreground: ${e.message}")
            stopSelf()
            return
        }
        started = true
        wakeEnabled = isWakeEnabled(this)
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 60) } catch (_: Exception) { null }
        audioManager = getSystemService(AudioManager::class.java)
        useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        Log.i(TAG, "Wake recognizer: ${if (useOnDevice) "on-device" else "online"}")

        // Follow the task the assistant started, to drive the popup.
        scope.launch {
            taskManager.state.collect { onTaskState(it) }
        }
        Log.i(TAG, "Assistant service created (wake word ${if (wakeEnabled) "on" else "off"})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_LISTEN_NOW -> startCommandCapture()
            ACTION_STOP_TASK -> taskManager.emergencyStop()
            ACTION_TURN_OFF -> {
                setWakeEnabled(this, false)
                wakeEnabled = false
                if (mode == Mode.WAKE || mode == Mode.IDLE) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> { // ACTION_START_WAKE, or a sticky restart (null intent)
                wakeEnabled = isWakeEnabled(this)
                if (!wakeEnabled) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (mode == Mode.IDLE) startWake()
            }
        }
        return if (wakeEnabled) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        mode = Mode.IDLE
        flowJob?.cancel()
        hideJob?.cancel()
        restoreEarcons()
        destroyWakeRecognizer()
        overlay.hide()
        tone?.release()
        scope.cancel()
        Log.i(TAG, "Assistant service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────
    // Wake-word listening
    // ──────────────────────────────────────────────────────────────

    private fun startWake() {
        restartJob?.cancel()
        if (!wakeEnabled) {
            mode = Mode.IDLE
            return
        }
        if (mode != Mode.TASK) mode = Mode.WAKE
        if (!hasMicPermission()) {
            Log.w(TAG, "No microphone permission")
            updateNotification("Microphone permission needed — open AURA to allow it")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available")
            updateNotification("No speech recognition on this phone — install or enable the Google app")
            return
        }
        wakeHeardInPartial = false
        val recognizer = wakeRecognizer ?: createWakeRecognizer().also {
            it.setRecognitionListener(wakeListener)
            wakeRecognizer = it
        }
        try {
            // Background restarts happen every few seconds — hide the system "listening" beep.
            muteEarconsBriefly()
            recognizer.startListening(recognizerIntent(partial = true))
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            destroyWakeRecognizer()
            scheduleWake(2000)
        }
    }

    /** True when a "Hey AURA" should start a new command (idle, or a finished task's popup). */
    private fun listeningForWake(): Boolean =
        mode == Mode.WAKE || (mode == Mode.TASK && !taskManager.isBusy)

    private fun scheduleWake(delayMs: Long) {
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delayMs)
            if (mode == Mode.WAKE || mode == Mode.TASK) startWake()
        }
    }

    private fun destroyWakeRecognizer() {
        restartJob?.cancel()
        try {
            wakeRecognizer?.cancel()
            wakeRecognizer?.destroy()
        } catch (_: Exception) { }
        wakeRecognizer = null
    }

    private val wakeListener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            if (!listeningForWake()) return
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (!wakeHeardInPartial && texts.any { extractCommand(it) != null }) {
                wakeHeardInPartial = true
                // Pop up right away; the final result decides what to do next.
                beep()
                overlay.show("Listening…")
            }
        }

        override fun onResults(results: Bundle?) {
            errorStreak = 0
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            Log.d(TAG, "Heard: $texts")

            if (mode == Mode.TASK && taskManager.isBusy) {
                // While a task runs only "Hey AURA, stop" (or a bare "stop") matters.
                val wantsStop = texts.any { t ->
                    val c = extractCommand(t)
                    (c != null && STOP_REGEX.matches(c)) || t.trim().lowercase(Locale.ROOT) in BARE_STOP
                }
                if (wantsStop) taskManager.emergencyStop()
                scheduleWake(RESTART_MS)
                return
            }
            if (!listeningForWake()) return

            val command = texts.firstNotNullOfOrNull { extractCommand(it) }
            if (command == null) {
                if (wakeHeardInPartial) startCommandCapture(alreadyBeeped = true)
                else scheduleWake(RESTART_MS)
                return
            }
            when {
                command.isBlank() -> startCommandCapture(alreadyBeeped = wakeHeardInPartial)
                STOP_REGEX.matches(command) -> { overlay.hide(); scheduleWake(RESTART_MS) }
                else -> runCommand(command)
            }
        }

        override fun onError(error: Int) {
            if (listeningForWake() && wakeHeardInPartial) {
                // Heard "Hey AURA" but the session ended — ask for the command.
                startCommandCapture(alreadyBeeped = true)
                return
            }
            if (mode != Mode.WAKE && mode != Mode.TASK) return
            if (useOnDevice && error in ON_DEVICE_UNSUPPORTED_ERRORS) {
                // On-device model missing for this language — switch to the online recognizer.
                Log.w(TAG, "On-device recognizer unavailable (error $error) — using online")
                useOnDevice = false
                destroyWakeRecognizer()
                scheduleWake(RESTART_MS)
                return
            }
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_MS
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateNotification("Microphone permission needed — open AURA to allow it")
                    return
                }
                else -> {
                    // Busy / client / network / server / too many requests: back off.
                    errorStreak++
                    destroyWakeRecognizer()
                    (1000L * errorStreak).coerceAtMost(10_000L)
                }
            }
            Log.d(TAG, "Wake session ended (error $error), restarting in ${delayMs}ms")
            scheduleWake(delayMs)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ──────────────────────────────────────────────────────────────
    // Command capture → task
    // ──────────────────────────────────────────────────────────────

    /** Pop up, beep and listen for one command (with live text and one retry). */
    private fun startCommandCapture(alreadyBeeped: Boolean = false) {
        if (taskManager.isBusy && mode == Mode.TASK) {
            overlay.show("I'm still working on the last task", "Tap Stop in AURA's notification to cancel it.",
                AssistantOverlay.Actions(onCancel = { taskManager.emergencyStop() }))
            return
        }
        mode = Mode.COMMAND
        destroyWakeRecognizer()
        hideJob?.cancel()
        flowJob?.cancel()
        flowJob = scope.launch {
            if (!hasMicPermission()) {
                showThenResume("I need microphone permission", "Open AURA and allow the microphone.")
                return@launch
            }
            overlay.show("Listening…", "Say what you want me to do.", actionsClose())
            if (!alreadyBeeped) {
                beep()
                delay(200)  // let our beep finish before the mic opens
            } else {
                delay(120)  // just enough for the wake recognizer to release the mic
            }

            var text: String? = null
            for (attempt in 1..2) {
                muteEarconsBriefly() // we already beeped; skip the system's second beep
                text = voiceManager.recognizeSpeech { partial ->
                    overlay.show("Listening…", "\u201C$partial\u201D", actionsClose())
                }?.trim()
                if (!text.isNullOrBlank() || attempt == 2) break
                // Nothing heard (user started late) — one more try before giving up.
                overlay.show("Still listening…", "Say what you want me to do.", actionsClose())
            }
            val command = text?.let { extractCommand(it) ?: it }?.trim()
            if (command.isNullOrBlank()) {
                overlay.show("Sorry, I didn't catch that", null,
                    AssistantOverlay.Actions(onTalk = { startCommandCapture() }, onClose = { closePanel() }))
                scheduleClose(5000)
                return@launch
            }
            runCommand(command)
        }
    }

    /**
     * Show what was heard with a Cancel button for a moment, then run it — so a
     * misheard command can be stopped before anything happens.
     */
    private fun runCommand(command: String) {
        Log.i(TAG, "Command: \"$command\"")
        mode = Mode.COMMAND
        destroyWakeRecognizer()
        hideJob?.cancel()
        flowJob?.cancel()
        overlay.show("\u201C$command\u201D", "Starting…", AssistantOverlay.Actions(onCancel = { closePanel() }))
        flowJob = scope.launch {
            delay(CONFIRM_HEARD_MS)
            mode = Mode.TASK
            overlay.show("\u201C$command\u201D", "Working on it…", AssistantOverlay.Actions(onClose = { closePanel() }))
            lastHandledState = null
            taskManager.reset()
            updateNotification("Working on: $command")
            taskManager.execute(command)
            // execute() can fail synchronously (e.g. setup problem) — handle that now.
            onTaskState(taskManager.state.value)
            // The wake listener stays OFF during a task: it would hear AURA's own voice and
            // the target app's audio. Stop with the popup / notification instead.
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Task state → popup
    // ──────────────────────────────────────────────────────────────

    private fun onTaskState(state: TaskState) {
        if (mode != Mode.TASK || state === lastHandledState) return
        lastHandledState = state
        when (state) {
            is TaskState.Planning -> overlay.show("Thinking…", null,
                AssistantOverlay.Actions(onCancel = { taskManager.emergencyStop() }))
            is TaskState.Executing -> overlay.showWorking(
                "Step ${state.stepIndex + 1} of ${state.totalSteps}",
                describe(state.currentStep.action, state.currentStep.text ?: state.currentStep.pkg)
            )
            is TaskState.WaitingForUser -> askApproval(state)
            is TaskState.Completed -> finishTask("Done", null, 2500)
            is TaskState.Failed -> finishTask("Couldn't finish", state.reason, 7000)
            is TaskState.Cancelled -> finishTask("Stopped", null, 1500)
            else -> {}
        }
    }

    private fun askApproval(state: TaskState.WaitingForUser) {
        val message = state.riskAction.message ?: "Allow this ${state.riskAction.action.replace('_', ' ')}?"
        val body = listOfNotNull(message, state.anomalyWarning?.let { "⚠ $it" }).joinToString("\n")

        if (state.riskLevel == RiskLevel.CRITICAL) {
            // Needs fingerprint/face — only possible inside AURA's screen.
            overlay.show("Confirm with fingerprint", body, AssistantOverlay.Actions(
                onDeny = { state.onDeny() },
                onOpenApp = { openApp() }
            ))
            return
        }

        overlay.show("Allow this?", "$body\n\nSay \"yes\" or \"no\".", AssistantOverlay.Actions(
            onDeny = { state.onDeny() },
            onAllow = { scope.launch { state.onAllow() } }
        ))
        // Also accept a spoken yes/no.
        destroyWakeRecognizer()
        flowJob?.cancel()
        flowJob = scope.launch {
            waitForSpeechToFinish()
            val answer = voiceManager.recognizeSpeech()?.lowercase(Locale.ROOT).orEmpty()
            if (taskManager.state.value !== state) return@launch // already answered by tap
            when {
                NO_WORDS.any { Regex("\\b$it\\b").containsMatchIn(answer) } -> state.onDeny()
                YES_WORDS.any { Regex("\\b$it\\b").containsMatchIn(answer) } -> state.onAllow()
                else -> { /* unclear — leave the buttons up */ }
            }
            scheduleWake(800)
        }
    }

    private fun finishTask(title: String, body: String?, showMs: Long) {
        flowJob?.cancel()
        overlay.show(title, body, AssistantOverlay.Actions(
            onTalk = { startCommandCapture() },
            onClose = { closePanel() }
        ))
        scheduleClose(showMs)
    }

    private fun scheduleClose(afterMs: Long) {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(afterMs)
            closePanel()
        }
    }

    /** Hide the popup and go back to waiting for "Hey AURA" (or stop if it's off). */
    private fun closePanel() {
        hideJob?.cancel()
        flowJob?.cancel()
        overlay.hide()
        if (taskManager.isBusy) return // task still running; keep TASK mode
        lastHandledState = null
        updateNotification("Say \"Hey AURA\" to activate")
        destroyWakeRecognizer() // a fresh session avoids "recognizer busy"
        if (wakeEnabled) {
            mode = Mode.WAKE
            scheduleWake(400)
        } else {
            mode = Mode.IDLE
            stopSelf()
        }
    }

    private fun showThenResume(title: String, body: String) {
        overlay.show(title, body, actionsClose())
        scheduleClose(5000)
    }

    private fun actionsClose() = AssistantOverlay.Actions(onClose = { closePanel() })

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private suspend fun waitForSpeechToFinish() {
        delay(300)
        var waited = 0
        while (voiceManager.isSpeaking.value && waited < 8000) {
            delay(200); waited += 200
        }
    }

    private fun recognizerIntent(partial: Boolean) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoicePrefs.wakeLanguageTag(this@WakeWordService))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
            // "Hey AURA … open YouTube" with a short pause stays in one session.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

    private fun createWakeRecognizer(): SpeechRecognizer =
        if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

    /**
     * Hide the recognizer's start beep: mute the system stream (and the media stream,
     * but only when nothing is playing, so the user's music never drops out) for a
     * moment, then restore exactly what we muted.
     */
    private fun muteEarconsBriefly() {
        val am = audioManager ?: return
        try {
            val streams = mutableListOf(AudioManager.STREAM_SYSTEM)
            if (!am.isMusicActive) streams += AudioManager.STREAM_MUSIC
            for (st in streams) {
                if (st !in mutedStreams && !am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams += st
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Couldn't mute earcons: ${e.message}")
        }
        unmuteJob?.cancel()
        unmuteJob = scope.launch {
            delay(EARCON_MUTE_MS)
            restoreEarcons()
        }
    }

    private fun restoreEarcons() {
        val am = audioManager ?: return
        for (st in mutedStreams.toList()) {
            try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) { }
        }
        mutedStreams.clear()
    }

    private fun beep() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150) } catch (_: Exception) { }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun describe(action: String, target: String?): String {
        val verb = action.replace('_', ' ').replaceFirstChar { it.uppercase() }
        return if (target.isNullOrBlank()) verb else "$verb: $target"
    }

    // ──────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AURA Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Listens for \"Hey AURA\""
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val talk = PendingIntent.getService(
            this, 1, listenNowIntent(this), PendingIntent.FLAG_IMMUTABLE
        )
        val off = PendingIntent.getService(
            this, 2, Intent(this, WakeWordService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AURA is ready")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(0, "Talk", talk)
            .addAction(0, "Stop", PendingIntent.getService(
                this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP_TASK),
                PendingIntent.FLAG_IMMUTABLE
            ))
            .addAction(0, "Turn off", off)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START_WAKE = "com.aura.assistant.START_WAKE"
        const val ACTION_LISTEN_NOW = "com.aura.assistant.LISTEN_NOW"
        const val ACTION_STOP_TASK  = "com.aura.assistant.STOP_TASK"
        const val ACTION_TURN_OFF   = "com.aura.assistant.TURN_OFF"

        private const val RESTART_MS = 250L
        /** How long system sounds stay muted around a recognizer start. */
        private const val EARCON_MUTE_MS = 700L
        /** SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31). */
        private val ON_DEVICE_UNSUPPORTED_ERRORS = setOf(12, 13)
        /** How long the heard command is shown (with Cancel) before it runs. */
        private const val CONFIRM_HEARD_MS = 1300L

        /** "hey aura", "ok aura", "hi ora", … (speech engines often mishear "aura"). */
        private val WAKE_REGEX = Regex(
            "\\b(hey|hi|hay|ok|okay|hello)[\\s,]+(aura|ora|aurah|oura|orra|aara|aira|aurora)\\b",
            RegexOption.IGNORE_CASE
        )
        /** Hindi-script recognitions of "Hey AURA" (no \\b: it doesn't work with Devanagari). */
        private val WAKE_REGEX_HI = Regex("(हे|है|हाय|ओके)\\s*(ऑरा|ओरा|औरा|आरा)")
        /** Utterance that starts with the name: "Aura, open YouTube". */
        private val NAME_FIRST_REGEX = Regex("^\\s*(aura|aurah|oura)\\b", RegexOption.IGNORE_CASE)
        private val STOP_REGEX = Regex("^\\s*(stop|cancel|abort)\\b.*", RegexOption.IGNORE_CASE)
        private val BARE_STOP = setOf("stop", "cancel", "aura stop", "stop aura")

        private val YES_WORDS = listOf("yes", "yeah", "yep", "confirm", "allow", "ok", "okay", "sure", "go ahead", "do it", "haan", "ha")
        private val NO_WORDS = listOf("no", "nope", "cancel", "deny", "stop", "don't", "dont", "nahi", "mat")

        /**
         * If [text] contains the wake phrase, returns what was said after it
         * ("" if nothing). Returns null if there's no wake phrase.
         */
        fun extractCommand(text: String): String? {
            val match = WAKE_REGEX.find(text) ?: WAKE_REGEX_HI.find(text)
                ?: NAME_FIRST_REGEX.find(text) ?: return null
            return text.substring(match.range.last + 1).trim(' ', ',', '.', '!', '?', ':')
        }

        fun isWakeEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_WAKE_ENABLED, false)

        fun setWakeEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_WAKE_ENABLED, enabled).apply()
        }

        fun listenNowIntent(context: Context) =
            Intent(context, WakeWordService::class.java).setAction(ACTION_LISTEN_NOW)

        /** Turn on always-listening for "Hey AURA". Call while AURA is in the foreground. */
        fun startWake(context: Context): Boolean {
            setWakeEnabled(context, true)
            return start(context, Intent(context, WakeWordService::class.java).setAction(ACTION_START_WAKE))
        }

        /** Listen for one command now (mic button / bubble). Call while AURA is in the foreground. */
        fun listenNow(context: Context): Boolean = start(context, listenNowIntent(context))

        /** Turn off always-listening. */
        fun turnOff(context: Context) {
            setWakeEnabled(context, false)
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        private fun start(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start assistant service: ${e.message}")
            false
        }
    }
}
