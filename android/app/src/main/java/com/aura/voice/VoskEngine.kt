package com.aura.voice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoskEngine"

/**
 * VoskEngine — offline STT fallback using the Vosk library.
 *
 * Phase 4 implementation notes:
 * ─────────────────────────────
 * Vosk runs fully on-device (no internet required). It uses a small ~50MB model
 * (vosk-model-small-en-us) bundled in assets or downloaded on first launch.
 *
 * Integration status:
 *  - The Vosk AAR dependency is NOT yet in build.gradle — add when ready to
 *    enable offline mode:
 *      implementation("com.alphacephei:vosk-android:0.3.47")
 *
 * Current behaviour:
 *  - [isModelReady] returns false until the model is downloaded.
 *  - [recognizeSpeech] returns null (no-op) if model is not ready.
 *  - [HomeViewModel] falls back to online SpeechRecognizer automatically.
 *
 * To enable:
 *  1. Add the Vosk dependency to build.gradle.kts
 *  2. Download model via [downloadModelIfNeeded]
 *  3. Uncomment the Vosk API calls in [recognizeSpeech]
 */
@Singleton
class VoskEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelDir = File(context.filesDir, "vosk-model")

    /** Returns true if the Vosk model is downloaded and ready. */
    val isModelReady: Boolean
        get() = modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true

    /**
     * Download the Vosk small model if not already present.
     * Model URL: https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
     *
     * Call this once from a background coroutine (e.g. on first launch in Settings).
     */
    suspend fun downloadModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (isModelReady) {
            Log.i(TAG, "Vosk model already present at ${modelDir.absolutePath}")
            return@withContext true
        }

        Log.i(TAG, "Vosk model not found — download required")
        // TODO Phase 4 — implement actual download + unzip:
        // 1. Download ZIP from alphacephei CDN
        // 2. Unzip to modelDir
        // 3. Return true on success
        false
    }

    /**
     * Recognise speech from audio data using the Vosk model.
     *
     * Currently a stub — returns null until Vosk AAR is added.
     * Called by [HomeViewModel.startVoiceInput] when offline.
     */
    suspend fun recognizeSpeech(): String? {
        if (!isModelReady) {
            Log.w(TAG, "Vosk model not ready — skipping offline recognition")
            return null
        }

        // TODO Phase 4 — uncomment after adding Vosk AAR dependency:
        //
        // return withContext(Dispatchers.IO) {
        //     val model = Model(modelDir.absolutePath)
        //     val recognizer = Recognizer(model, 16000.0f)
        //     // feed audio bytes from microphone in loop
        //     // return recognizer.finalResult()
        // }

        Log.d(TAG, "Vosk stub — Vosk AAR not yet added to build.gradle")
        return null
    }
}
