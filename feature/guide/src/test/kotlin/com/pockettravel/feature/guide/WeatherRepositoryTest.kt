package com.pockettravel.feature.guide

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

// Percorsi senza rete di WeatherRepository: coordinate e risposta gia' salvate.
class WeatherRepositoryTest {

    private val dispatcher = StandardTestDispatcher()
    private val cacheDir = createTempDirectory("weather-repository-test").toFile()
    private val files = InMemoryPreferencesFactory()
    private val json = Json { ignoreUnknownKeys = true }

    private fun newRepository() = WeatherRepository(cacheDir, files, OkHttpClient(), json, dispatcher)

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
        val today = LocalDate.now(ZoneOffset.UTC)
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
        cacheFile("city|IT|Roma").writeText(forecastBody())
        var asked = 0

        val result = newRepository().weather("city|IT|Roma") { asked++; null }

        assertNotNull(result)
        assertEquals(18.5, requireNotNull(result).weather.temperature, 0.0)
        assertEquals(0, asked)
    }

    @Test
    fun `una risposta salvata vale per la sua chiave e un'altra chiave non la vede`() = runTest(dispatcher) {
        cacheFile("city|IT|Roma").writeText(forecastBody())

        assertNull(newRepository().weather("city|IT|Milano") { null })
    }
}
