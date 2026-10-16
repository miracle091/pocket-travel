package com.pockettravel.feature.guide

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

// WeatherRepository: coordinate e risposta gia' salvate, e il download con un client che risponde al posto di Open-Meteo.
class WeatherRepositoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val cacheDir = createTempDirectory("weather-repository-test").toFile()
    private val files = InMemoryPreferencesFactory()
    private val json = Json { ignoreUnknownKeys = true }
    private val clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)

    private fun newRepository() = WeatherRepository(cacheDir, files, OkHttpClient(), json, dispatcher, clock)

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    // Stesso nome di file della cache: SHA-256 della chiave in esadecimale.
    private fun cacheFile(cacheKey: String) = File(
        cacheDir,
        MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray()).joinToString("") { "%02x".format(it) } + ".json",
    )

    private fun forecastBody(): String {
        val today = LocalDate.now(clock)
        return """
            {"utc_offset_seconds":0,
             "current":{"temperature_2m":18.5,"weather_code":1},
             "daily":{"time":["$today","${today.plusDays(1)}"],
                      "weather_code":[1,3],
                      "temperature_2m_max":[24.0,22.0],
                      "temperature_2m_min":[14.0,13.0],
                      "precipitation_probability_max":[10,20]}}
        """.trimIndent()
    }

    @Test
    fun `le coordinate salvate si leggono senza rete, anche con il paese in minuscolo`() = runTest(dispatcher) {
        files.open("weather_places").edit().putString("IT|Roma", "41.9,12.5").apply()
        val repository = newRepository()

        assertEquals(41.9 to 12.5, repository.coordinatesOf("Roma", "IT"))
        assertEquals(41.9 to 12.5, repository.coordinatesOf("Roma", "it"))
    }

    @Test
    fun `senza risposta salvata ne coordinate il meteo manca`() = runTest(dispatcher) {
        var asked = 0
        val result = newRepository().weather("city|IT|Roma") { asked++; null }

        assertNull(result)
        assertEquals(1, asked)
    }

    @Test
    fun `una risposta salvata da poco si mostra senza chiedere le coordinate`() = runTest(dispatcher) {
        cacheFile("city|IT|Roma").apply { writeText(forecastBody()) }.setLastModified(clock.millis() - WEATHER_MIN_REFRESH_MILLIS + 1_000)
        var asked = 0

        val result = newRepository().weather("city|IT|Roma") { asked++; null }

        assertNotNull(result)
        assertEquals(18.5, requireNotNull(result).weather.temperature, 0.0)
        assertEquals(0, asked)
    }

    @Test
    fun `una risposta salvata da piu' dell'intervallo chiede le coordinate e senza rete resta quella salvata`() = runTest(dispatcher) {
        cacheFile("city|IT|Roma").apply { writeText(forecastBody()) }.setLastModified(clock.millis() - WEATHER_MIN_REFRESH_MILLIS - 1_000)
        var asked = 0

        val result = newRepository().weather("city|IT|Roma") { asked++; null }

        assertEquals(1, asked)
        assertEquals(18.5, requireNotNull(result).weather.temperature, 0.0)
    }

    // Client che risponde al posto di Open-Meteo con [code] e [body], e annota le richieste.
    private fun answering(code: Int, body: String, requests: MutableList<String>) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        .build()

    private fun staleCache(cacheKey: String) = cacheFile(cacheKey).apply {
        writeText(forecastBody())
        setLastModified(clock.millis() - WEATHER_MIN_REFRESH_MILLIS - 1_000)
    }

    @Test
    fun `una risposta salvata da piu' dell'intervallo si riscarica e la nuova prende il suo posto`() = runTest(dispatcher) {
        val file = staleCache("city|IT|Roma")
        val requests = mutableListOf<String>()
        val newer = forecastBody().replace("18.5", "21.0")
        val repository = WeatherRepository(cacheDir, files, answering(200, newer, requests), json, dispatcher, clock)

        val result = repository.weather("city|IT|Roma") { 41.9 to 12.5 }

        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("latitude=41.9000") && requests.single().contains("longitude=12.5000"))
        assertEquals(21.0, requireNotNull(result).weather.temperature, 0.0)
        assertEquals(newer, file.readText())
        // Nessun file temporaneo rimasto accanto alla risposta.
        assertEquals(listOf(file.name), cacheDir.list()?.toList())
    }

    @Test
    fun `un errore HTTP lascia la risposta salvata`() = runTest(dispatcher) {
        val file = staleCache("city|IT|Roma")
        val saved = file.readText()
        val requests = mutableListOf<String>()
        val repository = WeatherRepository(cacheDir, files, answering(503, "{}", requests), json, dispatcher, clock)

        val result = repository.weather("city|IT|Roma") { 41.9 to 12.5 }

        assertEquals(1, requests.size)
        assertEquals(18.5, requireNotNull(result).weather.temperature, 0.0)
        assertEquals(saved, file.readText())
    }

    @Test
    fun `una risposta che non si legge non sostituisce quella salvata`() = runTest(dispatcher) {
        val file = staleCache("city|IT|Roma")
        val saved = file.readText()
        val repository = WeatherRepository(cacheDir, files, answering(200, "{\"current\":", mutableListOf()), json, dispatcher, clock)

        val result = repository.weather("city|IT|Roma") { 41.9 to 12.5 }

        assertEquals(18.5, requireNotNull(result).weather.temperature, 0.0)
        assertEquals(saved, file.readText())
    }

    @Test
    fun `con una risposta recente la rete non si interroga`() = runTest(dispatcher) {
        cacheFile("city|IT|Roma").apply { writeText(forecastBody()) }.setLastModified(clock.millis() - 1_000)
        val requests = mutableListOf<String>()
        val repository = WeatherRepository(cacheDir, files, answering(200, forecastBody(), requests), json, dispatcher, clock)

        repository.weather("city|IT|Roma") { 41.9 to 12.5 }

        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `una risposta salvata vale per la sua chiave e un'altra chiave non la vede`() = runTest(dispatcher) {
        cacheFile("city|IT|Roma").writeText(forecastBody())

        assertNull(newRepository().weather("city|IT|Milano") { null })
    }
}
