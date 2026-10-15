package com.aura.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ──────────────────────────────────────────────────────────────────────────────
// Groq / OpenAI-compatible API models
// ──────────────────────────────────────────────────────────────────────────────

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.6f,
    @SerialName("top_p") val topP: Float? = null,
    /** Includes the model's hidden reasoning tokens — too low and the JSON gets cut off. */
    @SerialName("max_completion_tokens") val maxTokens: Int = 4096,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    /** gpt-oss: "low" | "medium" | "high" · Qwen 3.8: "none" | "default" | "low" | "medium" | "high". Omitted when null. */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** gpt-oss only: false = don't send the reasoning text back. */
    @SerialName("include_reasoning") val includeReasoning: Boolean? = null,
    /** Other reasoning models (Qwen): "hidden" = answer only. With JSON mode Groq rejects the
     *  default "raw" format. gpt-oss rejects this field, and it can't be combined with include_reasoning. */
    @SerialName("reasoning_format") val reasoningFormat: String? = null
)

@Serializable
data class ChatMessage(
    val role: String,    // "system" | "user" | "assistant"
    val content: String? = null  // nullable: reasoning models can reply with no content
)

@Serializable
data class ResponseFormat(
    val type: String = "json_object"  // forces JSON output from Groq
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)
