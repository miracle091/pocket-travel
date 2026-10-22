package com.pockettravel.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * Lettore minimo del formato protobuf di GTFS-RT (gtfs-realtime.proto di google/transit, Apache 2.0) per i soli campi
 * che usa il tabellone: FeedHeader.timestamp, TripUpdate (trip, stop_time_update, delay), TripDescriptor (trip_id,
 * start_date, schedule_relationship), StopTimeUpdate (stop_id, arrival e departure, schedule_relationship). Gli altri
 * campi, le estensioni degli enti e le posizioni dei mezzi si saltano. Numeri di campo dal .proto.
 */

/**
 * Legge un FeedMessage GTFS-RT; lancia [IllegalArgumentException] se i byte non sono un protobuf valido o manca l'header,
 * obbligatorio in GTFS-RT: Autolinee Toscane a volte risponde 200 con un corpo vuoto, che non e' un feed senza ritardi.
 * Lancia [IllegalArgumentException] anche oltre [MAX_TRIPS] corse, [MAX_STOPS_PER_TRIP] fermate per corsa o
 * [MAX_STOPS] fermate in tutto: un feed ostile, di pochi KB compressi, non riempie la memoria di oggetti.
 */
fun decodeTripUpdates(bytes: ByteArray): RealtimeFeed {
    var timestamp: Instant? = null
    var header = false
    val trips = mutableListOf<RealtimeTrip>()
    val budget = StopBudget()
    ProtoReader(bytes, 0, bytes.size).forEachField { key, reader ->
        when (key) {
            FEED_HEADER -> {
                header = true
                reader.message().forEachField { f, r ->
                    if (f == HEADER_TIMESTAMP) timestamp = r.varint().let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() } else r.skip()
                }
            }
            FEED_ENTITY -> readEntity(reader.message(), budget)?.let {
                trips += it
                require(trips.size <= MAX_TRIPS) { "feed GTFS-RT con piu' di $MAX_TRIPS corse" }
            }
            else -> reader.skip()
        }
    }
    require(header) { "feed GTFS-RT senza header" }
    return RealtimeFeed(timestamp, trips.groupBy { it.tripId })
}

/** Fermate ancora ammesse in tutto il feed. */
private class StopBudget(var left: Int = MAX_STOPS)

private fun readEntity(entity: ProtoReader, budget: StopBudget): RealtimeTrip? {
    var deleted = false
    var trip: RealtimeTrip? = null
    entity.forEachField { key, reader ->
        when (key) {
            ENTITY_IS_DELETED -> deleted = reader.varint() != 0L
            ENTITY_TRIP_UPDATE -> trip = readTripUpdate(reader.message(), budget)
            else -> reader.skip()
        }
    }
    return trip?.takeIf { !deleted }
}

private fun readTripUpdate(update: ProtoReader, budget: StopBudget): RealtimeTrip? {
    var tripId: String? = null
    var startDate: LocalDate? = null
    var relationship = 0L
    var delay: Int? = null
    val stops = mutableListOf<RealtimeStop>()
    update.forEachField { key, reader ->
        when (key) {
            TRIP_UPDATE_TRIP -> reader.message().forEachField { f, r ->
                when (f) {
                    TRIP_ID -> tripId = r.string()
                    TRIP_START_DATE -> startDate = runCatching { LocalDate.parse(r.string(), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                    TRIP_SCHEDULE_RELATIONSHIP -> relationship = r.varint()
                    else -> r.skip()
                }
            }
            TRIP_UPDATE_STOP_TIME_UPDATE -> readStopTimeUpdate(reader.message())?.let {
                require(stops.size < MAX_STOPS_PER_TRIP && budget.left-- > 0) { "feed GTFS-RT con troppe fermate" }
                stops += it
            }
            TRIP_UPDATE_DELAY -> delay = reader.varint().toInt()
            else -> reader.skip()
        }
    }
    // Senza trip_id (corse indicate solo da linea e ora) non c'e' modo di agganciarla a una corsa del tabellone.
    val id = tripId?.takeIf { it.isNotEmpty() } ?: return null
    return RealtimeTrip(id, startDate, relationship == TRIP_CANCELED, delay, stops, deleted = relationship == TRIP_DELETED)
}

// Null per uno StopTimeUpdate che non dice nulla (ne' fermata, ne' ora o ritardo, ne' NO_DATA): non si alloca.
private fun readStopTimeUpdate(update: ProtoReader): RealtimeStop? {
    var stopId: String? = null
    var relationship = 0L
    var arrival: Pair<Int?, Long?>? = null
    var departure: Pair<Int?, Long?>? = null
    update.forEachField { key, reader ->
        when (key) {
            STU_ARRIVAL -> arrival = readStopTimeEvent(reader.message())
            STU_DEPARTURE -> departure = readStopTimeEvent(reader.message())
            STU_STOP_ID -> stopId = reader.string()
            STU_SCHEDULE_RELATIONSHIP -> relationship = reader.varint()
            else -> reader.skip()
        }
    }
    // La partenza conta per chi sale; l'arrivo se la partenza manca (Renfe e Autolinee Toscane danno solo quello).
    val event = departure ?: arrival
    if (stopId == null && event == null && relationship != STU_NO_DATA) return null
    return RealtimeStop(
        stopId, relationship == STU_SKIPPED, event?.first, event?.second,
        noData = relationship == STU_NO_DATA, fromArrival = departure == null && arrival != null,
    )
}

private fun readStopTimeEvent(event: ProtoReader): Pair<Int?, Long?> {
    var delay: Int? = null
    var time: Long? = null
    event.forEachField { key, reader ->
        when (key) {
            EVENT_DELAY -> delay = reader.varint().toInt()
            EVENT_TIME -> time = reader.varint()
            else -> reader.skip()
        }
    }
    return delay to time
}

/** Lettore del wire format protobuf su [bytes] tra [start] e [end]: varint, 64 bit, lunghezza, 32 bit. */
internal class ProtoReader(private val bytes: ByteArray, start: Int, private val end: Int) {
    private var pos = start
    private var wireType = 0

    /**
     * Chiama [onField] per ogni campo con la sua chiave (numero di campo e tipo, come le costanti sotto: un campo noto
     * con un tipo inatteso non combacia e si salta, come fa protobuf); [onField] deve leggere o saltare il valore.
     */
    fun forEachField(onField: (key: Int, reader: ProtoReader) -> Unit) {
        while (pos < end) {
            val key = varint()
            wireType = (key and WIRE_TYPE_MASK).toInt()
            val field = key ushr WIRE_TYPE_BITS
            require(field in 1..MAX_FIELD) { "campo protobuf non valido" }
            val before = pos
            onField(key.toInt(), this)
            // Ogni valore occupa almeno un byte (anche una stringa vuota ha la lunghezza): un campo non letto ne' saltato
            // farebbe leggere il valore come chiave del campo dopo.
            require(pos > before) { "campo protobuf $field non letto" }
        }
        require(pos == end) { "messaggio protobuf troncato" }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end && shift < Long.SIZE_BITS) { "varint protobuf non valido" }
            val b = bytes[pos++].toInt()
            result = result or ((b and VARINT_PAYLOAD).toLong() shl shift)
            if (b and VARINT_CONTINUE == 0) return result
            shift += VARINT_BITS
        }
    }

    private fun length(): Int {
        require(wireType == WIRE_LENGTH) { "atteso un campo con lunghezza" }
        val length = varint()
        require(length in 0..(end - pos).toLong()) { "lunghezza protobuf oltre il messaggio" }
        return length.toInt()
    }

    fun message(): ProtoReader {
        val length = length()
        return ProtoReader(bytes, pos, pos + length).also { pos += length }
    }

    fun string(): String {
        val length = length()
        return String(bytes, pos, length, Charsets.UTF_8).also { pos += length }
    }

    fun skip() {
        when (wireType) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> advance(FIXED64_BYTES)
            WIRE_LENGTH -> advance(length())
            WIRE_FIXED32 -> advance(FIXED32_BYTES)
            WIRE_START_GROUP -> skipGroup()
            else -> throw IllegalArgumentException("tipo di campo protobuf $wireType non gestito")
        }
    }

    // Gruppi (proto2, deprecati, assenti in gtfs-realtime.proto ma possibili nelle estensioni di un ente): si saltano fino
    // alla chiusura, senza ricorsione e con un limite di annidamento.
    private fun skipGroup() {
        var open = 1
        while (open > 0) {
            val key = varint()
            require(key ushr WIRE_TYPE_BITS in 1..MAX_FIELD) { "campo protobuf non valido" }
            wireType = (key and WIRE_TYPE_MASK).toInt()
            when (wireType) {
                WIRE_START_GROUP -> require(++open <= MAX_GROUP_DEPTH) { "gruppi protobuf annidati oltre $MAX_GROUP_DEPTH" }
                WIRE_END_GROUP -> open--
                else -> skip()
            }
        }
    }

    private fun advance(count: Int) {
        require(count <= end - pos) { "campo protobuf oltre il messaggio" }
        pos += count
    }
}

// Tetti contro i feed ostili (Berlino, il piu' grande in catalogo, il 22 ottobre 2026 alle 16:45: 9,9 MB, 9.486 corse,
// 195.681 fermate, al massimo 98 fermate per corsa nei feed in catalogo).
private const val MAX_TRIPS = 200_000
private const val MAX_STOPS_PER_TRIP = 1_000
private const val MAX_STOPS = 1_000_000

// Wire format.
private const val WIRE_TYPE_BITS = 3
private const val WIRE_TYPE_MASK = 0x7L
private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH = 2
private const val WIRE_START_GROUP = 3
private const val WIRE_END_GROUP = 4
private const val WIRE_FIXED32 = 5
private const val MAX_FIELD = (1L shl 29) - 1
private const val MAX_GROUP_DEPTH = 32
private const val FIXED64_BYTES = 8
private const val FIXED32_BYTES = 4
private const val VARINT_PAYLOAD = 0x7F
private const val VARINT_CONTINUE = 0x80
private const val VARINT_BITS = 7

// Chiavi dei campi di gtfs-realtime.proto: numero di campo e tipo (lunghezza per messaggi e stringhe, varint per numeri ed enum).
private const val FEED_HEADER = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val FEED_ENTITY = (2 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val HEADER_TIMESTAMP = 3 shl WIRE_TYPE_BITS
private const val ENTITY_IS_DELETED = 2 shl WIRE_TYPE_BITS
private const val ENTITY_TRIP_UPDATE = (3 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_UPDATE_TRIP = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_UPDATE_STOP_TIME_UPDATE = (2 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_UPDATE_DELAY = 5 shl WIRE_TYPE_BITS
private const val TRIP_ID = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_START_DATE = (3 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_SCHEDULE_RELATIONSHIP = 4 shl WIRE_TYPE_BITS
private const val STU_ARRIVAL = (2 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val STU_DEPARTURE = (3 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val STU_STOP_ID = (4 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val STU_SCHEDULE_RELATIONSHIP = 5 shl WIRE_TYPE_BITS
private const val EVENT_DELAY = 1 shl WIRE_TYPE_BITS
private const val EVENT_TIME = 2 shl WIRE_TYPE_BITS

// Valori di schedule_relationship: TripDescriptor CANCELED e DELETED, StopTimeUpdate SKIPPED e NO_DATA.
private const val TRIP_CANCELED = 3L
private const val TRIP_DELETED = 7L
private const val STU_SKIPPED = 1L
private const val STU_NO_DATA = 2L
