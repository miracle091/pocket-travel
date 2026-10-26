package com.pockettravel.core.data.advice

import android.content.Context
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.WEATHER_MIN_REFRESH_MILLIS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Ultima modifica dei consigli di viaggio FCDO di un paese e la pagina GOV.UK che li contiene. */
data class TravelAdviceUpdate(val updatedAt: Instant, val webUrl: String) {
    /** Vero se l'FCDO ha modificato i consigli in un giorno (UTC) dopo [day]. */
    fun isAfter(day: LocalDate): Boolean = updatedAt.atOffset(ZoneOffset.UTC).toLocalDate().isAfter(day)
}

/** Aggiornamenti dei consigli di viaggio dell'FCDO. Implementazione: [OnlineTravelAdviceUpdates]. */
interface TravelAdviceUpdates {
    /** Null se la pagina non si e' mai letta (mai scaricata e niente rete) o lo [slug] non e' valido. */
    suspend fun latest(slug: String): TravelAdviceUpdate?
}

/**
 * Data di modifica letta dalla GOV.UK Content API (Open Government Licence v3), senza la posizione dell'utente: una
 * richiesta per paese. L'ultima risposta resta in un file per l'uso senza rete; un paese non si richiede piu' spesso di
 * WEATHER_MIN_REFRESH_MILLIS, lo stesso limite del meteo, anche dopo un tentativo fallito.
 */
class OnlineTravelAdviceUpdates(
    private val cacheDir: File,
    okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : TravelAdviceUpdates {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        clock: Clock,
    ) : this(File(context.filesDir, "travel-advice"), okHttpClient, ioDispatcher, clock)

    private val client = okHttpClient.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    // Ora dell'ultimo tentativo per paese, riuscito o no.
    private val attempts = ConcurrentHashMap<String, Long>()

    override suspend fun latest(slug: String): TravelAdviceUpdate? = withContext(ioDispatcher) {
        if (!SLUG.matches(slug)) return@withContext null
        val file = File(cacheDir, "$slug.json")
        val lastAttempt = maxOf(attempts[slug] ?: 0L, if (file.isFile) file.lastModified() else 0L)
        if (clock.millis() - lastAttempt >= WEATHER_MIN_REFRESH_MILLIS) download(slug, file)
        val updatedAt = if (file.isFile) parse(file.readText()) else null
        updatedAt?.let { TravelAdviceUpdate(it, WEB_BASE + slug) }
    }

    // Si salva solo una risposta che si legge: una pagina di errore non sostituisce l'ultima buona.
    private fun download(slug: String, file: File) {
        attempts[slug] = clock.millis()
        try {
            val body = get(API_BASE + slug)
            if (parse(body) != null) save(file, body)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            // Senza rete resta la risposta salvata.
        }
    }

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Consigli di viaggio: HTTP ${response.code} da $url")
        response.body.string()
    }

    // Prima in un file a parte, poi al suo posto: una scrittura interrotta non lascia una risposta troncata.
    private fun save(file: File, body: String) {
        cacheDir.mkdirs()
        val partial = File.createTempFile("advice", ".part", cacheDir)
        try {
            partial.writeText(body)
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // L'ora del download e' quella dell'orologio dell'app, la stessa del confronto con il limite.
            file.setLastModified(clock.millis())
        } finally {
            partial.delete()
        }
    }

    private fun parse(body: String): Instant? = try {
        Json.parseToJsonElement(body).jsonObject["public_updated_at"]?.jsonPrimitive?.contentOrNull
            ?.let { OffsetDateTime.parse(it).toInstant() }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: DateTimeException) {
        null
    }

    private companion object {
        const val API_BASE = "https://www.gov.uk/api/content/foreign-travel-advice/"
        const val WEB_BASE = "https://www.gov.uk/foreign-travel-advice/"
        const val CALL_TIMEOUT_SECONDS = 10L
        val SLUG = Regex("[a-z0-9-]+")
    }
}
