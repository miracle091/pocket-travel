package com.pockettravel.feature.ai

import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * La risposta in streaming (`stream: true`), un pezzo di testo alla volta; il flusso finisce con la risposta completa.
     * [prompt] e' il messaggio user; [system], se c'e', un solo messaggio system prima di lui.
     * [sendTemperature]: false per i modelli che rifiutano una temperatura impostata (OnlineModelCatalog.sendsTemperature).
     * Un errore temporaneo prima del primo pezzo si riprova una volta; dopo, il testo gia' mostrato non si puo' rifare.
     * La cancellazione del collector chiude la connessione. [api]: il formato del servizio ([OnlineProvider.api]); con
     * la Messages API di Anthropic [sendTemperature] non conta. [zeroDataRetention]: OpenRouter instrada la domanda
     * solo ai fornitori che non la conservano ([OnlineTerms.zeroDataRetention]).
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        sendTemperature: Boolean,
        system: String? = null,
        api: OnlineApi = OnlineApi.CHAT_COMPLETIONS,
        zeroDataRetention: Boolean = false,
    ): Flow<String> = flow {
        val request = when (api) {
            OnlineApi.CHAT_COMPLETIONS -> chatCompletionsRequest(baseUrl, apiKey, model, prompt, sendTemperature, system, zeroDataRetention)
            OnlineApi.ANTHROPIC_MESSAGES -> anthropicMessagesRequest(baseUrl, apiKey, model, prompt, system)
        }
        var emitted = false
        var retried = false
        while (true) {
            try {
                answer(request, model, api).collect {
                    emitted = true
                    emit(it)
                }
                return@flow
            } catch (busy: OnlineServiceBusyException) {
                if (emitted || retried) throw busy
                retried = true
                delay(busy.retryAfterMillis ?: throw busy)
            }
        }
    }.flowOn(ioDispatcher)

    private fun chatCompletionsRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        sendTemperature: Boolean,
        system: String?,
        zeroDataRetention: Boolean,
    ): Request {
        val requestBody = OpenAiChatRequest(
            model = model,
            messages = listOfNotNull(system?.let { OpenAiChatMessage(role = "system", content = it) }) +
                OpenAiChatMessage(role = "user", content = prompt),
            temperature = TEMPERATURE.takeIf { sendTemperature },
            stream = true,
            provider = OpenRouterRouting(zdr = true).takeIf { zeroDataRetention },
        )
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(json.encodeToString(OpenAiChatRequest.serializer(), requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun anthropicMessagesRequest(baseUrl: String, apiKey: String, model: String, prompt: String, system: String?): Request {
        val requestBody = AnthropicMessagesRequest(
            model = model,
            maxTokens = ANTHROPIC_MAX_TOKENS,
            messages = listOf(OpenAiChatMessage(role = "user", content = prompt)),
            system = system,
            stream = true,
        )
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(json.encodeToString(AnthropicMessagesRequest.serializer(), requestBody).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    // Una richiesta: i pezzi di testo fino a finish_reason o [DONE]. Gemini non manda finish_reason, solo [DONE];
    // Mistral manda l'ultimo pezzo insieme a finish_reason (provati il 2026-10-31). Dopo la fine non si aspetta altro
    // (un eventuale pezzo con l'uso dei token non serve): il flusso si ferma e la connessione si chiude.
    private fun answer(request: Request, model: String, api: OnlineApi): Flow<String> = flow {
        var finished = false
        var emitted = false
        try {
            sseLines(request, model, api).transformWhile { line ->
                val chunk = when (api) {
                    OnlineApi.CHAT_COMPLETIONS -> sseChunk(line, model)
                    OnlineApi.ANTHROPIC_MESSAGES -> anthropicChunk(line, model)
                } ?: return@transformWhile true
                chunk.error?.let { throw if (emitted) OnlineAnswerInterruptedException("Errore a meta' della risposta", it) else it }
                chunk.text?.takeIf { it.isNotEmpty() }?.let {
                    emitted = true
                    emit(it)
                }
                finished = chunk.finished
                !finished
            }.collect { emit(it) }
        } catch (busy: OnlineServiceBusyException) {
            // Errore di trasporto dopo il primo pezzo: il testo gia' mostrato non si rifa', e' una risposta interrotta.
            throw if (emitted) OnlineAnswerInterruptedException("Risposta interrotta", busy) else busy
        }
        if (!finished) {
            // Connessione chiusa senza la fine: prima del testo e' come un timeout, dopo e' una risposta interrotta.
            val closed = OnlineServiceBusyException("Connessione chiusa prima della risposta", DEFAULT_RETRY_MILLIS)
            throw if (emitted) OnlineAnswerInterruptedException("Risposta interrotta", closed) else closed
        }
    }

    // Le righe del corpo SSE, lette sul thread di OkHttp. Senza redirect: un 307/308 rimanderebbe la domanda (e
    // un'intestazione con la chiave diversa da Authorization) a un altro indirizzo. readTimeout: l'attesa massima tra due
    // pezzi, larga per i modelli che ragionano prima del primo. La cancellazione chiude la connessione (awaitClose).
    private fun sseLines(request: Request, model: String, api: OnlineApi): Flow<String> = callbackFlow {
        val call = okHttpClient.newBuilder().readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
            .newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(transportError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            val body = response.peekBody(MAX_ERROR_BODY_BYTES).string()
                            val code = when (api) {
                                OnlineApi.CHAT_COMPLETIONS -> response.code
                                OnlineApi.ANTHROPIC_MESSAGES -> anthropicStatus(body, json) ?: response.code
                            }
                            close(httpError(code, body, response.header("Retry-After"), model))
                            return
                        }
                        val source = response.body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (trySendBlocking(line).isFailure) return
                        }
                        close()
                    } catch (e: IOException) {
                        close(transportError(e))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private class SseChunk(val text: String?, val finished: Boolean, val error: Exception?)

    // Una riga "data: ..." dello stream; null per le altre (vuote, "event:", commenti) e per i JSON senza testo ne' fine.
    private fun sseChunk(line: String, model: String): SseChunk? {
        val payload = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim()
        val obj = payload?.takeIf { it != "[DONE]" }?.let { json.parseToJsonElement(it) }
            ?.let { (if (it is JsonArray) it.firstOrNull() else it) as? JsonObject }
        val error = obj?.get("error")
        val choice = obj?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
        return when {
            payload == null -> null
            payload == "[DONE]" -> SseChunk(null, finished = true, error = null)
            // Errore dentro lo stream (un oggetto error, anche per Anthropic con type error): stessa classificazione dell'HTTP.
            error != null -> {
                val code = (error as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull ?: HTTP_INTERNAL_ERROR
                SseChunk(null, finished = false, error = httpError(code, payload, null, model))
            }
            choice == null -> null
            else -> SseChunk(
                text = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull,
                finished = choice["finish_reason"]?.jsonPrimitive?.contentOrNull != null,
                error = null,
            )
        }
    }

    // Un evento dello stream della Messages API: il testo da content_block_delta di tipo text_delta (i blocchi di
    // ragionamento, thinking_delta, non si mostrano), la fine da message_stop, l'errore dall'evento error con il suo tipo.
    // Il tipo dell'evento e' anche nel JSON di "data:", quindi le righe "event:" non servono.
    private fun anthropicChunk(line: String, model: String): SseChunk? {
        val payload = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: return null
        val obj = json.parseToJsonElement(payload) as? JsonObject
        return when (obj?.get("type")?.jsonPrimitive?.contentOrNull) {
            "content_block_delta" -> (obj.get("delta") as? JsonObject)
                ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text_delta" }
                ?.let { SseChunk(it["text"]?.jsonPrimitive?.contentOrNull, finished = false, error = null) }
            "message_stop" -> SseChunk(null, finished = true, error = null)
            "error" -> SseChunk(null, finished = false, error = httpError(anthropicStatus(payload, json) ?: HTTP_INTERNAL_ERROR, payload, null, model))
            else -> null
        }
    }

    private fun httpError(code: Int, responseBody: String, retryAfter: String?, model: String): Exception = when {
        // Un 403 di Gemini puo' essere un saldo scoperto, non la chiave: lo decide onlineErrorKind.
        code == HTTP_UNAUTHORIZED || (code == HTTP_FORBIDDEN && onlineErrorKind(code, responseBody) != OnlineErrorKind.CREDIT) ->
            OnlineApiKeyRejectedException("Chiave API rifiutata (HTTP $code)")
        isModelNotFound(code, responseBody, json) -> OnlineModelNotFoundException("Modello \"$model\" non disponibile (HTTP $code)")
        isDailyQuota(code, responseBody) -> OnlineDailyQuotaException("Quota giornaliera finita (HTTP $code)", model, isNotInFreePlan(responseBody))
        else -> when (onlineErrorKind(code, responseBody)) {
            OnlineErrorKind.CREDIT -> OnlineCreditExhaustedException("Credito esaurito (HTTP $code)")
            OnlineErrorKind.TEMPORARY -> OnlineServiceBusyException(
                "Servizio occupato (HTTP $code)",
                retryDelayMillis(retryAfter, responseBody, System.currentTimeMillis()),
            )
            OnlineErrorKind.PERMANENT -> IllegalStateException("Errore dal servizio online (HTTP $code)")
        }
    }

    private companion object {
        const val TEMPERATURE = 0.3
        const val READ_TIMEOUT_SECONDS = 120L
        const val ANTHROPIC_VERSION = "2026-10-21"

        // Tetto della risposta, ragionamento compreso: si paga solo quello che il modello scrive. 32.000 e' il massimo
        // dei modelli Claude piu' vecchi che si possono ancora scegliere (Opus 4 e 4.1), oltre il quale rispondono 400.
        const val ANTHROPIC_MAX_TOKENS = 32_000

        // Del corpo di un errore HTTP basta l'inizio: serve a classificarlo, e un servizio difettoso potrebbe mandarne molto.
        const val MAX_ERROR_BODY_BYTES = 8 * 1024L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// Un 400 e' "modello non disponibile" solo se il corpo dice model_not_found (error.code o error.type) o, per Mistral,
// type invalid_model al primo livello: citare la parola "model" non basta, un 400 qualunque (richiesta malformata) la
// contiene spesso.
internal fun isModelNotFound(statusCode: Int, responseBody: String, json: Json): Boolean = when (statusCode) {
    HTTP_NOT_FOUND -> true
    HTTP_BAD_REQUEST -> runCatching {
        val body = json.parseToJsonElement(responseBody).jsonObject
        val error = body["error"]?.jsonObject
        listOf("code", "type").any { error?.get(it)?.jsonPrimitive?.contentOrNull == "model_not_found" } ||
            body["type"]?.jsonPrimitive?.contentOrNull == "invalid_model"
    }.getOrDefault(false)
    else -> false
}

/**
 * Il codice HTTP che corrisponde al tipo di un errore della Messages API di Anthropic (`{"type":"error","error":{"type":
 * ...}}`, nel corpo della risposta o nell'evento error dello stream), o null se il tipo manca o e' sconosciuto. Il tipo
 * decide anche quando il codice HTTP direbbe altro: un billing_error arrivato con un 400 e' credito finito.
 */
internal fun anthropicStatus(responseBody: String, json: Json): Int? = runCatching {
    val type = json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
    ANTHROPIC_ERROR_STATUS[type]
}.getOrNull()

private val ANTHROPIC_ERROR_STATUS = mapOf(
    "invalid_request_error" to HTTP_BAD_REQUEST,
    "authentication_error" to HTTP_UNAUTHORIZED,
    "billing_error" to HTTP_PAYMENT_REQUIRED,
    "permission_error" to HTTP_FORBIDDEN,
    "not_found_error" to HTTP_NOT_FOUND,
    "request_too_large" to 413,
    "rate_limit_error" to HTTP_TOO_MANY_REQUESTS,
    "api_error" to HTTP_INTERNAL_ERROR,
    "timeout_error" to 504,
    "overloaded_error" to 529,
)

/** Come trattare un errore HTTP del servizio, oltre a chiave rifiutata e modello sconosciuto. */
internal enum class OnlineErrorKind { CREDIT, TEMPORARY, PERMANENT }

// Le parole del corpo che dicono credito, quota o tetto di spesa finiti (documentazione ufficiale, letta il 2026-10-31).
// OpenAI: 429 con error.code credit_balance_exhausted, *_spend_limit_exceeded, organization_usage_limit_exceeded, o con
// error.type insufficient_quota. Anthropic: 402 billing_error, 429 enforced_spend_limit_reached, 400 "credit balance is
// too low" o "You have reached your specified API usage limits". Gemini: 402 per il credito prepagato, 403 per un saldo
// scoperto ("outstanding balance"), 400 "enable billing". Mistral: 402. La quota giornaliera di Gemini e' a parte
// (isDailyQuota). Non la parola "billing" da sola: anche il 429 del limite al minuto di Gemini dice "check your plan and
// billing details" (provato con una chiave gratuita il 2026-10-31: quotaId ...PerMinute..., retryDelay nel corpo, nessun
// Retry-After). Servizi aggiunti (documentazione letta il 2026-11-04): Z.ai 429 "Insufficient balance" (codice 1113),
// Groq 400 blocked_api_access (tetto di spesa), Alibaba 403 Arrearage e AllocationQuota.FreeTierOnly, Regolo (proxy
// LiteLLM) 400 budget_exceeded, Cloudflare 429 "daily free allocation" (codice 3036, la quota gratuita del giorno).
// Fireworks e OpenRouter usano il 402.
private val CREDIT_MARKERS = listOf(
    "insufficient_quota", "credit_balance", "credit balance", "spend_limit", "usage_limit", "specified api usage limits",
    "billing_error", "enable billing", "outstanding balance",
    "insufficient balance", "blocked_api_access", "arrearage", "freetieronly", "budget_exceeded", "daily free allocation",
)
private val CREDIT_MARKER_CODES = setOf(HTTP_BAD_REQUEST, HTTP_FORBIDDEN, HTTP_TOO_MANY_REQUESTS)
private val TEMPORARY_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504, 529)

/**
 * Quota giornaliera di un modello finita: il 429 di Gemini con "PerDay" nel quotaId (provato con una chiave gratuita il
 * 2026-11-01: GenerateRequestsPerDayPerProjectPerModel-FreeTier, 20 richieste al giorno per gemini-3.6-flash).
 */
internal fun isDailyQuota(statusCode: Int, responseBody: String): Boolean =
    statusCode == HTTP_TOO_MANY_REQUESTS && "perday" in responseBody.lowercase()

/**
 * Il piano gratuito non include il modello: nel messaggio del 429 di Gemini il limite "free_tier" e' 0 (provato il
 * 2026-11-01 con gemini-4-argon: "generate_content_free_tier_requests, limit: 0"; con la quota finita e' "limit: 20").
 */
internal fun isNotInFreePlan(responseBody: String): Boolean = FREE_TIER_LIMIT_ZERO.containsMatchIn(responseBody)

private val FREE_TIER_LIMIT_ZERO = Regex("""free_tier\w*,\s*limit:\s*0\b""", RegexOption.IGNORE_CASE)

internal fun onlineErrorKind(statusCode: Int, responseBody: String): OnlineErrorKind {
    val body = responseBody.lowercase()
    return when {
        statusCode == HTTP_PAYMENT_REQUIRED -> OnlineErrorKind.CREDIT
        statusCode in CREDIT_MARKER_CODES && CREDIT_MARKERS.any { it in body } -> OnlineErrorKind.CREDIT
        statusCode in TEMPORARY_CODES -> OnlineErrorKind.TEMPORARY
        else -> OnlineErrorKind.PERMANENT
    }
}

/**
 * Quanto aspettare prima del nuovo tentativo: Retry-After in secondi o come data HTTP, altrimenti il retryDelay di
 * Gemini nel corpo ("13s"), altrimenti 2 s. Null, cioe' nessun nuovo tentativo, se il servizio chiede piu' di 10 s:
 * l'utente aspetterebbe troppo, e il messaggio gli dice di riprovare tra poco.
 */
internal fun retryDelayMillis(retryAfter: String?, responseBody: String, nowMillis: Long): Long? {
    val header = retryAfter?.trim()?.takeIf { it.isNotEmpty() }
    val millis = header?.let { value ->
        value.toLongOrNull()?.let { it * MILLIS_PER_SECOND }
            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis }
                .getOrNull()
    } ?: RETRY_DELAY_IN_BODY.find(responseBody)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * MILLIS_PER_SECOND).toLong() }
        ?: DEFAULT_RETRY_MILLIS
    return millis.coerceAtLeast(0).takeIf { it <= MAX_RETRY_MILLIS }
}

/**
 * Timeout e connessione chiusa a meta' sono temporanei (un nuovo tentativo dopo 2 s); senza rete (nome del server
 * non risolto, connessione rifiutata) resta l'errore generico, perche' riprovare subito non servirebbe.
 */
internal fun transportError(error: IOException): Exception = when (error) {
    is UnknownHostException, is ConnectException -> error
    else -> OnlineServiceBusyException("Servizio non raggiungibile: ${error.javaClass.simpleName}", DEFAULT_RETRY_MILLIS, error)
}

private val RETRY_DELAY_IN_BODY = Regex(""""retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"""")
private const val MILLIS_PER_SECOND = 1_000L
private const val DEFAULT_RETRY_MILLIS = 2_000L
private const val MAX_RETRY_MILLIS = 10_000L
private const val HTTP_PAYMENT_REQUIRED = 402
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_INTERNAL_ERROR = 500
