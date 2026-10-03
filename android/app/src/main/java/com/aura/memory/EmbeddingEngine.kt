package com.aura.memory

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val TAG = "EmbeddingEngine"

/**
 * EmbeddingEngine — converts text to 384-dimensional semantic vectors.
 *
 * Phase 5 implementation — two modes:
 *
 * MODE A (current — lightweight TF-IDF approximation):
 *   Works offline immediately, no model download needed.
 *   Uses a hash-based bag-of-words that approximates semantic similarity
 *   well enough for keyword-based memory retrieval.
 *   Suitable for MVP — retrieves "send money to Rahul" when queried with
 *   "pay Rahul" because both share the Rahul token.
 *
 * MODE B (planned — ONNX all-MiniLM-L6-v2):
 *   True 384-dim sentence embeddings, proper semantic similarity.
 *   Add dependency: implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
 *   Download model: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
 *   ~25MB model, works fully offline after download.
 *
 * API: embed(text) → FloatArray(384), serialized as comma-separated string in Room.
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val EMBEDDING_DIM = 384
        private val STOP_WORDS = setOf(
            "a", "an", "the", "is", "in", "on", "at", "to", "for",
            "and", "or", "but", "my", "me", "i", "it", "this", "that",
            "with", "from", "of", "do", "can", "please", "hey", "aura"
        )
    }

    /**
     * Generate a semantic embedding for [text].
     * Returns a 384-dim FloatArray.
     *
     * Current: lightweight hash-based approximation.
     * Future: swap body to ONNX inference for true semantic vectors.
     */
    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        hashEmbed(text.lowercase().trim())
    }

    /**
     * Cosine similarity between two embeddings.
     * Returns 0.0..1.0 where 1.0 = identical meaning.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    /** Serialize a FloatArray to a comma-separated string for Room storage. */
    fun serialize(embedding: FloatArray): String =
        embedding.joinToString(",")

    /** Deserialize a comma-separated string back to FloatArray. */
    fun deserialize(str: String): FloatArray {
        if (str.isBlank()) return FloatArray(EMBEDDING_DIM)
        return try {
            str.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize embedding: ${e.message}")
            FloatArray(EMBEDDING_DIM)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Hash-based embedding approximation (MODE A)
    // ──────────────────────────────────────────────────────────────

    private fun hashEmbed(text: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val tokens = tokenize(text)

        for (token in tokens) {
            // Map each token to multiple positions in the vector (hashing trick)
            val h1 = (token.hashCode() and Int.MAX_VALUE) % EMBEDDING_DIM
            val h2 = ((token.hashCode() * 31 + 7) and Int.MAX_VALUE) % EMBEDDING_DIM
            val h3 = ((token.hashCode() * 1009 + 37) and Int.MAX_VALUE) % EMBEDDING_DIM
            vec[h1] += 1f
            vec[h2] += 0.7f
            vec[h3] += 0.4f
        }

        // L2 normalize
        val norm = sqrt(vec.map { it * it }.sum())
        if (norm > 0f) { for (i in vec.indices) vec[i] /= norm }

        return vec
    }

    private fun tokenize(text: String): List<String> {
        val words = text.split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOP_WORDS }

        // Add bigrams for phrase awareness
        val bigrams = words.zipWithNext { a, b -> "${a}_$b" }
        return words + bigrams
    }
}
