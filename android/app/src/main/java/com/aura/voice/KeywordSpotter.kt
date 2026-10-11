package com.aura.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeywordSpotter"

/**
 * KeywordSpotter — offline, in-process listening for a few words (live tasks).
 *
 * Why not Android's SpeechRecognizer: it records inside Google's app, which Android
 * silences while another app (e.g. the camera) records video, and it's slow and beeps.
 * This uses Vosk with its own [AudioRecord] in AURA's process; while an AURA accessibility
 * overlay is on screen Android lets both AURA and the foreground app hear the mic.
 *
 * - The model (Indian English, ~36 MB) downloads once on first use ([ensureModel]).
 * - [listen] recognizes only the given words (a grammar), so it's fast and ignores other speech.
 * - If Android still silences AURA (some camera apps block all other recording), a
 *   [Heard.Silenced] is emitted so the user is told instead of nothing happening.
 *
 * Audio is never stored or sent anywhere.
 */
@Singleton
class KeywordSpotter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class ModelState {
        data object Missing : ModelState()
        data class Downloading(val percent: Int) : ModelState()
        data object Ready : ModelState()
        data class Failed(val reason: String) : ModelState()
    }

    /** What the recognizer produced. */
    sealed class Heard {
        /** [text] grows while you speak ([isFinal] = false), then the phrase ends (true). */
        data class Text(val text: String, val isFinal: Boolean) : Heard()
        /** Android stopped giving AURA audio (another app took the mic exclusively), or gave it back. */
        data class Silenced(val silenced: Boolean) : Heard()
        data class Error(val reason: String) : Heard()
    }

    private val modelDir = File(context.filesDir, "vosk/$MODEL_NAME")
    private val _modelState = MutableStateFlow<ModelState>(
        if (isModelPresent()) ModelState.Ready else ModelState.Missing
    )
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val loadLock = Mutex()
    @Volatile private var model: Model? = null

    /**
     * True if the model knows [word] (so a grammar with it can match). Models without a
     * word list are assumed to know it.
     */
    fun knowsWord(word: String): Boolean {
        val list = listOf(File(modelDir, "graph/words.txt"), File(modelDir, "words.txt")).firstOrNull { it.isFile }
            ?: return isModelPresent()
        val w = word.lowercase()
        return try {
            list.useLines { lines -> lines.any { it.substringBefore(' ') == w } }
        } catch (_: Exception) {
            false
        }
    }

    private fun isModelPresent() = File(modelDir, "am").isDirectory || File(modelDir, "conf").isDirectory

    // ──────────────────────────────────────────────────────────────
    // Model
    // ──────────────────────────────────────────────────────────────

    /** Download and unpack the model if needed. Returns true when it's ready to use. */
    suspend fun ensureModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelPresent()) {
            _modelState.value = ModelState.Ready
            return@withContext true
        }
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        val zip = File(root, "$MODEL_NAME.zip.part")
        val unpacked = File(root, "$MODEL_NAME.tmp")
        try {
            _modelState.value = ModelState.Downloading(0)
            Log.i(TAG, "Downloading voice model $MODEL_URL")
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: MODEL_BYTES_APPROX
                body.byteStream().use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) { lastPct = pct; _modelState.value = ModelState.Downloading(pct) }
                        }
                    }
                }
            }
            unpacked.deleteRecursively()
            unzip(zip, unpacked)
            // The zip holds one top folder (vosk-model-small-en-in-0.4/…); that's the model.
            val top = unpacked.listFiles()?.singleOrNull { it.isDirectory } ?: unpacked
            modelDir.deleteRecursively()
            if (!top.renameTo(modelDir)) throw IllegalStateException("couldn't move the model")
            _modelState.value = ModelState.Ready
            Log.i(TAG, "Voice model ready at $modelDir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Voice model download failed: ${e.message}", e)
            _modelState.value = ModelState.Failed(e.message ?: "download failed")
            false
        } finally {
            zip.delete()
            unpacked.deleteRecursively()
        }
    }

    private fun unzip(zip: File, dest: File) {
        val base = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(dest, entry.name)
                // Never write outside dest ("zip slip").
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("bad zip entry ${entry.name}")
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
            }
        }
    }

    private suspend fun loadModel(): Model? = loadLock.withLock {
        model?.let { return it }
        if (!isModelPresent()) return null
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(modelDir.absolutePath).also { model = it }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't load the voice model: ${e.message}", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Listening
    // ──────────────────────────────────────────────────────────────

    /**
     * Listen for [vocabulary] (words or short phrases) until the flow is cancelled.
     * Only one listener at a time; the caller must keep an AURA overlay visible.
     */
    fun listen(vocabulary: List<String>): Flow<Heard> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            send(Heard.Error("AURA needs microphone permission."))
            close(); return@callbackFlow
        }
        val m = loadModel()
        if (m == null) {
            send(Heard.Error("The offline voice model isn't ready."))
            close(); return@callbackFlow
        }
        val grammar = JsonArray((vocabulary.map { it.lowercase().trim() }.filter { it.isNotEmpty() } + "[unk]")
            .distinct().map { JsonPrimitive(it) }).toString()
        val recognizer = Recognizer(m, SAMPLE_RATE.toFloat(), grammar)

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // ~200 ms of 16-bit audio
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, chunk * 2
            )
        } catch (e: SecurityException) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            recognizer.close()
            send(Heard.Error("The microphone is busy."))
            close(); return@callbackFlow
        }

        val callbackExecutor = Executors.newSingleThreadExecutor()
        val silenceCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }?.let {
                    trySend(Heard.Silenced(it.isClientSilenced))
                }
            }
        }
        record.registerAudioRecordingCallback(callbackExecutor, silenceCallback)

        val loop = launch(Dispatchers.IO) {
            val buf = ByteArray(chunk)
            var lastPartial = ""
            try {
                record.startRecording()
                Log.i(TAG, "Listening for $vocabulary")
                while (isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) {
                        trySend(Heard.Error("The microphone stopped (error $n)."))
                        break
                    }
                    if (n == 0) continue
                    if (recognizer.acceptWaveForm(buf, n)) {
                        val text = field(recognizer.result, "text")
                        lastPartial = ""
                        if (text.isNotBlank()) trySend(Heard.Text(text, isFinal = true))
                    } else {
                        val partial = field(recognizer.partialResult, "partial")
                        if (partial != lastPartial) {
                            lastPartial = partial
                            if (partial.isNotBlank()) trySend(Heard.Text(partial, isFinal = false))
                        }
                    }
                }
            } catch (e: Exception) {
                trySend(Heard.Error(e.message ?: "listening failed"))
            } finally {
                try { record.stop() } catch (_: Exception) { }
                record.unregisterAudioRecordingCallback(silenceCallback)
                record.release()
                recognizer.close()
                callbackExecutor.shutdown()
                Log.i(TAG, "Stopped listening")
                close()
            }
        }
        awaitClose {
            loop.cancel()
            try { record.stop() } catch (_: Exception) { } // unblocks read()
        }
    }.flowOn(Dispatchers.IO)

    private fun field(json: String, key: String): String = try {
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content.orEmpty()
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        const val MODEL_NAME = "vosk-model-small-en-in-0.4"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        /** Used for the progress bar when the server doesn't send a size. */
        private const val MODEL_BYTES_APPROX = 36L * 1024 * 1024
    }
}
