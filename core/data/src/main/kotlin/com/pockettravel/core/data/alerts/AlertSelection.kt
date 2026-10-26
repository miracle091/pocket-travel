package com.pockettravel.core.data.alerts

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Magnitudo minima di un terremoto da mostrare (scelta dell'utente del 2026-10-26). */
private const val QUAKE_MIN_MAGNITUDE = 4.5

/** Nelle nazioni molto sismiche una scossa di magnitudo 4,5 capita ogni giorno: la soglia sale. */
private const val QUAKE_MIN_MAGNITUDE_SEISMIC = 5.0

/** Segnalazioni "l'ho sentito" oltre le quali una scossa piu' piccola si mostra lo stesso (zona abitata). */
private const val QUAKE_MIN_FELT_REPORTS = 100

/** Un terremoto resta tra le allerte per tre giorni (scosse di assestamento, strade e servizi). */
private val QUAKE_RECENT = Duration.ofHours(72)

/** Un evento GDACS e uno USGS a meno di un'ora l'uno dall'altro sono lo stesso terremoto. */
private val SAME_QUAKE = Duration.ofHours(1)

/** Nazioni con terremoti frequenti (codice ISO 3166-1 alpha-2, minuscolo come nel catalogo). */
private val SEISMIC_COUNTRIES = setOf(
    "jp", "id", "cl", "pe", "ph", "nz", "mx", "tr", "ir", "ec", "gt", "pg", "vu", "to", "sb", "tw",
)

/**
 * Le allerte da mostrare per una regione, dalla piu' grave: dentro il suo rettangolo ([bounds], null senza mappa
 * installata: allora solo quelle senza coordinate), ancora valide a [now], terremoti filtrati per magnitudo con la soglia
 * di [countryCode], segnalazioni e allerta PAGER, senza terremoti verdi di GDACS e senza doppioni GDACS/USGS.
 */
fun selectAlerts(alerts: List<Alert>, bounds: AlertBounds?, countryCode: String?, now: Instant): List<Alert> {
    val inRegion = if (bounds != null) alerts.inside(bounds) else alerts.filter { it.area == null }
    val kept = inRegion.filter { it.isActiveAt(now) && it.isRelevantQuake(countryCode, now) }
    val usgsQuakes = kept.filter { it.source == AlertSource.USGS }.mapNotNull { it.startsAt }
    return kept
        .filterNot { alert -> alert.source == AlertSource.GDACS && alert.kind == AlertKind.EARTHQUAKE && alert.startsAt.isNear(usgsQuakes) }
        .sortedWith(compareByDescending<Alert> { it.level }.thenByDescending { it.startsAt })
}

private fun Alert.isRelevantQuake(countryCode: String?, now: Instant): Boolean = when {
    kind != AlertKind.EARTHQUAKE -> true
    source == AlertSource.GDACS -> sourceLevel != "Green"
    else -> startsAt?.isAfter(now - QUAKE_RECENT) == true && isStrongEnough(countryCode)
}

private fun Alert.isStrongEnough(countryCode: String?): Boolean {
    val threshold = if (countryCode?.lowercase(Locale.ROOT) in SEISMIC_COUNTRIES) QUAKE_MIN_MAGNITUDE_SEISMIC else QUAKE_MIN_MAGNITUDE
    return (magnitude ?: 0.0) >= threshold || (feltReports ?: 0) >= QUAKE_MIN_FELT_REPORTS || impactAlert != null
}

private fun Instant?.isNear(others: List<Instant>): Boolean =
    this != null && others.any { Duration.between(it, this).abs() <= SAME_QUAKE }

/**
 * Feed Meteoalarm di una nazione (codice ISO alpha-2, minuscolo); null fuori dai paesi di Meteoalarm. Indirizzi provati con
 * curl il 2026-10-26: tutti rispondono.
 */
fun meteoalarmCountry(countryCode: String?): String? = METEOALARM_COUNTRIES[countryCode?.lowercase(Locale.ROOT)]

private val METEOALARM_COUNTRIES = mapOf(
    "at" to "austria", "be" to "belgium", "ba" to "bosnia-herzegovina", "bg" to "bulgaria", "hr" to "croatia",
    "cy" to "cyprus", "cz" to "czechia", "dk" to "denmark", "ee" to "estonia", "fi" to "finland", "fr" to "france",
    "de" to "germany", "gr" to "greece", "hu" to "hungary", "is" to "iceland", "ie" to "ireland", "il" to "israel",
    "it" to "italy", "lv" to "latvia", "lt" to "lithuania", "lu" to "luxembourg", "mt" to "malta", "md" to "moldova",
    "me" to "montenegro", "nl" to "netherlands", "mk" to "republic-of-north-macedonia", "no" to "norway", "pl" to "poland",
    "pt" to "portugal", "ro" to "romania", "rs" to "serbia", "sk" to "slovakia", "si" to "slovenia", "es" to "spain",
    "se" to "sweden", "ch" to "switzerland", "gb" to "united-kingdom", "ua" to "ukraine",
)
