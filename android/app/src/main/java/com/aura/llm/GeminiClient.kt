package com.aura.llm

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiClient"
private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"

/**
 * Model id. Google retires models regularly — when this one is shut down, replace it
 * (see ai.google.dev/gemini-api/docs/deprecations).
 */
private const val GEMINI_MODEL = "gemini-2.5-flash"

/**
 * GeminiClient — fallback planner, used by TaskManager when Groq fails or has no key.
 *
 * Same prompt and parser as [GroqClient] (see [PlannerPrompt]), so responses match.
 * Key comes from [ApiKeyStore] (Settings first, then GEMINI_API_KEY in local.properties).
 */
@Singleton
class GeminiClient @Inject constructor(
    private val apiKeyStore: ApiKeyStore
) {

    private interface GeminiApiService {
        @POST("v1beta/models/$GEMINI_MODEL:generateContent")
        suspend fun generate(
            @Header("x-goog-api-key") apiKey: String,
            @Body request: GeminiRequest
        ): GeminiResponse
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val api: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(GEMINI_BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiService::class.java)
    }

    fun isConfigured(): Boolean = apiKeyStore.geminiKey().isNotBlank()

    /**
     * Plan the steps for [goal]. Same contract as [GroqClient.plan].
     *
     * @throws LlmException with a user-readable message on failure.
     */
    suspend fun plan(
        goal: String,
        uiContext: String,
        memoryContext: String = ""
    ): Plan {
        val key = apiKeyStore.geminiKey()
        if (key.isBlank()) throw LlmException("No Gemini API key set.")

        try {
            val response = api.generate(
                apiKey = key,
                request = GeminiRequest(
                    systemInstruction = GeminiContent(
                        parts = listOf(GeminiPart(PlannerPrompt.SYSTEM)),
                        role = null
                    ),
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(PlannerPrompt.userMessage(goal, uiContext, memoryContext))
                            )
                        )
                    ),
                    generationConfig = GeminiGenerationConfig(
                        temperature = 0.1f,
                        maxOutputTokens = 2048,
                        responseMimeType = "application/json",
                        thinkingConfig = GeminiThinkingConfig(thinkingBudget = 0)
                    )
                )
            )
            val rawText = response.candidates.firstOrNull()
                ?.content?.parts?.joinToString("") { it.text }
                .orEmpty()
            if (rawText.isBlank()) throw LlmException("Gemini returned an empty answer.")

            val plan = PlannerPrompt.parsePlan(rawText)
            if (plan.steps.isEmpty()) throw LlmException("Gemini's answer couldn't be understood.")
            Log.i(TAG, "✅ Gemini returned ${plan.steps.size} steps: ${plan.steps.map { it.action }}")
            return plan

        } catch (e: LlmException) {
            throw e
        } catch (e: HttpException) {
            val body = try { e.response()?.errorBody()?.string()?.take(300) } catch (_: Exception) { null }
            Log.w(TAG, "Gemini HTTP ${e.code()}: $body")
            throw when (e.code()) {
                400, 401, 403 -> LlmException("The Gemini API key was rejected. Check it in Settings.", e)
                404 -> LlmException("Gemini model $GEMINI_MODEL isn't available.", e)
                429 -> LlmException("Gemini rate limit reached. Try again in a minute.", e)
                else -> LlmException("Gemini server error (${e.code()}).", e)
            }
        } catch (e: IOException) {
            throw LlmException("Couldn't reach Gemini. Check your internet connection.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini failed: ${e.message}", e)
            throw LlmException("Gemini request failed: ${e.message}", e)
        }
    }
}

// ── Gemini API models ─────────────────────────────────────────

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig,
    val systemInstruction: GeminiContent? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
    val role: String? = "user"
)

@Serializable
data class GeminiPart(val text: String = "")

@Serializable
data class GeminiGenerationConfig(
    val temperature: Float = 0.1f,
    val maxOutputTokens: Int = 2048,
    val responseMimeType: String? = null,
    /** 0 = no hidden "thinking" tokens: faster, and the JSON isn't cut off by the token limit. */
    val thinkingConfig: GeminiThinkingConfig? = null
)

@Serializable
data class GeminiThinkingConfig(val thinkingBudget: Int = 0)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)
