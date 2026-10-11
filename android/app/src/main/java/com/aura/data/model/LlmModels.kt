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
    val temperature: Float = 0.1f,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    /** "low" | "medium" | "high" — only for reasoning models (gpt-oss). Omitted when null. */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null
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
