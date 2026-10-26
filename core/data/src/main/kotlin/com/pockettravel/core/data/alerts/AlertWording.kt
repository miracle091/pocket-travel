package com.pockettravel.core.data.alerts

import java.util.Locale

/*
 * Le fonti scrivono le allerte solo in inglese, ma con schemi fissi: qui si leggono i pezzi (colore, tipo, magnitudo,
 * distanza, nazioni) che l'app riscrive nella sua lingua. Ogni funzione da' null quando il testo non segue lo schema:
 * allora resta il testo originale.
 */

/** Colore di un'allerta meteo di Meteoalarm. */
enum class WarningColor { YELLOW, ORANGE, RED }

/** Tipo di un'allerta meteo di Meteoalarm, con il nome che ha nel titolo della fonte. */
enum class WeatherHazard(internal val sourceName: String) {
    WIND("wind"),
    SNOW_ICE("snow-ice"),
    THUNDERSTORM("thunderstorm"),
    FOG("fog"),
    HIGH_TEMPERATURE("high-temperature"),
    LOW_TEMPERATURE("low-temperature"),
    COASTAL_EVENT("coastalevent"),
    FOREST_FIRE("forest-fire"),
    AVALANCHES("avalanches"),
    RAIN("rain"),
    FLOODING("flooding"),
    RAIN_FLOOD("rain-flood"),
}

/** Allerta di Meteoalarm letta dal titolo: colore e tipo ("Yellow Wind Warning issued for Italy - Basilicata"). */
data class WeatherWarning(val color: WarningColor, val hazard: WeatherHazard)

/** Categoria di un ciclone tropicale nel testo di GDACS. */
enum class CycloneCategory { DEPRESSION, STORM, HURRICANE }

/** Dettaglio di un evento GDACS letto da `severitytext`. */
sealed interface GdacsDetail {
    /** "Magnitude 7.7M, Depth:12.647km". */
    data class Quake(val magnitude: Double, val depthKm: Double) : GdacsDetail

    /** "Tropical Storm (maximum wind speed of 74 km/h)". */
    data class Cyclone(val category: CycloneCategory?, val maxWindKmh: Int) : GdacsDetail

    /** "Orange impact for forestfire in 18310 ha" (ettari) o "Medium impact for agricultural drought in 1587809 km2". */
    data class Area(val value: Long, val hectares: Boolean) : GdacsDetail

    /** Testo senza informazioni ("Magnitude 0" delle alluvioni): non si mostra. */
    data object Empty : GdacsDetail
}

/**
 * Luogo di un terremoto USGS nella forma "52 km WNW of Malfa, Italy": [direction] e' l'indice nella rosa dei 16 venti
 * da nord in senso orario (0 = N, 1 = NNE ... 15 = NNW), [place] il resto ("Malfa, Italy").
 */
data class QuakePlace(val km: Int, val direction: Int, val place: String)

private val WARNING_TITLE = Regex("""(Yellow|Orange|Red) (\S+) Warning issued for .+""", RegexOption.IGNORE_CASE)

/** Colore e tipo di un'allerta Meteoalarm dal suo titolo; null se il titolo non segue lo schema della fonte. */
fun weatherWarning(title: String): WeatherWarning? {
    val match = WARNING_TITLE.matchEntire(title.trim()) ?: return null
    val color = WarningColor.valueOf(match.groupValues[1].uppercase(Locale.ROOT))
    val name = match.groupValues[2].lowercase(Locale.ROOT)
    return WeatherHazard.entries.firstOrNull { it.sourceName == name }?.let { WeatherWarning(color, it) }
}

private val GDACS_PREFIXES = mapOf(AlertKind.CYCLONE to "Tropical Cyclone", AlertKind.VOLCANO to "Eruption")

/** Nome proprio di un ciclone o di un vulcano GDACS ("Tropical Cyclone ARNAB-26" -> "ARNAB-26", "Eruption  Taal" -> "Taal"). */
fun gdacsEventName(kind: AlertKind, title: String): String? {
    val prefix = GDACS_PREFIXES[kind] ?: return null
    return title.trim().takeIf { it.startsWith(prefix, ignoreCase = true) }
        ?.substring(prefix.length)?.trim()?.takeIf { it.isNotEmpty() }
}

private val QUAKE_DETAIL = Regex("""Magnitude ([\d.]+)M, Depth:([\d.]+)km""")
private val CYCLONE_DETAIL = Regex("""(.+?) \(maximum wind speed of (\d+) km/h\)""")
private val AREA_DETAIL = Regex(""".+ impact for .+ in (\d+) (ha|km2)""")
private val EMPTY_DETAIL = Regex("""Magnitude 0""")

/** Dettaglio GDACS da `severitytext`; null se il testo non segue nessuno schema noto. */
fun gdacsDetail(text: String): GdacsDetail? {
    val trimmed = text.trim()
    return quakeDetail(trimmed) ?: cycloneDetail(trimmed) ?: areaDetail(trimmed)
        ?: GdacsDetail.Empty.takeIf { trimmed.isEmpty() || EMPTY_DETAIL.matches(trimmed) }
}

private fun quakeDetail(text: String): GdacsDetail? {
    val (magnitude, depth) = QUAKE_DETAIL.matchEntire(text)?.destructured ?: return null
    val m = magnitude.toDoubleOrNull()
    val d = depth.toDoubleOrNull()
    return if (m != null && d != null) GdacsDetail.Quake(m, d) else null
}

private fun cycloneDetail(text: String): GdacsDetail? {
    val (name, wind) = CYCLONE_DETAIL.matchEntire(text)?.destructured ?: return null
    val category = when {
        name.startsWith("Tropical Depression") -> CycloneCategory.DEPRESSION
        name.startsWith("Tropical Storm") -> CycloneCategory.STORM
        name.startsWith("Hurricane") -> CycloneCategory.HURRICANE
        else -> null
    }
    return GdacsDetail.Cyclone(category, wind.toInt())
}

private fun areaDetail(text: String): GdacsDetail? =
    AREA_DETAIL.matchEntire(text)?.destructured?.let { (value, unit) -> GdacsDetail.Area(value.toLong(), unit == "ha") }

private val COMPASS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
private val QUAKE_PLACE = Regex("""(\d+) km ([NSEW]{1,3}) of (.+)""")

/** Distanza, direzione e luogo di un terremoto USGS ("52 km WNW of Malfa, Italy"); null per le altre forme ("Reykjanes Ridge"). */
fun quakePlace(text: String): QuakePlace? {
    val (km, compass, place) = QUAKE_PLACE.matchEntire(text.trim())?.destructured ?: return null
    val direction = COMPASS.indexOf(compass)
    return if (direction < 0) null else QuakePlace(km.toInt(), direction, place.trim())
}

// Nomi delle fonti diversi da quelli inglesi del JDK.
private val COUNTRY_ALIASES = mapOf(
    "bosnia & herzegovina" to "BA",
    "bosnia and herzegovina" to "BA",
    "czech republic" to "CZ",
    "democratic republic of congo" to "CD",
    "democratic republic of the congo" to "CD",
    "republic of congo" to "CG",
    "the bahamas" to "BS",
    "viet nam" to "VN",
    "méxico" to "MX",
    "timor leste" to "TL",
    "east timor" to "TL",
    "myanmar" to "MM",
    "turkey" to "TR",
    "ivory coast" to "CI",
    "cape verde" to "CV",
    "macedonia" to "MK",
    "north macedonia" to "MK",
    "swaziland" to "SZ",
    "palestine" to "PS",
    "hong kong" to "HK",
    "macau" to "MO",
    "saint pierre and miquelon" to "PM",
)

private val ENGLISH_COUNTRIES: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT) } +
        COUNTRY_ALIASES
}

/** Codice ISO 3166-1 alpha-2 di una nazione scritta in inglese dalle fonti ("Italy", "Viet Nam"); null se sconosciuta. */
fun countryCode(englishName: String): String? =
    ENGLISH_COUNTRIES[englishName.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)]

/** Nazioni dell'elenco GDACS separato da virgole ("Japan, China, "), senza i vuoti. */
fun gdacsCountries(text: String?): List<String> = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
