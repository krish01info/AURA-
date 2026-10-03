package com.aura.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aura.agent.RiskLevel
import com.aura.data.model.ActionStep
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "VoiceManager"

/**
 * VoiceManager — Phase 4 voice interface.
 *
 * Responsibilities:
 *  1. TTS narration — speaks what AURA is doing ("Opening WhatsApp...")
 *  2. Speech recognition — converts user speech to text
 *  3. Voice confirmation — "Say 'confirm' or 'cancel'" for HIGH risk actions
 *
 * Wake word detection is handled separately by [WakeWordService].
 * Offline STT fallback is handled by [VoskEngine].
 *
 * TTS is initialised eagerly on first injection so it is ready instantly.
 */
@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // ──────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.i(TAG, "TTS initialised — ready=$isTtsReady")
            } else {
                Log.e(TAG, "TTS init failed with status=$status")
            }
        }
    }

    /**
     * Speak [text] asynchronously. Interrupts any currently speaking text.
     * Safe to call from any thread.
     */
    fun speak(text: String, flush: Boolean = true) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready — skipping: $text")
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _isSpeaking.value = false }
        })
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, utteranceId)
        Log.d(TAG, "Speaking: \"$text\"")
    }

    /** Stop any current TTS speech immediately. */
    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /**
     * Narrate what the agent is currently doing.
     * Called by TaskManager at each step.
     */
    fun narrateStep(step: ActionStep) {
        val narration = when (step.action) {
            ActionStep.OPEN_APP   -> "Opening ${step.pkg?.split(".")?.lastOrNull() ?: "app"}"
            ActionStep.FIND_ELEMENT -> "Looking for ${step.text ?: "element"}"
            ActionStep.TAP        -> "Tapping ${step.text ?: step.viewId ?: "button"}"
            ActionStep.TYPE       -> "Typing text"
            ActionStep.SCROLL     -> "Scrolling"
            ActionStep.SWIPE      -> "Swiping"
            ActionStep.WAIT       -> "Waiting"
            ActionStep.BACK       -> "Going back"
            ActionStep.HOME       -> "Going home"
            ActionStep.CONFIRM_SEND -> "Ready to send — waiting for your approval"
            ActionStep.READ_TEXT  -> "Reading screen content"
            else                  -> step.action.replace("_", " ")
        }
        speak(narration, flush = false)
    }

    /**
     * Announce that the agent is waiting for user confirmation on a risk action.
     */
    fun announceConfirmation(step: ActionStep, riskLevel: RiskLevel) {
        val message = step.message ?: "Allow this ${step.action} action?"
        val prefix = if (riskLevel == RiskLevel.CRITICAL) {
            "Critical action requires your approval. "
        } else {
            "Waiting for confirmation. "
        }
        speak(prefix + message + " Say confirm or cancel.")
    }

    /**
     * Announce task completion.
     */
    fun announceCompletion(summary: String = "Task completed") {
        speak(summary)
    }

    /**
     * Announce task failure.
     */
    fun announceFailure(reason: String) {
        speak("Task failed. $reason")
    }

    // ──────────────────────────────────────────────────────────────
    // Speech Recognition
    // ──────────────────────────────────────────────────────────────

    /**
     * Start one-shot speech recognition and return the transcribed text.
     * Returns null if recognition fails or is cancelled.
     *
     * Uses Android SpeechRecognizer (online). Falls back to [VoskEngine]
     * when the device is offline (handled by [HomeViewModel]).
     */
    suspend fun recognizeSpeech(): String? = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "SpeechRecognizer not available")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        _isListening.value = true

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "🎤 Ready for speech")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()
                Log.i(TAG, "✅ Speech recognised: \"$transcript\"")
                _isListening.value = false
                recognizer.destroy()
                if (cont.isActive) cont.resume(transcript)
            }

            override fun onError(error: Int) {
                val msg = sttErrorMessage(error)
                Log.w(TAG, "STT error [$error]: $msg")
                _isListening.value = false
                recognizer.destroy()
                if (cont.isActive) cont.resume(null)
            }

            override fun onBeginningOfSpeech() { Log.d(TAG, "Speech started") }
            override fun onEndOfSpeech() { Log.d(TAG, "Speech ended") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)

        cont.invokeOnCancellation {
            recognizer.stopListening()
            recognizer.destroy()
            _isListening.value = false
        }
    }

    /**
     * Listen for a voice confirmation ("confirm" / "cancel") for a risk action.
     *
     * @return true if user said a confirm word, false for cancel/timeout.
     */
    suspend fun listenForConfirmation(): Boolean {
        val transcript = recognizeSpeech()?.lowercase(Locale.getDefault()) ?: return false
        return CONFIRM_WORDS.any { transcript.contains(it) }
            && CANCEL_WORDS.none { transcript.contains(it) }
    }

    // ──────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun sttErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO              -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT             -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK            -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH           -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER             -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "Speech timeout"
        else                                      -> "Unknown error $error"
    }

    companion object {
        private val CONFIRM_WORDS = setOf("confirm", "yes", "allow", "proceed", "ok", "okay", "sure", "go")
        private val CANCEL_WORDS  = setOf("cancel", "no", "deny", "stop", "abort", "don't")
    }
}
