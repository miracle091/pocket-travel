package com.pockettravel.feature.ai

import kotlinx.serialization.SerialName
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

/**
 * Schema minimo della Messages API nativa di Anthropic (`POST /v1/messages`): `system` al primo livello, `max_tokens`
 * obbligatorio, nessuna temperatura (con `anthropic-version: 2026-10-21` la rifiuta ogni modello).
 */
@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<OpenAiChatMessage>,
    val system: String? = null,
    // Come in OpenAiChatRequest: un valore di default non si serializza, quindi stream = true va passato.
    val stream: Boolean = false,
)
