package com.aura.llm

import android.util.Log
import com.aura.data.model.ChatMessage
import com.aura.data.model.ChatRequest
import com.aura.data.model.ChatResponse
import com.aura.data.model.ResponseFormat
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GroqClient"

// Retrofit service interface for Groq's OpenAI-compatible endpoint
private interface GroqApiService {
    @POST("openai/v1/chat/completions")
    suspend fun chat(@Body request: ChatRequest): ChatResponse
}

/** A planning failure with a message that is safe to show / speak to the user. */
class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * GroqClient — primary LLM planner with an automatic model cascade.
 *
 * The API key is added per request by the interceptor in `di/NetworkModule.kt`,
 * which reads [ApiKeyStore] (Settings key first, build-time key second).
 *
 * Model cascade (same key, same endpoint). Groq retires models regularly — when a
 * model is shut down, replace it here (see console.groq.com/docs/deprecations):
 *   1. openai/gpt-oss-120b — best reasoning
 *   2. openai/gpt-oss-20b  — faster
 *   3. qwen/qwen3.8-27b    — last resort
 *
 * A 401 (bad key) stops the cascade at once — every model would fail the same way.
 */
@Singleton
class GroqClient @Inject constructor(retrofit: Retrofit) {

    private val api: GroqApiService = retrofit.create(GroqApiService::class.java)

    companion object {
        /** Model cascade — tried in order. Each entry is (modelId, displayName). */
        val MODEL_CASCADE = listOf(
            "openai/gpt-oss-120b" to "GPT-OSS 120B",
            "openai/gpt-oss-20b"  to "GPT-OSS 20B (fast)",
            "qwen/qwen3.8-27b"    to "Qwen3.8 27B"
        )
    }

    /**
     * Plan the steps for [goal] using the current screen text and memory.
     *
     * @throws LlmException with a user-readable message if every model fails.
     */
    suspend fun plan(
        goal: String,
        uiContext: String,
        activeModel: String? = null,
        memoryContext: String = ""
    ): Plan {
        val userContent = PlannerPrompt.userMessage(goal, uiContext, memoryContext)
        val cascade = if (activeModel != null) listOf(activeModel to activeModel) else MODEL_CASCADE
        var lastError: LlmException? = null

        for ((modelId, modelName) in cascade) {
            try {
                Log.i(TAG, "Trying model: $modelName")
                val response = api.chat(
                    ChatRequest(
                        model = modelId,
                        messages = listOf(
                            ChatMessage("system", PlannerPrompt.SYSTEM),
                            ChatMessage("user", userContent)
                        ),
                        temperature = 0.1f,
                        responseFormat = ResponseFormat("json_object"),
                        reasoningEffort = if (modelId.startsWith("openai/gpt-oss")) "low" else null
                    )
                )
                val content = response.choices.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) {
                    lastError = LlmException("The AI model returned an empty answer.")
                    continue
                }
                val plan = PlannerPrompt.parsePlan(content)
                if (plan.steps.isEmpty()) {
                    lastError = LlmException("The AI model's answer couldn't be understood.")
                    continue
                }
                Log.i(TAG, "✅ $modelName returned ${plan.steps.size} steps: ${plan.steps.map { it.action }}")
                return plan

            } catch (e: HttpException) {
                val body = try { e.response()?.errorBody()?.string()?.take(300) } catch (_: Exception) { null }
                Log.w(TAG, "⚠️ $modelName HTTP ${e.code()}: $body")
                when (e.code()) {
                    401, 403 -> throw LlmException("The Groq API key was rejected. Check it in Settings.", e)
                    429 -> lastError = LlmException("Groq rate limit reached. Try again in a minute.", e)
                    400, 404 -> lastError = LlmException("Groq model $modelId isn't available.", e)
                    else -> lastError = LlmException("Groq server error (${e.code()}).", e)
                }
            } catch (e: IOException) {
                Log.w(TAG, "⚠️ $modelName network error: ${e.message}")
                throw LlmException("Couldn't reach Groq. Check your internet connection.", e)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ $modelName failed: ${e.message}")
                lastError = LlmException("Groq request failed: ${e.message}", e)
            }
        }
        throw lastError ?: LlmException("All Groq models failed.")
    }
}
