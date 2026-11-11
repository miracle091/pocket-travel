package com.pockettravel.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Vista della corsa: tutte le fermate di una corsa con gli orari e il tempo reale, dalla tabella trip_stop di
 * transit.db (meta trip_stops; le reti costruite prima non l'hanno e la corsa non si apre).
 */

/**
 * Una corsa di una partenza del tabellone. [network]: id della rete (TransitFeedInfo.id), [trip] l'id della corsa in
 * transit.db, [serviceDate] il giorno di servizio, [stop] la fermata del tabellone (id di transit.db) e [minute] il
 * minuto di servizio GTFS a quella fermata. [pattern] e [start] sono la chiave della corsa nella tabella trip.
 */
data class TripRef(
    val network: String,
    val trip: Int,
    val serviceDate: LocalDate,
    val stop: Long,
    val minute: Int,
    val pattern: Int,
    val start: Int,
)

/** La corsa con le sue fermate in ordine, dall'ultima salita al capolinea d'arrivo; linea e colori come in [TransitDeparture]. */
data class TripView(
    val line: String,
    val mode: TransitMode,
    val color: Int?,
    val textColor: Int?,
    val headsign: String?,
    val stops: List<TripStopTime>,
)

/**
 * Il passaggio a una fermata: [minuteOfDay] e' l'ora programmata sull'orologio della rete, [inMinutes] i minuti da adesso
 * (con il ritardo). [pickup] e [dropOff]: si puo' salire e scendere. [isBoardStop]: la fermata del tabellone.
 */
data class TripStopTime(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val minuteOfDay: Int,
    val inMinutes: Int,
    val pickup: Boolean,
    val dropOff: Boolean,
    val isBoardStop: Boolean,
    val realtime: DepartureRealtime?,
)

/** Una fermata del blob di trip_stop: [seq] e' lo stop_sequence GTFS, [stop] l'id di transit.db, [offset] i minuti dalla partenza (trip.start). */
internal class RawTripStop(val seq: Int, val stop: Int, val offset: Int, val flags: Int) {
    val pickup: Boolean get() = flags and FLAG_NO_PICKUP == 0
    val dropOff: Boolean get() = flags and FLAG_NO_DROP_OFF == 0
}

private const val FLAG_NO_PICKUP = 1
private const val FLAG_NO_DROP_OFF = 2
private const val VARINT_PAYLOAD_BITS = 7
private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUE = 0x80
private const val VARINT_MAX_SHIFT = 28
private const val BYTE_MASK = 0xFF

/**
 * Le fermate del blob di trip_stop: per ogni fermata varint(stop_sequence meno il precedente), varint(zigzag(stop meno il
 * precedente)), varint(zigzag(offset meno il precedente)) e un byte di flag (bit 0 senza salita, bit 1 senza discesa); il
 * primo campo di ogni tipo e' relativo a 0. Null se il blob e' troncato o malformato.
 */
internal fun decodeTripStops(blob: ByteArray): List<RawTripStop>? {
    var pos = 0
    var broken = false
    fun varint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            if (pos >= blob.size || shift > VARINT_MAX_SHIFT) {
                broken = true
                return 0
            }
            val b = blob[pos++].toInt() and BYTE_MASK
            result = result or ((b and VARINT_PAYLOAD_MASK) shl shift)
            if (b and VARINT_CONTINUE == 0) return result
            shift += VARINT_PAYLOAD_BITS
        }
    }
    fun zigzag(): Int = varint().let { (it ushr 1) xor -(it and 1) }
    val stops = mutableListOf<RawTripStop>()
    var seq = 0
    var stop = 0
    var offset = 0
    while (pos < blob.size) {
        seq += varint()
        stop += zigzag()
        offset += zigzag()
        if (broken || pos >= blob.size) return null
        stops += RawTripStop(seq, stop, offset, blob[pos++].toInt() and BYTE_MASK)
    }
    return stops
}

private class TripHead(
    val fullPattern: Int?,
    val route: Int,
    val line: String,
    val type: Int,
    val color: String?,
    val textColor: String?,
    val headsign: String?,
)

private class StopInfo(val name: String, val latitude: Double, val longitude: Double)

// Le fermate del blob con i loro dati ([ids]: gli id di transit.db, separati da virgole, per le query).
private class TripStops(val raw: List<RawTripStop>, val ids: String, val info: Map<Int, StopInfo>)

// La corsa nel feed ([trip], null se non c'e'), il suo trip_id GTFS e gli stop_id GTFS delle fermate (id di transit.db).
private data class LiveTrip(val gtfsTripId: String?, val trip: RealtimeTrip?, val gtfsStops: Map<Int, String> = emptyMap())

// Fuso della rete, minuti di servizio di adesso e minuti tra oggi e il giorno di servizio della corsa.
private class TripClock(val zone: ZoneId, val nowMinute: Int, val dayMinutes: Int)

private const val MINUTES_PER_DAY_TRIP = 1440

/**
 * La vista della corsa [ref] sulla rete, con il tempo reale di [realtime] se la rete ha gli id GTFS e la corsa e' nel
 * feed (per trip_id, o per chiavi nelle reti con trip_keys). Gli orari sono come quelli del tabellone: trip.start piu'
 * l'offset della fermata, in minuti di servizio GTFS. Null se transit.db non ha le fermate delle corse, il formato e'
 * diverso o la corsa non si trova.
 */
internal fun readTripView(db: TransitQuery, ref: TripRef, now: Instant, realtime: RealtimeFeed?): TripView? {
    val meta = readFeedMeta(db)?.takeIf { it.tripStops }
    val head = meta?.let { readTripHead(db, ref) }
    val stops = head?.let { readTripStops(db, it) }
    if (meta == null || head == null || stops == null) return null
    val live = findLiveTrip(db, meta, ref, head.route, realtime).let {
        if (it.trip == null) it else it.copy(gtfsStops = readGtfsStopIds(db, stops.ids))
    }
    return buildTripView(ref, head, stops, live, tripClock(meta.zone, now, ref))
}

private fun readTripHead(db: TransitQuery, ref: TripRef): TripHead? = db.query(
    "SELECT t.full_pattern, t.route, r.short_name, r.long_name, r.type, r.color, r.text_color, h.text " +
        "FROM trip t JOIN route r ON r.id = t.route LEFT JOIN headsign h ON h.id = t.headsign " +
        "WHERE t.pattern = ${ref.pattern} AND t.start = ${ref.start} AND t.id = ${ref.trip}",
) {
    fun text(column: HeadColumn) = it.string(column.ordinal)?.takeIf { value -> value.isNotBlank() }
    TripHead(
        fullPattern = text(HeadColumn.FULL_PATTERN)?.toIntOrNull(),
        route = it.int(HeadColumn.ROUTE.ordinal),
        line = text(HeadColumn.SHORT_NAME) ?: text(HeadColumn.LONG_NAME) ?: "?",
        type = it.int(HeadColumn.TYPE.ordinal),
        color = it.string(HeadColumn.COLOR.ordinal),
        textColor = it.string(HeadColumn.TEXT_COLOR.ordinal),
        headsign = text(HeadColumn.HEADSIGN),
    )
}.firstOrNull()

// Le colonne della SELECT di readTripHead, nello stesso ordine.
private enum class HeadColumn { FULL_PATTERN, ROUTE, SHORT_NAME, LONG_NAME, TYPE, COLOR, TEXT_COLOR, HEADSIGN }

// Le fermate della corsa intera della testata e i dati di ciascuna; null senza corsa intera, blob mancante o vuoto.
private fun readTripStops(db: TransitQuery, head: TripHead): TripStops? {
    val fullPattern = head.fullPattern
    val blob = fullPattern?.let { db.query("SELECT stops FROM trip_stop WHERE pattern = $it") { row -> row.bytes(0) }.firstOrNull() }
    val raw = blob?.let { decodeTripStops(it) }?.takeIf { it.isNotEmpty() } ?: return null
    val ids = raw.map { it.stop }.distinct().joinToString(",")
    val info = db.query("SELECT id, name, latE6, lonE6 FROM stop WHERE id IN ($ids)") {
        it.int(0) to StopInfo(
            it.string(1).orEmpty(),
            (it.string(2)?.toLongOrNull() ?: 0L) / MICRODEGREES,
            (it.string(3)?.toLongOrNull() ?: 0L) / MICRODEGREES,
        )
    }.toMap()
    return TripStops(raw, ids, info)
}

// La corsa nel feed: per trip_id, o per chiavi nelle reti con trip_keys; vuota senza feed, id GTFS o oltre la validita' della rete.
private fun findLiveTrip(db: TransitQuery, meta: FeedMeta, ref: TripRef, route: Int, realtime: RealtimeFeed?): LiveTrip {
    val feed = realtime?.takeIf { meta.gtfsIds && ref.serviceDate <= meta.validUntil } ?: return LiveTrip(null, null)
    val tripId = db.query("SELECT trip_id FROM gtfs_trip WHERE id = ${ref.trip}") { row -> row.string(0) }.firstOrNull()
    val call = RealtimeCall(ref.trip, ref.stop.toInt(), ref.serviceDate, ref.minute, route)
    val trip = tripId?.let { tripOf(RealtimeKey(it, null, ref.serviceDate, 0), feed) }
        ?: if (meta.tripKeys && meta.gtfsRoutes) readTripKeys(db, listOf(call)).keyOf(call)?.let { keyedTrips(feed)[it] } else null
    return LiveTrip(tripId, trip?.takeIf { !it.deleted })
}

private fun readGtfsStopIds(db: TransitQuery, ids: String): Map<Int, String> =
    db.query("SELECT id, stop_id FROM gtfs_stop WHERE id IN ($ids)") { it.int(0) to it.string(1).orEmpty() }.toMap()

private fun tripClock(zone: ZoneId, now: Instant, ref: TripRef): TripClock {
    val today = now.atZone(zone).toLocalDate()
    return TripClock(
        zone = zone,
        nowMinute = Math.floorDiv(now.epochSecond - gtfsEpochSeconds(today, 0, zone), SECONDS_PER_MINUTE_TRIP).toInt(),
        dayMinutes = ChronoUnit.DAYS.between(today, ref.serviceDate).toInt() * MINUTES_PER_DAY_TRIP,
    )
}

private fun buildTripView(ref: TripRef, head: TripHead, stops: TripStops, live: LiveTrip, clock: TripClock): TripView {
    val raw = stops.raw
    val times = raw.map { ref.start + it.offset }
    val boardIndex = raw.indices.firstOrNull { raw[it].stop.toLong() == ref.stop && times[it] == ref.minute } ?: raw.indexOfFirst { it.stop.toLong() == ref.stop }
    val color = parseGtfsColor(head.color)
    return TripView(
        line = head.line,
        mode = transitModeOf(head.type),
        color = color,
        textColor = parseGtfsColor(head.textColor) ?: color?.let(::contrastingTextColor),
        headsign = head.headsign,
        stops = raw.mapIndexed { i, stop ->
            val minute = times[i]
            val gtfsStop = live.gtfsStops[stop.stop]
            val status = live.trip?.let {
                val key = RealtimeKey(live.gtfsTripId.orEmpty(), gtfsStop, ref.serviceDate, gtfsEpochSeconds(ref.serviceDate, minute, clock.zone))
                realtimeOfTrip(key, it.withStopId(stop.seq, gtfsStop))
            }
            val place = stops.info[stop.stop]
            TripStopTime(
                name = place?.name.orEmpty(),
                latitude = place?.latitude ?: 0.0,
                longitude = place?.longitude ?: 0.0,
                minuteOfDay = Math.floorMod(minute, MINUTES_PER_DAY_TRIP),
                inMinutes = clock.dayMinutes + minute - clock.nowMinute + status.delayMinutes,
                pickup = stop.pickup,
                dropOff = stop.dropOff,
                isBoardStop = i == boardIndex,
                realtime = status,
            )
        },
    )
}

private const val MICRODEGREES = 1e6
private const val SECONDS_PER_MINUTE_TRIP = 60L

// Gli StopTimeUpdate senza stop_id che hanno lo stop_sequence [seq] prendono lo stop_id GTFS [stopId] della fermata.
private fun RealtimeTrip.withStopId(seq: Int, stopId: String?): RealtimeTrip =
    if (stopId == null || stops.none { it.stopId == null && it.stopSequence == seq }) {
        this
    } else {
        copy(stops = stops.map { if (it.stopId == null && it.stopSequence == seq) it.copy(stopId = stopId) else it })
    }
