package com.pockettravel.core.data

import java.time.LocalDate

/*
 * Corse del feed GTFS-RT senza trip_id (HSL): si agganciano alle corse di transit.db con linea, direzione, giorno e ora
 * di partenza (meta trip_keys).
 */

/** Chiave di una corsa senza trip_id: linea (route_id GTFS), direzione, giorno di servizio e ora di partenza in secondi GTFS. */
internal data class TripKey(val routeId: String, val directionId: Int?, val serviceDate: LocalDate, val startSeconds: Int)

/** Le corse di [feed] senza trip_id per [TripKey]; scartate quelle senza linea, data o ora di partenza (se si ripetono vale la prima). */
internal fun keyedTrips(feed: RealtimeFeed): Map<TripKey, RealtimeTrip> {
    val result = mutableMapOf<TripKey, RealtimeTrip>()
    for (trip in feed.tripsWithoutId) {
        val key = tripKeyOf(trip) ?: continue
        result.putIfAbsent(key, trip)
    }
    return result
}

private fun tripKeyOf(trip: RealtimeTrip): TripKey? {
    val route = trip.routeId
    val date = trip.startDate
    val start = gtfsTimeSeconds(trip.startTime)
    return if (route == null || date == null || start == null) null else TripKey(route, trip.directionId, date, start)
}

/** "HH:MM:SS" (anche oltre 24 ore) in secondi; null se non e' in quella forma. */
internal fun gtfsTimeSeconds(text: String?): Int? {
    val parts = text?.split(':')?.map { it.toIntOrNull() }
        ?.takeIf { numbers -> numbers.size == SECONDS_PARTS && numbers.all { it != null && it >= 0 } }
        ?.filterNotNull() ?: return null
    return parts[0] * SECONDS_PER_HOUR + parts[1] * SECONDS_PER_MINUTE + parts[2]
}

/** route_id, direction_id e start_time GTFS delle corse e delle linee in [calls] (reti con trip_keys). */
internal class TripKeyLookup(
    private val routes: Map<Int, String>,
    private val trips: Map<Int, Pair<Int?, Int?>>,
) {
    fun keyOf(call: RealtimeCall): TripKey? {
        val route = routes[call.route]
        val trip = trips[call.trip]
        val start = trip?.second
        return if (route == null || start == null) null else TripKey(route, trip.first, call.serviceDate, start)
    }
}

internal fun readTripKeys(db: TransitQuery, calls: List<RealtimeCall>): TripKeyLookup {
    val trips = db.query("SELECT id, direction_id, start_time FROM gtfs_trip WHERE id IN (${calls.map { it.trip }.distinct().joinToString(",")})") {
        it.int(0) to Pair(it.string(1)?.toIntOrNull(), it.string(2)?.toIntOrNull())
    }.toMap()
    val routes = db.query("SELECT id, route_id FROM gtfs_route WHERE id IN (${calls.map { it.route }.distinct().joinToString(",")})") {
        it.int(0) to it.string(1).orEmpty()
    }.toMap()
    return TripKeyLookup(routes, trips)
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PARTS = 3
