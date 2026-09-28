package com.pockettravel.feature.ai

import kotlinx.serialization.Serializable

/** Schema minimo del Chat Completions API, compatibile con OpenAI e provider equivalenti. */
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    // Assente (null, non serializzato: encodeDefaults e' false) per i modelli che non la accettano.
    val temperature: Double? = null,
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
