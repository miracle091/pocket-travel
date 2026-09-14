package com.pockettravel.feature.ai

import kotlinx.serialization.Serializable

/** Schema minimo del Chat Completions API, compatibile con OpenAI e provider equivalenti. */
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    val temperature: Double,
)

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OpenAiChatResponse(
    val choices: List<OpenAiChatChoice> = emptyList(),
)

@Serializable
data class OpenAiChatChoice(
    val message: OpenAiChatMessage,
)
