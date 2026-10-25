package com.pockettravel.core.data

/** Meteo con l'ora in cui e' stato scaricato: senza rete si mostra l'ultimo salvato e da quando. */
data class WeatherResult(val weather: Weather, val updatedAtMillis: Long)

/** Intervallo minimo fra due richieste del meteo dello stesso luogo, per tutti i chiamanti (riquadro della guida, assistente). */
const val WEATHER_MIN_REFRESH_MILLIS = 10 * 60 * 1000L

/** Previsioni del tempo per luogo, dalla rete o dall'ultima risposta salvata. Implementazione: [OpenMeteoWeatherRepository]. */
interface WeatherRepository {
    /**
     * Il meteo salvato come [cacheKey], aggiornato dalla rete quando [coordinates] le trova. Null senza rete e
     * senza una risposta salvata ancora valida. Con una risposta di meno di [WEATHER_MIN_REFRESH_MILLIS] niente rete:
     * ne' l'apertura della guida, ne' il pulsante "Aggiorna", ne' l'assistente interrogano il servizio piu' spesso di cosi'.
     */
    suspend fun weather(cacheKey: String, coordinates: suspend () -> Pair<Double, Double>?): WeatherResult?

    /** Coordinate della citta' [city] nel paese [countryCode] (ISO alpha-2); null se il geocoding non la trova. */
    suspend fun coordinatesOf(city: String, countryCode: String): Pair<Double, Double>?
}
