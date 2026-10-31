package com.pockettravel.feature.ai

import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse

// Risposte SSE per i test del motore Online, nel formato visto da Mistral e Gemini il 2026-10-31.

/** Un pezzo "data:" per ogni testo; [finishReason] nell'ultimo (Mistral), poi [DONE] se [done]. */
internal fun sseBody(vararg pieces: String, finishReason: String? = "stop", done: Boolean = true): String = buildString {
    pieces.forEachIndexed { index, piece ->
        val finish = if (index == pieces.lastIndex && finishReason != null) "\"$finishReason\"" else "null"
        append("data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$piece\"},\"finish_reason\":$finish}]}\n\n")
    }
    if (done) append("data: [DONE]\n\n")
}

internal fun sseResponse(vararg pieces: String, finishReason: String? = "stop", done: Boolean = true): MockResponse =
    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sseBody(*pieces, finishReason = finishReason, done = done))

/** Tutto il testo della risposta in streaming. */
internal suspend fun OnlineLlmClient.answerText(
    baseUrl: String,
    apiKey: String,
    model: String,
    prompt: String,
    sendTemperature: Boolean,
    system: String? = null,
): String = stream(baseUrl, apiKey, model, prompt, sendTemperature, system).toList().joinToString("")
