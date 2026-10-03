package com.aura.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

private const val TAG = "WakeWordService"
private const val CHANNEL_ID = "aura_wake_word"
private const val NOTIF_ID = 2001

/**
 * WakeWordService — continuously listens for "Hey AURA" in the background.
 *
 * Phase 4 implementation — two modes:
 *
 * MODE A (current): Android SpeechRecognizer in continuous loop.
 *   - Uses online recognition with Google Cloud STT.
 *   - Restarts automatically after each timeout/result.
 *   - Detects WAKE_WORDS list — fires [onWakeWord] when found.
 *   - Works without any paid API key.
 *
 * MODE B (planned): Picovoice Porcupine (ultra-low power, offline).
 *   - 10x more battery efficient than MODE A.
 *   - Requires a free Porcupine AccessKey from Picovoice Console.
 *   - Add dependency: implementation("ai.picovoice:porcupine-android:3.0.1")
 *   - Replace the recognition loop with Porcupine.Builder().build() in onCreate()
 *
 * Communication:
 *   - Broadcasts Intent(ACTION_WAKE_WORD_DETECTED) to MainActivity / HomeViewModel
 *   - HomeViewModel calls taskManager.execute() after receiving the broadcast
 *
 * Lifecycle:
 *   - Started as a foreground service by [AURAForegroundService]
 *   - Stopped when user disables AURA or device runs low on battery
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject lateinit var voiceManager: VoiceManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var isRunning = false

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "WakeWordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startListeningLoop()
        }
        return START_STICKY // restart automatically if killed
    }

    override fun onDestroy() {
        isRunning = false
        stopListening()
        scope.cancel()
        Log.i(TAG, "WakeWordService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────
    // Listening loop
    // ──────────────────────────────────────────────────────────────

    /**
     * Runs a continuous listen-restart loop.
     * SpeechRecognizer times out after ~5s of silence and must be restarted.
     */
    private fun startListeningLoop() {
        scope.launch {
            while (isRunning) {
                startListening()
                delay(RESTART_DELAY_MS) // tiny gap before restarting
            }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            return
        }

        stopListening()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(wakeWordListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            recognizer?.startListening(intent)
            Log.d(TAG, "👂 Listening for wake word...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
        }
    }

    private fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
    }

    // ──────────────────────────────────────────────────────────────
    // Recognition listener
    // ──────────────────────────────────────────────────────────────

    private val wakeWordListener = object : RecognitionListener {
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase(Locale.getDefault())
                ?: return
            if (WAKE_WORDS.any { partial.contains(it) }) {
                Log.i(TAG, "🔔 Wake word detected (partial): \"$partial\"")
                onWakeWord()
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase(Locale.getDefault())
                ?: return

            Log.d(TAG, "Result: \"$text\"")

            when {
                STOP_WORDS.any { text.contains(it) } -> {
                    Log.i(TAG, "🛑 Stop command detected: \"$text\"")
                    broadcastStop()
                }
                WAKE_WORDS.any { text.contains(it) } -> {
                    Log.i(TAG, "🔔 Wake word detected: \"$text\"")
                    onWakeWord()
                }
            }
        }

        override fun onError(error: Int) {
            // ERROR_NO_MATCH and ERROR_SPEECH_TIMEOUT are normal — just restart
            Log.d(TAG, "STT error $error — will restart loop")
        }

        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    // ──────────────────────────────────────────────────────────────
    // Wake + Stop actions
    // ──────────────────────────────────────────────────────────────

    private fun onWakeWord() {
        stopListening() // stop the loop while processing command
        voiceManager.speak("Yes?")
        broadcastWakeWord()
        // Resume loop after brief delay (voice manager will restart when HomeViewModel is done)
        scope.launch {
            delay(500)
            if (isRunning) startListeningLoop()
        }
    }

    private fun broadcastWakeWord() {
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.i(TAG, "📡 Broadcast: ACTION_WAKE_WORD_DETECTED")
    }

    private fun broadcastStop() {
        val intent = Intent(ACTION_STOP_COMMAND).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        voiceManager.speak("Stopping.")
        Log.i(TAG, "📡 Broadcast: ACTION_STOP_COMMAND")
    }

    // ──────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AURA Wake Word Listener",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Listens for 'Hey AURA' in the background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AURA Listening")
            .setContentText("Say \"Hey AURA\" to activate")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_WAKE_WORD_DETECTED = "com.aura.WAKE_WORD_DETECTED"
        const val ACTION_STOP_COMMAND       = "com.aura.STOP_COMMAND"

        /** Words that activate AURA */
        private val WAKE_WORDS = setOf("hey aura", "hi aura", "okay aura", "aura")

        /** Voice emergency stop commands */
        private val STOP_WORDS = setOf("hey aura stop", "aura stop", "stop aura", "emergency stop")

        private const val RESTART_DELAY_MS = 300L

        fun startIntent(context: Context) =
            Intent(context, WakeWordService::class.java)
    }
}
