package com.pockettravel.core.data

import java.time.Instant
import kotlin.math.cos
import kotlin.math.hypot

/*
 * Fermate vicine a un punto, con i mezzi e le prossime partenze: per ogni rete le fermate nel raggio (rettangolo in
 * microgradi, poi distanza) raggruppate per stazione, con una sola query delle partenze; poi l'unione delle reti.
 */

/** Raggio delle fermate vicine. */
const val NEARBY_STOPS_RADIUS_M = 500.0

/** Partenze mostrate per fermata nella lista delle fermate vicine. */
const val NEARBY_STOP_DEPARTURES = 2

/** Reti diverse con lo stesso nome entro questa distanza sono la stessa fermata. */
private const val MERGE_DISTANCE_M = 100.0

private const val METERS_PER_DEGREE = 111_320.0
private const val MICRODEGREES_PER_DEGREE = 1e6

// Colonne delle query sulle fermate.
private const val STOP_COL_ID = 0
private const val STOP_COL_PARENT = 1
private const val STOP_COL_LAT = 2
private const val STOP_COL_LON = 3
private const val STOP_COL_NAME = 4

/** Una fermata o stazione vicina al punto, con le prossime partenze. */
data class NearbyStop(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    /** Mezzi delle linee che passano dalla fermata (anche senza partenze nella finestra). */
    val modes: Set<TransitMode>,
    /** Le prossime partenze, al massimo NEARBY_STOP_DEPARTURES, in ordine; vuoto se nessuna nelle prossime 3 ore. */
    val next: List<TransitDeparture>,
    /** Id delle reti (TransitFeedInfo.id). */
    val networks: List<String>,
)

private class NearbyRow(val id: Long, val parent: Long?, val name: String, val latitude: Double, val longitude: Double) {
    val group: Long get() = parent ?: id
}

/**
 * Le fermate di una rete entro [radiusMeters] dal punto, dalla piu' vicina: una voce per stazione (con le sue banchine), col
 * nome e la posizione della stazione se c'e'. Null se la rete non si legge (formato diverso) o gli orari sono scaduti.
 */
internal fun readFeedNearbyStops(
    db: TransitQuery,
    feed: TransitFeedInfo?,
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    now: Instant,
    realtime: RealtimeFeed? = null,
): List<NearbyStop>? {
    val meta = readFeedMeta(db) ?: return null
    val dLat = radiusMeters / METERS_PER_DEGREE
    val dLon = dLat / maxOf(cos(Math.toRadians(latitude)), 0.01)
    fun micro(degrees: Double) = Math.round(degrees * MICRODEGREES_PER_DEGREE)
    val near = nearbyRows(
        db,
        "latE6 BETWEEN ${micro(latitude - dLat)} AND ${micro(latitude + dLat)} AND lonE6 BETWEEN ${micro(longitude - dLon)} AND ${micro(longitude + dLon)}",
    ).filter { distanceMeters(latitude, longitude, it.latitude, it.longitude) <= radiusMeters }
    // Senza fermate nel raggio non si leggono ne' mezzi ne' partenze.
    return if (near.isEmpty()) {
        emptyList()
    } else {
        val keys = near.mapTo(mutableSetOf()) { it.group }.joinToString(",")
        val groups = nearbyRows(db, "id IN ($keys) OR parent IN ($keys)").groupBy { it.group }
        val ids = groups.values.flatten().map { it.id }
        val modes = db.query(
            "SELECT DISTINCT ps.stop, r.type FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern JOIN route r ON r.id = t.route " +
                "WHERE ps.stop IN (${ids.joinToString(",")})",
        ) { (it.string(0)?.toLongOrNull() ?: 0L) to transitModeOf(it.int(1)) }.groupBy({ it.first }, { it.second })
        val departures = readStopDepartures(db, meta, feed, ids, now, realtime, perStop = true)?.groupBy({ it.stop }, { it.departure })
        departures?.let { byStop ->
            groups.map { (key, rows) ->
                val head = rows.firstOrNull { it.id == key } ?: rows.minBy { distanceMeters(latitude, longitude, it.latitude, it.longitude) }
                NearbyStop(
                    name = head.name,
                    latitude = head.latitude,
                    longitude = head.longitude,
                    distanceMeters = distanceMeters(latitude, longitude, head.latitude, head.longitude),
                    modes = rows.flatMapTo(mutableSetOf()) { modes[it.id].orEmpty() },
                    next = rows.flatMap { byStop[it.id].orEmpty() }.sortedBy { it.inMinutes }.take(NEARBY_STOP_DEPARTURES),
                    networks = listOfNotNull(feed?.id),
                )
            }.sortedBy { it.distanceMeters }
        }
    }
}

private fun nearbyRows(db: TransitQuery, where: String): List<NearbyRow> =
    db.query("SELECT id, parent, latE6, lonE6, name FROM stop WHERE $where") {
        NearbyRow(
            id = it.string(STOP_COL_ID)?.toLongOrNull() ?: 0L,
            parent = it.string(STOP_COL_PARENT)?.toLongOrNull(),
            name = it.string(STOP_COL_NAME).orEmpty(),
            latitude = (it.string(STOP_COL_LAT)?.toLongOrNull() ?: 0L) / MICRODEGREES_PER_DEGREE,
            longitude = (it.string(STOP_COL_LON)?.toLongOrNull() ?: 0L) / MICRODEGREES_PER_DEGREE,
        )
    }

/**
 * Le voci delle reti in un'unica lista, dalla piu' vicina: reti diverse con lo stesso nome (senza maiuscole e spazi ai
 * lati) a meno di [MERGE_DISTANCE_M] sono una voce, con mezzi e reti uniti, le prime partenze di tutte e la distanza minore.
 */
internal fun mergeNearbyStops(perNetwork: List<List<NearbyStop>>): List<NearbyStop> {
    val merged = mutableListOf<NearbyStop>()
    for (stop in perNetwork.flatten().sortedBy { it.distanceMeters }) {
        val i = merged.indexOfFirst { it.isSameStop(stop) }
        if (i < 0) {
            merged += stop
        } else {
            val first = merged[i]
            merged[i] = first.copy(
                modes = first.modes + stop.modes,
                next = (first.next + stop.next).sortedBy { it.inMinutes }.take(NEARBY_STOP_DEPARTURES),
                networks = (first.networks + stop.networks).distinct(),
            )
        }
    }
    return merged
}

private fun NearbyStop.isSameStop(other: NearbyStop): Boolean =
    name.trim().equals(other.name.trim(), ignoreCase = true) &&
        networks.none { it in other.networks } &&
        distanceMeters(latitude, longitude, other.latitude, other.longitude) <= MERGE_DISTANCE_M

/**
 * Fermate del gruppo del punto: quelle entro [TRANSIT_STOP_RADIUS_M] (rettangolo in microgradi, poi
 * distanza), con le loro stazioni (parent) e tutte le banchine di quelle stazioni. Per una stazione senza
 * fermate cosi' vicine, il gruppo della fermata piu' vicina entro [TRANSIT_STATION_RADIUS_M] servita da uno dei
 * [stationModes]. Vuoto se nessuna.
 */
internal fun nearbyStopIds(db: TransitQuery, latitude: Double, longitude: Double, stationModes: Set<TransitMode> = emptySet()): List<Long> {
    val radius = if (stationModes.isEmpty()) TRANSIT_STOP_RADIUS_M else TRANSIT_STATION_RADIUS_M
    val dLat = radius / METERS_PER_DEGREE
    val dLon = dLat / maxOf(cos(Math.toRadians(latitude)), 0.01)
    fun micro(degrees: Double) = Math.round(degrees * MICRODEGREES_PER_DEGREE)
    val rows = db.query(
        "SELECT id, parent, latE6, lonE6 FROM stop WHERE latE6 BETWEEN ${micro(latitude - dLat)} AND ${micro(latitude + dLat)} " +
            "AND lonE6 BETWEEN ${micro(longitude - dLon)} AND ${micro(longitude + dLon)}",
    ) { StopRow(it.string(STOP_COL_ID)!!.toLong(), it.string(STOP_COL_PARENT)?.toLong(), it.string(STOP_COL_LAT)!!.toLong(), it.string(STOP_COL_LON)!!.toLong()) }
    val distances = rows.associateWith { distanceMeters(latitude, longitude, it.latE6 / MICRODEGREES_PER_DEGREE, it.lonE6 / MICRODEGREES_PER_DEGREE) }
    val near = distances.filterValues { it <= TRANSIT_STOP_RADIUS_M }.keys
        .ifEmpty { listOfNotNull(nearestServedStop(db, distances.filterValues { it <= radius }, stationModes)) }
    if (near.isEmpty()) return emptyList()
    val ids = near.mapTo(mutableSetOf()) { it.id }
    val stations = near.mapTo(mutableSetOf()) { it.parent ?: it.id }
    ids += db.query("SELECT id FROM stop WHERE id IN (${stations.joinToString(",")}) OR parent IN (${stations.joinToString(",")})") { it.string(0)!!.toLong() }
    return ids.sorted()
}

/**
 * La piu' vicina tra le fermate [candidates] (con la distanza) servite da linee di uno dei [modes]; le stazioni parent
 * non hanno passaggi, le loro banchine si'. Null se nessuna.
 */
private fun nearestServedStop(db: TransitQuery, candidates: Map<StopRow, Double>, modes: Set<TransitMode>): StopRow? {
    if (candidates.isEmpty()) return null
    val served = db.query(
        "SELECT DISTINCT ps.stop, r.type FROM pattern_stop ps JOIN trip t ON t.pattern = ps.pattern JOIN route r ON r.id = t.route " +
            "WHERE ps.stop IN (${candidates.keys.joinToString(",") { it.id.toString() }})",
    ) { it.string(0)?.toLongOrNull() to transitModeOf(it.int(1)) }
        .filter { it.second in modes }.mapNotNullTo(mutableSetOf()) { it.first }
    return candidates.filterKeys { it.id in served }.minByOrNull { it.value }?.key
}

private data class StopRow(val id: Long, val parent: Long?, val latE6: Long, val lonE6: Long)

/** Distanza approssimata (piano locale), abbondante per qualche centinaio di metri. */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dy = (lat2 - lat1) * METERS_PER_DEGREE
    val dx = (lon2 - lon1) * METERS_PER_DEGREE * cos(Math.toRadians((lat1 + lat2) / 2))
    return hypot(dx, dy)
}
