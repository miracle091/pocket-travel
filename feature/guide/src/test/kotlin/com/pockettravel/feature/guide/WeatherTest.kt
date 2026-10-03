package com.pockettravel.feature.guide

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WeatherTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Risposta reale di api.open-meteo.com accorciata a tre giorni (Roma, fuso +2).
    private val body = """
        {"latitude":41.875,"longitude":12.5,"utc_offset_seconds":7200,"timezone":"Europe/Rome",
         "current":{"time":"2026-10-03T20:15","interval":900,"temperature_2m":22.2,"weather_code":2,"is_day":0},
         "daily":{"time":["2026-10-03","2026-10-04","2026-10-05"],
                  "weather_code":[3,95,null],
                  "temperature_2m_max":[26.9,22.5,19.2],
                  "temperature_2m_min":[16.9,17.3,15.1],
                  "precipitation_probability_max":[0,89,null]}}
    """.trimIndent()

    @Test
    fun `parses current weather and skips days without data`() {
        val weather = requireNotNull(parseForecast(json, body, Instant.parse("2026-10-03T18:00:00Z")))
        assertEquals(22.2, weather.temperature, 0.0)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, weather.condition)
        assertEquals(LocalDate.parse("2026-10-03"), weather.today)
        assertEquals(
            listOf(
                WeatherDay(LocalDate.parse("2026-10-03"), WeatherCondition.CLOUDY, 26.9, 16.9, 0),
                WeatherDay(LocalDate.parse("2026-10-04"), WeatherCondition.THUNDERSTORM, 22.5, 17.3, 89),
            ),
            weather.days,
        )
    }

    @Test
    fun `today follows the place's time zone, not UTC`() {
        // 22:30 UTC del 3 sono le 00:30 del 4 a Roma: il 3 e' gia' passato.
        val weather = requireNotNull(parseForecast(json, body, Instant.parse("2026-10-03T22:30:00Z")))
        assertEquals(LocalDate.parse("2026-10-04"), weather.today)
        assertEquals(listOf(LocalDate.parse("2026-10-04")), weather.days.map { it.date })
    }

    @Test
    fun `an old saved forecast with no days left is dropped`() {
        assertNull(parseForecast(json, body, Instant.parse("2026-10-10T12:00:00Z")))
    }

    @Test
    fun `maps WMO codes to conditions`() {
        val expected = mapOf(
            0 to WeatherCondition.CLEAR,
            1 to WeatherCondition.PARTLY_CLOUDY,
            3 to WeatherCondition.CLOUDY,
            48 to WeatherCondition.FOG,
            53 to WeatherCondition.RAIN,
            66 to WeatherCondition.RAIN,
            81 to WeatherCondition.RAIN,
            75 to WeatherCondition.SNOW,
            86 to WeatherCondition.SNOW,
            99 to WeatherCondition.THUNDERSTORM,
        )
        expected.forEach { (code, condition) -> assertEquals("code $code", condition, WeatherCondition.ofWmoCode(code)) }
    }

    @Test
    fun `geocoding tries shorter names after the full one`() {
        assertEquals(
            listOf("Città di San Marino", "di San Marino", "San Marino", "Marino"),
            geocodingCandidates("Città di San Marino"),
        )
        assertEquals(listOf("Frankfurt (Oder)", "Frankfurt"), geocodingCandidates("Frankfurt (Oder)"))
        assertEquals(listOf("Roma"), geocodingCandidates("Roma"))
    }
}
