package com.pockettravel.feature.ai

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_AUTO
import com.pockettravel.core.sync.OnlineModelEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Modelli che la chiave dell'utente puo' usare, letti dall'elenco dei modelli del servizio (GET `/models`):
 * servono a nascondere quelli proposti che il suo account non vede (riservati, ritirati, di un'altra area).
 * Claude non documenta `/models` nell'endpoint compatibile con OpenAI: per lui si usa l'API nativa, con la
 * chiave in `x-api-key`. Un errore lancia un'eccezione: chi chiama allora non filtra.
 */
class OnlineModelAvailability @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** [baseUrl]: diverso da quello del servizio solo nei test. */
    suspend fun availableModels(provider: OnlineProvider, apiKey: String, baseUrl: String = provider.baseUrl): Set<String> = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/models"
        val request = when (provider) {
            // 20 modelli per pagina se non si chiede altro.
            OnlineProvider.CLAUDE -> Request.Builder().url("$url?limit=1000")
                .header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
            OnlineProvider.CHATGPT, OnlineProvider.MISTRAL, OnlineProvider.GEMINI -> Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
        }.build()
        // Senza redirect: OkHttp toglie Authorization verso un altro host, non x-api-key. Un 3xx vale come errore.
        okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Elenco dei modelli non disponibile (HTTP ${response.code})")
            parseModelIds(json, response.body.string())
        }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/**
 * Nomi dei modelli in una risposta `/models` (`{"data": [{"id": ...}]}`, uguale per tutti e quattro i servizi);
 * Gemini li scrive come `models/<nome>`.
 */
internal fun parseModelIds(json: Json, body: String): Set<String> =
    (json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: throw IOException("Risposta /models senza data"))
        .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
        .toSet()

/**
 * I modelli di [models] da mostrare con i nomi disponibili [available] (null: elenco non letto, si mostrano tutti).
 * Il modello di Automatico resta sempre, come quelli con un nome che il servizio aggiorna da solo ("-latest"),
 * che l'elenco puo' non riportare. Un nome vale disponibile anche se l'elenco ne riporta solo le versioni con la
 * data (`claude-sonnet-5-5-20261001` per `claude-sonnet-5-5`).
 */
private const val DATE_DIGITS = 8
private const val DATE_SUFFIX = DATE_DIGITS + 1

internal fun visibleModels(models: List<OnlineModelEntry>, available: Set<String>?): List<OnlineModelEntry> =
    if (available == null) {
        models
    } else {
        models.filter { model ->
            model.role == ONLINE_MODEL_ROLE_AUTO || model.id.endsWith("-latest") ||
                available.any { it == model.id || (it.startsWith("${model.id}-") && it.length == model.id.length + DATE_SUFFIX && it.takeLast(DATE_DIGITS).all(Char::isDigit)) }
        }
    }
