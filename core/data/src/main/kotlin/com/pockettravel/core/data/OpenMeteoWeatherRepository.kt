package com.pockettravel.core.data

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Meteo da Open-Meteo (open-meteo.com, nessuna chiave, dati CC BY 4.0). L'ultima risposta di ogni luogo resta
 * in un file: senza rete, o se il servizio non risponde, il riquadro mostra quella finche' ha giorni non passati.
 * Le coordinate delle citta' (geocoding per nome e paese) restano nelle preferenze: non cambiano.
 */
class OpenMeteoWeatherRepository(
    private val cacheDir: File,
    preferencesFactory: PreferencesFactory,
    okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) : WeatherRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        preferencesFactory: PreferencesFactory,
        okHttpClient: OkHttpClient,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        clock: Clock,
    ) : this(File(context.filesDir, "weather"), preferencesFactory, okHttpClient, json, ioDispatcher, clock)

    private val places = preferencesFactory.open("weather_places")

    // Tetto per chiamata: con una rete lenta il riquadro non resta vuoto per decine di secondi prima di mostrare
    // il meteo salvato (i timeout di connessione e lettura non limitano la chiamata intera).
    private val client = okHttpClient.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override suspend fun weather(cacheKey: String, coordinates: suspend () -> Pair<Double, Double>?): WeatherResult? =
        withContext(ioDispatcher) {
            val file = File(cacheDir, cacheKey.toFileName())
            val fresh = clock.millis() - file.lastModified() < WEATHER_MIN_REFRESH_MILLIS && cached(file) != null
            if (!fresh) try {
                coordinates()?.let { (lat, lon) ->
                    val body = get(forecastUrl(lat, lon))
                    // Salvata solo se si legge: una risposta rotta non sostituisce l'ultima buona.
                    parseForecast(json, body, clock.instant())
                    cacheDir.mkdirs()
                    // Prima in un file a parte, poi al suo posto: una scrittura interrotta non lascia una risposta troncata.
                    // Files.move sostituisce la risposta vecchia anche dove renameTo non sovrascrive (JVM su Windows).
                    val partial = File.createTempFile("weather", ".part", cacheDir)
                    try {
                        partial.writeText(body)
                        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally {
                        partial.delete()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Rete assente, servizio giu' o risposta inattesa: resta l'ultima salvata.
            }
            cached(file)
        }

    override suspend fun coordinatesOf(city: String, countryCode: String): Pair<Double, Double>? = withContext(ioDispatcher) {
        val key = "${countryCode.uppercase(Locale.ROOT)}|$city"
        places.getString(key, null)?.split(',')?.let { (lat, lon) -> return@withContext lat.toDouble() to lon.toDouble() }
        val (name, result) = geocodingCandidates(city).firstNotNullOfOrNull { name ->
            val url = GEOCODING_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", name)
                .addQueryParameter("count", "1")
                .addQueryParameter("countryCode", countryCode.uppercase(Locale.ROOT))
                .build().toString()
            json.decodeFromString(GeocodingResponse.serializer(), get(url)).results.firstOrNull()?.let { name to it }
        } ?: return@withContext null
        // Si salvano solo le coordinate trovate col nome intero: un ripiego senza le prime parole ("Rotondo" per
        // "San Giovanni Rotondo") puo' essere un altro luogo, e si riprova alla volta successiva.
        if (name == city.trim() || name == withoutDisambiguator(city)) places.edit { putString(key, "${result.latitude},${result.longitude}") }
        result.latitude to result.longitude
    }

    private fun cached(file: File): WeatherResult? = runCatching {
        parseForecast(json, file.readText(), clock.instant())?.let { WeatherResult(it, file.lastModified()) }
    }.getOrNull()

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Open-Meteo: HTTP ${response.code}")
            response.body.string()
        }

    private fun forecastUrl(lat: Double, lon: Double): String = FORECAST_URL.toHttpUrl().newBuilder()
        .addQueryParameter("latitude", "%.4f".format(Locale.ROOT, lat))
        .addQueryParameter("longitude", "%.4f".format(Locale.ROOT, lon))
        .addQueryParameter("current", "temperature_2m,weather_code")
        .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
        .addQueryParameter("timezone", "auto")
        .addQueryParameter("forecast_days", FORECAST_DAYS.toString())
        .build().toString()

    private companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_DAYS = 7
        const val CALL_TIMEOUT_SECONDS = 10L
    }
}

/**
 * I nomi da cercare per una citta' della guida, dal piu' preciso: il nome intero, poi senza le parentesi
 * togliendo una parola alla volta dall'inizio. Le guide usano anche nomi ufficiali che GeoNames non conosce
 * ("Citta' di San Marino" e' "San Marino"); la ricerca resta limitata al paese.
 */
internal fun geocodingCandidates(city: String): List<String> {
    val words = withoutDisambiguator(city).split(Regex("\\s+")).filter { it.isNotEmpty() }
    return (listOf(city.trim()) + words.indices.map { words.drop(it).joinToString(" ") }).distinct()
}

// "Frankfurt (Oder)" -> "Frankfurt": senza il disambiguatore di Wikivoyage tra parentesi.
private fun withoutDisambiguator(city: String): String = city.replace(Regex("\\s*\\(.*?\\)"), "").trim()

// Nome di file sicuro per qualunque nome di citta' (spazi, apostrofi, alfabeti non latini).
private fun String.toFileName(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json"
