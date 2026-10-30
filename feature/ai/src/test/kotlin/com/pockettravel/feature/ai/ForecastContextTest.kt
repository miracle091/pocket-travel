package com.pockettravel.feature.ai

import com.pockettravel.core.data.CityClimate
import com.pockettravel.core.data.WEATHER_MIN_REFRESH_MILLIS
import com.pockettravel.core.data.Weather
import com.pockettravel.core.data.WeatherCondition
import com.pockettravel.core.data.WeatherDay
import com.pockettravel.core.data.WeatherRepository
import com.pockettravel.core.data.WeatherResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Previsioni nel contesto dell'assistente: quali domande le vogliono e il testo del blocco, con un WeatherRepository finto.
class ForecastContextTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-25T16:20:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.parse("2026-10-25")

    private fun result(): WeatherResult = WeatherResult(
        Weather(
            temperature = 20.0,
            condition = WeatherCondition.CLEAR,
            days = listOf(
                WeatherDay(today, WeatherCondition.CLEAR, 22.4, 13.0, 0),
                WeatherDay(today.plusDays(1), WeatherCondition.RAIN, 19.0, 12.0, 70),
                WeatherDay(today.plusDays(2), WeatherCondition.CLOUDY, 18.0, 11.0, null),
            ),
            today = today,
        ),
        updatedAtMillis = clock.millis(),
    )

    private class FakeWeatherRepository(var weather: WeatherResult?) : WeatherRepository {
        val keys = mutableListOf<String>()
        val geocoded = mutableListOf<Pair<String, String>>()

        override suspend fun weather(cacheKey: String, coordinates: suspend () -> Pair<Double, Double>?): WeatherResult? {
            keys += cacheKey
            coordinates()
            return weather
        }

        override suspend fun coordinatesOf(city: String, countryCode: String): Pair<Double, Double>? {
            geocoded += city to countryCode
            return 41.9 to 12.5
        }
    }

    private fun query(usePosition: Boolean, cities: List<String> = emptyList()) = ForecastQuery(
        regionId = "italia", question = "Che tempo fa domani?", previousQuestion = null, modelGroups = emptySet(),
        cities = cities, cityNames = listOf("Roma", "Firenze", "Bari"), namesCountry = false, language = "it",
        usePosition = usePosition,
    )

    @Test
    fun onlineForecastSkipsPositionAndLastOpenedGuide() = runTest {
        val nearest = suspend { "Bari" }
        val main = suspend { "Roma" }
        // Sul dispositivo: la piu' vicina, poi l'ultima guida aperta.
        assertEquals("Bari", forecastCity(query(usePosition = true), nearest, { "Firenze" }, main))
        assertEquals("Firenze", forecastCity(query(usePosition = true), { null }, { "Firenze" }, main))
        // Online: ne' la posizione ne' l'ultima guida aperta arrivano al servizio, si passa alla capitale.
        assertEquals("Roma", forecastCity(query(usePosition = false), nearest, { "Firenze" }, main))
        // La citta' nominata nella domanda vale con tutti e due i motori.
        assertEquals("Bari", forecastCity(query(usePosition = false, cities = listOf("Bari")), nearest, { "Firenze" }, main))
    }

    @Test
    fun onlyForecastQuestionsAreForecastQuestions() {
        listOf("Che tempo fa domani a Roma?", "Will it rain tomorrow in Porto?", "Weather forecast for Zagreb")
            .forEach { assertTrue(it, isForecastQuestion(it)) }
        listOf("Com'è il clima a Roma?", "Cosa vedere oggi a Roma?", "Storia di Roma", "When is the best time to go to Porto?")
            .forEach { assertFalse(it, isForecastQuestion(it)) }
        // La categoria scelta dal modello vale come quella delle liste.
        assertTrue(isForecastQuestion("Com'è fuori?", setOf("METEO")))
    }

    @Test
    fun forecastQuestionGetsTheBlockWithTheSharedCacheKey() = runTest {
        val repository = FakeWeatherRepository(result())
        val block = requireNotNull(ForecastProvider(repository, clock).section("Roma", "IT", "it"))
        val section = block.section
        // L'ora del download decide quando "Aggiorna le previsioni" compare sotto la risposta.
        val weather = requireNotNull(block.weather)
        assertEquals(clock.millis(), weather.result.updatedAtMillis)
        assertEquals("Roma", weather.place)
        assertEquals(listOf("city|IT|Roma"), repository.keys)
        assertEquals(listOf("Roma" to "IT"), repository.geocoded)
        assertEquals(
            "Previsioni per Roma (Open-Meteo, aggiornate alle 16:20):\n" +
                "- oggi: sereno, 22/13 °C, pioggia 0%\n" +
                "- domani: pioggia, 19/12 °C, pioggia 70%\n" +
                "- martedì 27/10: coperto, 18/11 °C",
            section.body,
        )
        assertTrue("Open-Meteo" in section.citation)
    }

    @Test
    fun englishBlockUsesEnglishLabels() = runTest {
        val section = requireNotNull(ForecastProvider(FakeWeatherRepository(result()), clock).section("Rome", "IT", "en")).section
        assertTrue(section.body, section.body.startsWith("Forecast for Rome (Open-Meteo, updated at 16:20):\n- today: clear, 22/13 °C"))
        assertTrue(section.body, "\n- tomorrow: rain, 19/12 °C, rain 70%" in section.body)
        assertTrue(section.body, "\n- Tuesday 27/10: cloudy" in section.body)
    }

    @Test
    fun refreshOnlyAfterThePerPlaceLimit() {
        val updated = clock.millis()
        assertEquals(WEATHER_MIN_REFRESH_MILLIS, forecastRefreshDelayMillis(updated, updated))
        assertEquals(60_000L, forecastRefreshDelayMillis(updated, updated + WEATHER_MIN_REFRESH_MILLIS - 60_000L))
        // Previsioni salvate piu' vecchie del limite (aperte senza rete, poi tornata): subito.
        assertEquals(0L, forecastRefreshDelayMillis(updated, updated + 3 * WEATHER_MIN_REFRESH_MILLIS))
    }

    @Test
    fun noForecastWithoutNetworkAndSavedWeather() = runTest {
        assertNull(ForecastProvider(FakeWeatherRepository(null), clock).section("Roma", "IT", "it"))
    }

    @Test
    fun withoutForecastTheMonthAveragesOfWikipedia() {
        val url = "https://it.wikipedia.org/wiki/Roma"
        val climate = listOf(CityClimate(10, 22.4, 13.0, 113.0, 8.0, url), CityClimate(11, 17.0, null, null, null, url))
        val october = offlineWeatherSection("Roma", climate, 10, "it")
        assertEquals(
            "Previsioni per Roma non disponibili: niente rete e nessuna previsione salvata.\n" +
                "Medie di ottobre a Roma (Wikipedia): massima 22 °C, minima 13 °C, pioggia 113 mm, giorni di pioggia 8.",
            october.body,
        )
        assertEquals("Fonte: tabella climatica di Wikipedia (Roma)", october.citation)
        assertEquals(
            "No forecast for Rome: no connection and no saved forecast.\nAverage for November in Rome (Wikipedia): high 17 °C.",
            offlineWeatherSection("Rome", climate, 11, "en").body,
        )
        // Senza il mese nella tabella resta solo la frase: la sezione Clima trovata dalla ricerca fa il resto.
        assertEquals("Previsioni per Roma non disponibili: niente rete e nessuna previsione salvata.", offlineWeatherSection("Roma", climate, 3, "it").body)
    }
}
