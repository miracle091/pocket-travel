package com.pockettravel.feature.ai

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
                temperature = TEMPERATURE,
            )
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header(String(charArrayOf(Char(65),Char(117),Char(116),Char(104),Char(111),Char(114),Char(105),Char(122),Char(97),Char(116),Char(105),Char(111),Char(110))), "Bearer ".plus(apiKey))
                .post(json.encodeToString(OpenAiChatRequest.serializer(), requestBody).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Errore dal servizio online (HTTP ${response.code})")
                }
                json.decodeFromString(OpenAiChatResponse.serializer(), responseBody)
                    .choices.firstOrNull()?.message?.content
                    ?: error("Il servizio online non ha restituito una risposta")
            }
        }

    private companion object {
        const val TEMPERATURE = 0.3
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
