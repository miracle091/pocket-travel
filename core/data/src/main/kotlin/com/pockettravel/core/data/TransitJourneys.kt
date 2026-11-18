package com.pockettravel.core.data

import com.pockettravel.core.transitrouting.DbStop
import com.pockettravel.core.transitrouting.DbTrip
import com.pockettravel.core.transitrouting.Place
import com.pockettravel.core.transitrouting.Raptor
import com.pockettravel.core.transitrouting.RideLeg
import com.pockettravel.core.transitrouting.WalkLeg
import com.pockettravel.core.transitrouting.dayNetwork
import com.pockettravel.core.transitrouting.distanceMeters
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Un modo di arrivare con i mezzi pubblici di una rete: tratte in ordine, orari veri nel fuso della rete. */
data class TransitJourney(val legs: List<JourneyLeg>, val network: String?) {
    val departure: Instant get() = legs.first().departure
    val arrival: Instant get() = legs.last().arrival
    val transfers: Int get() = (legs.count { it is JourneyLeg.Ride } - 1).coerceAtLeast(0)
}

/** Tratta di un [TransitJourney]; [fromName] e [toName] null per la partenza e l'arrivo del viaggio. */
sealed interface JourneyLeg {
    val fromName: String?
    val toName: String?
    val fromLat: Double
    val fromLon: Double
    val toLat: Double
    val toLon: Double
    val departure: Instant
    val arrival: Instant

    data class Walk(
        override val fromName: String?,
        override val toName: String?,
        override val fromLat: Double,
        override val fromLon: Double,
        override val toLat: Double,
        override val toLon: Double,
        override val departure: Instant,
        override val arrival: Instant,
        val meters: Int,
    ) : JourneyLeg

    /** In corsa sulla linea [line] verso [headsign], [stops] fermate dopo quella dove si sale. */
    data class Ride(
        override val fromName: String?,
        override val toName: String?,
        override val fromLat: Double,
        override val fromLon: Double,
        override val toLat: Double,
        override val toLon: Double,
        override val departure: Instant,
        override val arrival: Instant,
        val line: String,
        val mode: TransitMode,
        val color: Int?,
        val textColor: Int?,
        val headsign: String?,
        val stops: Int,
    ) : JourneyLeg
}

/**
 * Itinerari con RAPTOR su una transit.db da ([fromLat], [fromLon]) a ([toLat], [toLon]) partendo a [now]: corse del
 * giorno e di quello prima tra un'ora prima e JOURNEY_WINDOW_MINUTES dopo. Null se la rete ha un formato che l'app non
 * legge o non ha le fermate delle corse (meta trip_stops, reti costruite prima).
 */
internal fun readFeedJourneys(
    db: TransitQuery,
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
    now: Instant,
    network: String? = null,
): List<TransitJourney>? {
    val meta = readFeedMeta(db)?.takeIf { it.tripStops } ?: return null
    val local = now.atZone(meta.zone)
    val dayStart = local.toLocalDate().atStartOfDay(meta.zone)
    val dayIndex = ChronoUnit.DAYS.between(meta.windowStart, local.toLocalDate()).toInt()
    val minute = local.hour * MINUTES_PER_HOUR + local.minute
    val from = minute - JOURNEY_LOOKBACK_MINUTES
    val to = minute + JOURNEY_WINDOW_MINUTES
    val stops = db.query("SELECT id, latE6, lonE6, parent, name FROM stop ORDER BY id") { row ->
        DbStop(row.int(0), row.int(1), row.int(2), row.string(3)?.toIntOrNull()) to row.string(4)
    }
    // Le corse partite fino a JOURNEY_MAX_TRIP_MINUTES prima della finestra, anche quelle del giorno prima (oltre 1440).
    val trips = db.query(
        "SELECT t.id, t.route, t.start, sv.days, t.full_pattern, r.short_name, r.long_name, r.type, r.color, r.text_color, h.text " +
            "FROM trip t JOIN service sv ON sv.id = t.service JOIN route r ON r.id = t.route LEFT JOIN headsign h ON h.id = t.headsign " +
            "WHERE t.full_pattern IS NOT NULL AND (t.start BETWEEN ${from - JOURNEY_MAX_TRIP_MINUTES} AND $to " +
            "OR t.start BETWEEN ${from + DAY_MINUTES - JOURNEY_MAX_TRIP_MINUTES} AND ${to + DAY_MINUTES})",
    ) { row ->
        val days = row.bytes(TRIP_COL_DAYS) ?: return@query null
        val color = parseGtfsColor(row.string(TRIP_COL_COLOR))
        DbTrip(row.int(0), row.int(1), row.int(2), days, row.int(TRIP_COL_PATTERN)) to TripInfo(
            line = row.string(TRIP_COL_SHORT)?.takeIf { it.isNotBlank() } ?: row.string(TRIP_COL_LONG)?.takeIf { it.isNotBlank() } ?: "?",
            mode = transitModeOf(row.int(TRIP_COL_TYPE)),
            color = color,
            textColor = parseGtfsColor(row.string(TRIP_COL_TEXT_COLOR)) ?: color?.let(::contrastingTextColor),
            headsign = row.string(TRIP_COL_HEADSIGN)?.takeIf { it.isNotBlank() },
        )
    }.filterNotNull()
    val patterns = trips.map { it.first.fullPattern }.distinct()
    // Senza corse nella finestra nessuna query (IN () non e' SQL valido): la rete resta vuota e non ci sono itinerari.
    val tripStops = if (patterns.isEmpty()) {
        emptyMap()
    } else {
        db.query("SELECT pattern, stops FROM trip_stop WHERE pattern IN (${patterns.joinToString(",")})") { row ->
            row.bytes(1)?.let { row.int(0) to it }
        }.filterNotNull().toMap()
    }
    val day = dayNetwork(stops.map { it.first }, tripStops, trips.map { it.first }, dayIndex, from, to, meta.windowDays)
    val info = trips.associate { (trip, info) -> trip.id to info }
    fun at(minuteOfDay: Int): Instant = dayStart.plusMinutes(minuteOfDay.toLong()).toInstant()
    fun name(stop: Int?): String? = stop?.let { stops[it].second }
    return Raptor(day.network).plan(Place(fromLat, fromLon), Place(toLat, toLon), minute).map { itinerary ->
        TransitJourney(
            itinerary.legs.map { leg ->
                when (leg) {
                    is WalkLeg -> JourneyLeg.Walk(
                        name(leg.from.stop), name(leg.to.stop), leg.from.lat, leg.from.lon, leg.to.lat, leg.to.lon,
                        at(leg.departMinute), at(leg.arriveMinute), leg.meters,
                    )
                    is RideLeg -> {
                        val trip = day.network.patterns[leg.pattern].trips[leg.trip]
                        val tripInfo = info.getValue(trip.id)
                        JourneyLeg.Ride(
                            name(leg.from.stop), name(leg.to.stop), leg.from.lat, leg.from.lon, leg.to.lat, leg.to.lon,
                            at(leg.departMinute), at(leg.arriveMinute), tripInfo.line, tripInfo.mode, tripInfo.color, tripInfo.textColor,
                            tripInfo.headsign, leg.alightIndex - leg.boardIndex,
                        )
                    }
                }
            },
            network,
        )
    }
}

/** Distanza in metri dalla fermata della rete piu' vicina a un punto; null se la rete non ha fermate. */
internal fun nearestStopMeters(db: TransitQuery, latitude: Double, longitude: Double): Double? =
    db.query("SELECT latE6, lonE6 FROM stop") { row -> distanceMeters(latitude, longitude, row.int(0) / E6, row.int(1) / E6) }.minOrNull()

private class TripInfo(val line: String, val mode: TransitMode, val color: Int?, val textColor: Int?, val headsign: String?)

// Scelte dell'utente del 2026-11-18 (piano navigazione-offline.md, indagine RAPTOR): corse da un'ora prima a tre dopo.
private const val JOURNEY_LOOKBACK_MINUTES = 60
private const val JOURNEY_WINDOW_MINUTES = 180

// Una corsa partita da piu' di cosi' prima della finestra non si legge: le corse piu' lunghe, rare, si perdono.
private const val JOURNEY_MAX_TRIP_MINUTES = 240
private const val MINUTES_PER_HOUR = 60
private const val DAY_MINUTES = 1440
private const val E6 = 1e6
private const val TRIP_COL_DAYS = 3
private const val TRIP_COL_PATTERN = 4
private const val TRIP_COL_SHORT = 5
private const val TRIP_COL_LONG = 6
private const val TRIP_COL_TYPE = 7
private const val TRIP_COL_COLOR = 8
private const val TRIP_COL_TEXT_COLOR = 9
private const val TRIP_COL_HEADSIGN = 10
