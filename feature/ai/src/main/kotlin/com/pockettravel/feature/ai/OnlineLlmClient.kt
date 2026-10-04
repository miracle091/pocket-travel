package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
                .header("Authorization", "Bearer $apiKey")
                .post(json.encodeToString(OpenAiChatRequest.serializer(), requestBody).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            // Risposta non in streaming: il server non manda nulla finche' non ha finito, e i 10 s di
            // default di OkHttp non bastano ai modelli piu' lenti.
            okHttpClient.newBuilder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
                .newCall(request).await().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        throw OnlineApiKeyRejectedException("Chiave API rifiutata (HTTP ${response.code})")
                    }
                    if (isModelNotFound(response.code, responseBody, json)) {
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

// Un 400 e' "modello non disponibile" solo se il corpo dice model_not_found (error.code o error.type):
// citare la parola "model" non basta, un 400 qualunque (richiesta malformata) la contiene spesso.
internal fun isModelNotFound(statusCode: Int, responseBody: String, json: Json): Boolean = when (statusCode) {
    HTTP_NOT_FOUND -> true
    HTTP_BAD_REQUEST -> runCatching {
        val error = json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonObject
        listOf("code", "type").any { error?.get(it)?.jsonPrimitive?.contentOrNull == "model_not_found" }
    }.getOrDefault(false)
    else -> false
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_NOT_FOUND = 404

// execute() bloccherebbe il thread ignorando la cancellazione (una risposta da 120 s andrebbe attesa
// per intero): enqueue + cancel() chiude la connessione appena la coroutine viene annullata.
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isCancelled) response.close() else continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
