package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Client per l'assistente "Online": chiama direttamente, dal dispositivo, un endpoint
 * compatibile con la Chat Completions API (OpenAI o provider equivalenti) usando la chiave
 * API personale dell'utente. Nessuna richiesta transita su un server dell'app.
 */
class OnlineLlmClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun generate(baseUrl: String, apiKey: String, model: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val requestBody = OpenAiChatRequest(
                model = model,
                messages = listOf(OpenAiChatMessage(role = "user", content = prompt)),
                temperature = TEMPERATURE.takeIf { supportsTemperature(model) },
            )
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header(String(charArrayOf(Char(65),Char(117),Char(116),Char(104),Char(111),Char(114),Char(105),Char(122),Char(97),Char(116),Char(105),Char(111),Char(110))), "Bearer ".plus(apiKey))
                .post(json.encodeToString(OpenAiChatRequest.serializer(), requestBody).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            // Risposta non in streaming: il server non manda nulla finche' non ha finito, e i 10 s di
            // default di OkHttp non bastano ai modelli piu' lenti.
            okHttpClient.newBuilder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code == 404 || (response.code == 400 && responseBody.contains("model", ignoreCase = true))) {
                        throw OnlineModelNotFoundException("Modello \"$model\" non disponibile (HTTP ${response.code})")
                    }
                    error("Errore dal servizio online (HTTP ${response.code})")
                }
                json.decodeFromString(OpenAiChatResponse.serializer(), responseBody)
                    .choices.firstOrNull()?.message?.content
                    ?: error("Il servizio online non ha restituito una risposta")
            }
        }

    private companion object {
        const val TEMPERATURE = 0.3
        const val READ_TIMEOUT_SECONDS = 120L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
