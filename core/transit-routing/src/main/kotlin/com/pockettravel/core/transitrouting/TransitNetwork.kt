package com.pockettravel.core.transitrouting

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Fermata di una rete; [parent] la stazione a cui appartiene (stesso parent = stessa stazione), o null. */
data class TransitStop(val lat: Double, val lon: Double, val parent: String? = null)

/**
 * Corsa di un [TransitPattern]: per ogni fermata del pattern l'arrivo e la partenza, in minuti dalla mezzanotte del giorno
 * della ricerca (le corse dopo mezzanotte superano 1440, quelle partite ieri si danno gia' meno 1440). [id] e' quello del
 * chiamante, per mostrare la corsa.
 */
class PatternTrip(val id: Int, val arrivals: IntArray, val departures: IntArray)

/**
 * Corse con le stesse fermate nello stesso ordine e senza sorpassi tra loro (come `trip.full_pattern` di transit.db):
 * [stops] indici in [TransitNetwork.stops], [trips] ordinate per partenza; [canBoard] e [canAlight] per fermata, null =
 * sempre. [routeId] e' quello del chiamante, per mostrare la linea.
 */
class TransitPattern(
    val routeId: Int,
    val stops: IntArray,
    val trips: List<PatternTrip>,
    val canBoard: BooleanArray? = null,
    val canAlight: BooleanArray? = null,
)

/** Passaggio a piedi da una fermata a [to], in [minutes] gia' con il tempo di cambio. */
class Footpath(val to: Int, val minutes: Int, val meters: Int)

/**
 * Una rete GTFS pronta per [Raptor]: fermate, pattern e i passaggi a piedi tra fermate entro [transferMeters] o della
 * stessa stazione, calcolati qui dalle coordinate (transit.db non ha `transfers.txt`).
 */
class TransitNetwork(
    val stops: List<TransitStop>,
    val patterns: List<TransitPattern>,
    transferMeters: Int = TRANSFER_METERS,
) {
    /** Per fermata, i pattern che ci passano con la posizione della fermata nel pattern. */
    internal val patternsAt: List<List<Pair<Int, Int>>> = List(stops.size) { mutableListOf<Pair<Int, Int>>() }.also { at ->
        patterns.forEachIndexed { p, pattern ->
            pattern.stops.forEachIndexed { i, stop -> at[stop].add(p to i) }
        }
    }

    internal val footpaths: Array<List<Footpath>> = footpaths(stops, transferMeters)
}

/**
 * Passaggi a piedi tra fermate entro [maxMeters] in linea d'aria o con lo stesso parent, con il tempo a piedi
 * (walkMinutes) piu' CHANGE_MINUTES. Le fermate si cercano in una griglia di celle di [maxMeters].
 */
internal fun footpaths(stops: List<TransitStop>, maxMeters: Int): Array<List<Footpath>> {
    val cellLat = maxMeters / METERS_PER_DEGREE

    // Larghezza della cella dalla latitudine al centro della riga, uguale per tutta la riga: con quella di ogni
    // fermata, a longitudini alte due fermate vicine in righe accanto finirebbero a piu' di una colonna di distanza.
    fun col(lon: Double, row: Int): Int = floor(lon * cos((row + ROW_CENTER) * cellLat * RADIANS).coerceAtLeast(MIN_COS) / cellLat).toInt()
    fun row(lat: Double): Int = floor(lat / cellLat).toInt()
    val grid = stops.indices.groupBy { stops[it].let { stop -> row(stop.lat).let { r -> r to col(stop.lon, r) } } }
    val byParent = stops.indices.filter { stops[it].parent != null }.groupBy { stops[it].parent }
    return Array(stops.size) { from ->
        val stop = stops[from]
        val row = row(stop.lat)
        val near = (-1..1).flatMap { dr ->
            val r = row + dr
            val c = col(stop.lon, r)
            (-1..1).flatMap { dc -> grid[r to c + dc].orEmpty() }
        }
        val sameStation = stop.parent?.let { byParent[it] }.orEmpty()
        (near + sameStation).distinct().filter { it != from }.mapNotNull { to ->
            val meters = distanceMeters(stop.lat, stop.lon, stops[to].lat, stops[to].lon)
            if (meters <= maxMeters || (stop.parent != null && stop.parent == stops[to].parent)) {
                Footpath(to, walkMinutes(meters) + CHANGE_MINUTES, meters.toInt())
            } else {
                null
            }
        }
    }
}

/** Minuti a piedi per [meters] in linea d'aria: il percorso vero e' circa WALK_DETOUR volte piu' lungo, a WALK_KMH. */
fun walkMinutes(meters: Double): Int = ceil(meters * WALK_DETOUR / WALK_METERS_PER_MINUTE).toInt()

/** Distanza in metri sulla sfera terrestre (haversine). */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * RADIANS
    val dLon = (lon2 - lon1) * RADIANS
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * RADIANS) * cos(lat2 * RADIANS) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

// Scelte dell'utente del 2026-11-18: cambi a piedi entro 300 m, fermate entro 800 m da partenza e arrivo, 4,5 km/h,
// percorso a piedi 1,3 volte la linea d'aria, un minuto per ogni cambio.
const val TRANSFER_METERS = 300
const val ACCESS_METERS = 800
const val WALK_KMH = 4.5
const val WALK_DETOUR = 1.3
const val CHANGE_MINUTES = 1

private const val WALK_METERS_PER_MINUTE = WALK_KMH * 1000 / 60

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val METERS_PER_DEGREE = 111_320.0
private const val RADIANS = PI / 180

// Vicino ai poli il coseno va a zero: le celle non diventano piu' larghe di cosi'.
private const val MIN_COS = 0.01

// Meta' riga: la latitudine al centro della riga della griglia.
private const val ROW_CENTER = 0.5
