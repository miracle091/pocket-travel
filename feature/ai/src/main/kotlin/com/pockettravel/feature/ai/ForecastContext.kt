package com.pockettravel.feature.ai

import android.location.Location
import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.LastOpenedCityGuide
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.WeatherCondition
import com.pockettravel.core.data.WeatherRepository
import com.pockettravel.core.data.WeatherResult
import com.pockettravel.core.ui.PlaceWeather
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/** True se la domanda chiede una previsione ("Che tempo fa domani?"): gruppo METEO delle liste di parole o scelto dal modello. */
internal fun isForecastQuestion(question: String, modelGroups: Set<String> = emptySet()): Boolean =
    "METEO" in questionGroups(question) + modelGroups

/**
 * Cosa serve a [ForecastSections] dalla domanda: [namesCountry] se la domanda nomina una nazione; [usePosition] solo sul
 * dispositivo, perche' con i modelli online il nome della citta' vicina all'utente o dell'ultima guida aperta arriverebbe
 * al servizio.
 */
internal class ForecastQuery(
    val regionId: String,
    val question: String,
    val previousQuestion: String?,
    val modelGroups: Set<String>,
    val cities: List<String>,
    val cityNames: List<String>,
    val namesCountry: Boolean,
    val language: String,
    val usePosition: Boolean,
)

/**
 * Per una domanda di previsione (isForecastQuestion), le previsioni della citta' nominata nella domanda o, in ordine:
 * la piu' vicina alla posizione dell'utente e quella dell'ultima guida di citta' aperta (solo sul dispositivo), la capitale
 * della regione (o la prima citta' principale), la citta' delle domande precedenti. Null per le altre domande, se la domanda nomina una nazione (le citta' della regione
 * non c'entrano), senza codice paese o senza previsioni (ForecastProvider). Il blocco dice la citta' usata.
 */
class ForecastSections @Inject constructor(
    private val forecastProvider: ForecastProvider,
    private val regionRepository: RegionRepository,
    private val cityRepository: CityRepository,
    private val poiRepository: PoiRepository,
    private val lastKnownPosition: LastKnownPosition,
    private val lastOpenedCityGuide: LastOpenedCityGuide,
) {
    internal suspend fun forQuestion(query: ForecastQuery): ForecastBlock? {
        val countryCode = if (isForecastQuestion(query.question, query.modelGroups) && !query.namesCountry) {
            regionRepository.installed(query.regionId)?.countryCode
        } else {
            null
        }
        val city = countryCode?.let {
            forecastCity(
                query,
                nearest = { nearestCity(query.regionId, query.cityNames) },
                lastOpened = { lastOpenedCityGuide.city(query.regionId) },
                main = { mainCity(query.regionId) },
            )
        }
        return if (city != null) forecastProvider.section(city, countryCode, query.language) else null
    }

    // La citta' della regione piu' vicina all'ultima posizione nota, se l'utente e' nella regione (POI del pacchetto entro
    // ~5 km, come il meteo "vicino a te" della guida) e a meno di MAX_CITY_KM da una citta' con le coordinate.
    private suspend fun nearestCity(regionId: String, cityNames: List<String>): String? {
        val position = lastKnownPosition.get(maxAgeMillis = POSITION_MAX_AGE_MILLIS)
        val inRegion = position != null && poiRepository.inBounds(
            regionId, position.first - NEAR_DEGREES, position.first + NEAR_DEGREES,
            position.second - NEAR_DEGREES, position.second + NEAR_DEGREES, 1,
        ).pois.isNotEmpty()
        if (position == null || !inRegion) return null
        val (latitude, longitude) = position
        val distance = FloatArray(1)
        return cityNames.mapNotNull { name ->
            cityRepository.coordinatesFor(regionId, name)?.let { coordinates ->
                Location.distanceBetween(latitude, longitude, coordinates.latitude, coordinates.longitude, distance)
                name to distance[0]
            }
        }.minByOrNull { it.second }?.takeIf { it.second <= MAX_CITY_KM * METERS_PER_KM }?.first
    }

    private suspend fun mainCity(regionId: String): String? = cityRepository.capitalFor(regionId)

    private companion object {
        const val POSITION_MAX_AGE_MILLIS = 3 * 60 * 60 * 1_000L
        const val NEAR_DEGREES = 0.05
        const val MAX_CITY_KM = 50
        const val METERS_PER_KM = 1_000
    }
}

/**
 * La citta' delle previsioni, nell'ordine di [ForecastSections]. La piu' vicina alla posizione e quella dell'ultima guida
 * aperta solo con [ForecastQuery.usePosition]: con i modelli online direbbero al servizio dove si trova l'utente o cosa
 * ha letto.
 */
internal suspend fun forecastCity(
    query: ForecastQuery,
    nearest: suspend () -> String?,
    lastOpened: () -> String?,
    main: suspend () -> String?,
): String? = query.cities.firstOrNull()
    ?: (if (query.usePosition) nearest() else null)
    ?: (if (query.usePosition) lastOpened()?.takeIf { it in query.cityNames } else null)
    ?: main()
    ?: query.previousQuestion?.let { previous -> namedCities(previous, query.cityNames, query.language).firstOrNull() }

/** Le previsioni nel contesto e quelle mostrate sotto la risposta nel riquadro meteo, con la citta' usata. */
internal class ForecastBlock(val section: AssistantSection, val weather: PlaceWeather)

/**
 * Le previsioni di una citta' come sezione di contesto. Stessa cache e stesso limite per luogo del riquadro meteo della
 * guida (la chiave e' la stessa): se la guida ha appena scaricato il meteo, la domanda non fa richieste in piu'.
 */
class ForecastProvider @Inject constructor(
    private val weatherRepository: WeatherRepository,
    private val clock: Clock,
) {
    /** Null senza previsioni: niente rete e nessuna risposta salvata ancora valida, o troppo lenta. */
    internal suspend fun section(city: String, countryCode: String, language: String): ForecastBlock? {
        val result = withTimeoutOrNull(FORECAST_TIMEOUT_MILLIS) {
            weatherRepository.weather("city|$countryCode|$city") { weatherRepository.coordinatesOf(city, countryCode) }
        } ?: return null
        val section = AssistantSection(
            body = forecastContext(city, result, language, clock.zone),
            category = GuideCategory.DA_SAPERE,
            citation = if (language == "en") "Source: weather forecasts by Open-Meteo.com" else "Fonte: previsioni del tempo di Open-Meteo.com",
        )
        return ForecastBlock(section, PlaceWeather(city, result))
    }

    private companion object {
        // Geocoding e previsioni hanno ognuno il loro tetto di 10 secondi: la risposta dell'assistente non aspetta oltre.
        const val FORECAST_TIMEOUT_MILLIS = 15_000L
    }
}

/**
 * Il blocco delle previsioni per il contesto, in testo semplice: la citta' usata, la fonte, quando sono state scaricate,
 * poi un giorno per riga con condizione, massima/minima e probabilita' di pioggia. Un modello che lo copia scrive un elenco
 * leggibile. Etichette nella
 * lingua delle guide ([language]).
 */
internal fun forecastContext(city: String, result: WeatherResult, language: String, zone: ZoneId): String {
    val en = language == "en"
    val locale = Locale.forLanguageTag(language)
    val weather = result.weather
    val updated = Instant.ofEpochMilli(result.updatedAtMillis).atZone(zone)
    val updatedText = updated.format(
        DateTimeFormatter.ofPattern(if (updated.toLocalDate() == weather.today) "HH:mm" else "d/M HH:mm", locale),
    )
    val days = weather.days.joinToString("") { day ->
        val offset = day.date.toEpochDay() - weather.today.toEpochDay()
        val label = when (offset) {
            0L -> if (en) "today" else "oggi"
            1L -> if (en) "tomorrow" else "domani"
            else -> "${day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)} ${day.date.dayOfMonth}/${day.date.monthValue}"
        }
        val rain = day.rainChance?.let { ", ${if (en) "rain" else "pioggia"} $it%" }.orEmpty()
        "\n- $label: ${day.condition.word(en)}, ${day.max.roundToInt()}/${day.min.roundToInt()} °C$rain"
    }
    return if (en) {
        "Forecast for $city (Open-Meteo, updated at $updatedText):$days"
    } else {
        "Previsioni per $city (Open-Meteo, aggiornate alle $updatedText):$days"
    }
}

private fun WeatherCondition.word(en: Boolean): String = when (this) {
    WeatherCondition.CLEAR -> "clear" to "sereno"
    WeatherCondition.PARTLY_CLOUDY -> "partly cloudy" to "poco nuvoloso"
    WeatherCondition.CLOUDY -> "cloudy" to "coperto"
    WeatherCondition.FOG -> "fog" to "nebbia"
    WeatherCondition.RAIN -> "rain" to "pioggia"
    WeatherCondition.SNOW -> "snow" to "neve"
    WeatherCondition.THUNDERSTORM -> "thunderstorm" to "temporale"
}.let { (english, italian) -> if (en) english else italian }
