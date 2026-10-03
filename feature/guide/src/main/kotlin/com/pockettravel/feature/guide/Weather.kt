package com.pockettravel.feature.guide

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Tempo attuale e previsioni giornaliere di un luogo (Open-Meteo, api.open-meteo.com/v1/forecast). */
data class Weather(
    val temperature: Double,
    val condition: WeatherCondition,
    val days: List<WeatherDay>,
    // Oggi nel fuso del luogo, per l'etichetta "Oggi" (puo' non essere la data del telefono).
    val today: LocalDate,
)

data class WeatherDay(
    val date: LocalDate,
    val condition: WeatherCondition,
    val max: Double,
    val min: Double,
    // Probabilita' massima di pioggia del giorno in percentuale; null dove il modello non la da'.
    val rainChance: Int?,
)

enum class WeatherCondition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDERSTORM;

    companion object {
        // Codici meteo WMO 4677 come li usa Open-Meteo: 0 sereno, 1-2 poco nuvoloso, 3 coperto, 45/48 nebbia,
        // 51-67 pioviggine e pioggia (anche gelata), 71-77 neve, 80-82 rovesci, 85-86 rovesci di neve,
        // 95-99 temporale.
        private val PARTLY_CLOUDY_CODES = 1..2
        private val FOG_CODES = setOf(45, 48)
        private val RAIN_CODES = (51..67) + (80..82)
        private val SNOW_CODES = (71..77) + (85..86)
        private val THUNDERSTORM_CODES = 95..99

        internal fun ofWmoCode(code: Int): WeatherCondition = when (code) {
            0 -> CLEAR
            in PARTLY_CLOUDY_CODES -> PARTLY_CLOUDY
            in FOG_CODES -> FOG
            in RAIN_CODES -> RAIN
            in SNOW_CODES -> SNOW
            in THUNDERSTORM_CODES -> THUNDERSTORM
            else -> CLOUDY
        }
    }
}

/**
 * Le previsioni di una risposta di Open-Meteo dal giorno di [now] nel fuso del luogo in poi: una risposta
 * salvata qualche giorno fa mostra solo i giorni non ancora passati. Salta i giorni senza dati; null se
 * non ne resta nessuno.
 */
internal fun parseForecast(json: Json, body: String, now: Instant): Weather? {
    val response = json.decodeFromString(ForecastResponse.serializer(), body)
    val today = now.atOffset(ZoneOffset.ofTotalSeconds(response.utcOffsetSeconds)).toLocalDate()
    val daily = response.daily
    val days = daily.time.indices.mapNotNull { i ->
        val date = LocalDate.parse(daily.time[i]).takeUnless { it.isBefore(today) } ?: return@mapNotNull null
        val code = daily.weatherCode.getOrNull(i) ?: return@mapNotNull null
        val max = daily.temperatureMax.getOrNull(i) ?: return@mapNotNull null
        val min = daily.temperatureMin.getOrNull(i) ?: return@mapNotNull null
        WeatherDay(date, WeatherCondition.ofWmoCode(code), max, min, daily.precipitationProbabilityMax?.getOrNull(i))
    }
    if (days.isEmpty()) return null
    return Weather(
        temperature = response.current.temperature,
        condition = WeatherCondition.ofWmoCode(response.current.weatherCode),
        days = days,
        today = today,
    )
}

@Serializable
private data class ForecastResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val current: Current,
    val daily: Daily,
)

@Serializable
private data class Current(
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("weather_code") val weatherCode: Int,
)

@Serializable
private data class Daily(
    val time: List<String>,
    @SerialName("weather_code") val weatherCode: List<Int?>,
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?>,
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?>,
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?>? = null,
)

/** Risposta del geocoding di Open-Meteo (geocoding-api.open-meteo.com/v1/search, dati GeoNames). */
@Serializable
internal data class GeocodingResponse(val results: List<GeocodingResult> = emptyList())

@Serializable
internal data class GeocodingResult(val latitude: Double, val longitude: Double)
