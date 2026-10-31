package com.pockettravel.feature.ai

import kotlinx.serialization.Serializable

/** Schema minimo del Chat Completions API, compatibile con OpenAI e provider equivalenti. */
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    // Assente (null, non serializzato: encodeDefaults e' false) per i modelli che non la accettano.
    val temperature: Double? = null,
    // L'app chiede sempre la risposta in streaming (OnlineLlmClient.stream).
    val stream: Boolean = false,
)

@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String,
)
