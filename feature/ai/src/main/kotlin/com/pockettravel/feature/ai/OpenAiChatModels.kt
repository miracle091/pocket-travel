package com.pockettravel.feature.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Schema minimo del Chat Completions API, compatibile con OpenAI e provider equivalenti. */
@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiChatMessage>,
    // Assente (null, non serializzato: encodeDefaults e' false) per i modelli che non la accettano.
    val temperature: Double? = null,
    // L'app chiede sempre la risposta in streaming (OnlineLlmClient.stream).
    val stream: Boolean = false,
    // Solo per OpenRouter: la domanda va soltanto ai fornitori che non la conservano.
    val provider: OpenRouterRouting? = null,
)

/** Filtro di instradamento di OpenRouter: `zdr` = solo fornitori senza conservazione dei dati. */
@Serializable
data class OpenRouterRouting(val zdr: Boolean)

/** [content]: una stringa, o con una foto la lista delle parti (chatCompletionsContent, anthropicContent). */
@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: JsonElement,
) {
    constructor(role: String, content: String) : this(role, JsonPrimitive(content))
}

/** Il messaggio user del Chat Completions API: solo testo, o il testo e la foto JPEG come data URI (`image_url`). */
internal fun chatCompletionsContent(text: String, photo: ByteArray?): JsonElement =
    if (photo == null) {
        JsonPrimitive(text)
    } else {
        buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(photo)) }
            }
        }
    }

/** Il messaggio user della Messages API: solo testo, o la foto JPEG in base64 prima del testo (ordine consigliato da Anthropic). */
internal fun anthropicContent(text: String, photo: ByteArray?): JsonElement =
    if (photo == null) {
        JsonPrimitive(text)
    } else {
        buildJsonArray {
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", Base64.getEncoder().encodeToString(photo))
                }
            }
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
    }

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
