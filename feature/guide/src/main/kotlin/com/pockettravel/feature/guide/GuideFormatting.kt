package com.pockettravel.feature.guide

import com.pockettravel.core.data.EmergencyNumbers
import java.util.Locale
import kotlin.math.roundToInt

// Testi della guida calcolati senza interfaccia: distanze, numeri di emergenza raggruppati, abitanti arrotondati.

private const val METERS_PER_KM = 1000
private const val DISTANCE_DECIMALS_BELOW_KM = 10
private const val POPULATION_ROUND_FROM = 10_000L
private const val POPULATION_ROUND_STEP = 1_000L
private const val POPULATION_ROUND_HALF_STEP = 500L

// "800 m", "2,3 km", "45 km": decimali solo sotto i 10 km, nel formato della lingua.
internal fun distanceText(km: Double): String = when {
    km < 1 -> "${(km * METERS_PER_KM).roundToInt()} m"
    km < DISTANCE_DECIMALS_BELOW_KM -> String.format(Locale.getDefault(), "%.1f km", km)
    else -> "${km.roundToInt()} km"
}

// Se il numero generale coincide con tutti gli altri (es. 911 negli Stati Uniti, 112 in
// Andorra) mostra una sola riga invece di quattro identiche; altrimenti raggruppa per numero
// (es. Giappone: ambulanza e vigili del fuoco condividono il 119) cosi' un numero condiviso
// non compare due volte.
internal fun EmergencyNumbers.entries(): List<Pair<List<Int>, String>> {
    val generalNumber = general
    if (generalNumber != null && listOf(police, ambulance, fire).all { it == generalNumber }) {
        return listOf(listOf(R.string.emergency_single) to generalNumber)
    }
    val labeled = buildList {
        general?.let { add(R.string.emergency_general to it) }
        add(R.string.emergency_police to police)
        add(R.string.emergency_ambulance to ambulance)
        add(R.string.emergency_fire to fire)
    }
    return labeled.groupBy({ it.second }, { it.first }).map { (number, labels) -> labels to number }
}

// Abitanti da mostrare: arrotondati alle migliaia da 10.000 in su, esatti sotto.
internal fun roundedPopulation(population: Long): Long = if (population >= POPULATION_ROUND_FROM) {
    (population + POPULATION_ROUND_HALF_STEP) / POPULATION_ROUND_STEP * POPULATION_ROUND_STEP
} else {
    population
}
