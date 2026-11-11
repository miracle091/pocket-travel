package com.pockettravel.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * Lettore minimo del formato protobuf di GTFS-RT (gtfs-realtime.proto di google/transit, Apache 2.0) per i soli campi
 * che usa il tabellone: FeedHeader.timestamp, TripUpdate (trip, stop_time_update, delay), TripDescriptor (trip_id,
 * route_id, direction_id, start_time, start_date, schedule_relationship), StopTimeUpdate (stop_sequence, stop_id,
 * arrival e departure, schedule_relationship) e Alert (periodi, enti interessati, cause, effect, severity_level, url,
 * titolo e descrizione). Gli altri campi, le estensioni degli enti e le posizioni dei mezzi si saltano. Numeri di campo
 * dal .proto.
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
    val tripsWithoutId = mutableListOf<RealtimeTrip>()
    val alerts = mutableListOf<TransitAlert>()
    val budget = StopBudget()
    ProtoReader(bytes, 0, bytes.size).forEachField { key, reader ->
        when (key) {
            FEED_HEADER -> {
                header = true
                reader.message().forEachField { f, r ->
                    if (f == HEADER_TIMESTAMP) timestamp = r.varint().let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() } else r.skip()
                }
            }
            FEED_ENTITY -> readEntity(reader.message(), budget, alerts)?.let {
                if (it.tripId.isEmpty()) tripsWithoutId += it else trips += it
                require(trips.size + tripsWithoutId.size <= MAX_TRIPS) { "feed GTFS-RT con piu' di $MAX_TRIPS corse" }
            }
            else -> reader.skip()
        }
    }
    require(header) { "feed GTFS-RT senza header" }
    return RealtimeFeed(timestamp, trips.groupBy { it.tripId }, tripsWithoutId, alerts)
}

/** Fermate ancora ammesse in tutto il feed. */
private class StopBudget(var left: Int = MAX_STOPS)

// Gli avvisi dell'ente si aggiungono a [alerts]; oltre MAX_ALERTS si saltano, senza rifiutare il feed.
private fun readEntity(entity: ProtoReader, budget: StopBudget, alerts: MutableList<TransitAlert>): RealtimeTrip? {
    var deleted = false
    var trip: RealtimeTrip? = null
    var alert: TransitAlert? = null
    entity.forEachField { key, reader ->
        when (key) {
            ENTITY_IS_DELETED -> deleted = reader.varint() != 0L
            ENTITY_TRIP_UPDATE -> trip = readTripUpdate(reader.message(), budget)
            ENTITY_ALERT -> alert = readAlert(reader.message())
            else -> reader.skip()
        }
    }
    if (!deleted) alert?.let { if (alerts.size < MAX_ALERTS) alerts += it }
    return trip?.takeIf { !deleted }
}

// Null per un avviso senza titolo ne' descrizione (niente da mostrare).
private fun readAlert(alert: ProtoReader): TransitAlert? {
    val periods = mutableListOf<TransitActivePeriod>()
    val entities = mutableListOf<TransitInformedEntity>()
    var cause: Int? = null
    var effect: Int? = null
    var severity: Int? = null
    var url: TransitText? = null
    var header: TransitText? = null
    var description: TransitText? = null
    alert.forEachField { key, reader ->
        when (key) {
            ALERT_ACTIVE_PERIOD -> periods.addUpTo(readTimeRange(reader.message()), MAX_PERIODS_PER_ALERT)
            ALERT_INFORMED_ENTITY -> entities.addUpTo(readInformedEntity(reader.message()), MAX_INFORMED_ENTITIES)
            ALERT_CAUSE -> cause = reader.varint().toInt()
            ALERT_EFFECT -> effect = reader.varint().toInt()
            ALERT_SEVERITY -> severity = reader.varint().toInt()
            ALERT_URL -> url = readTranslatedText(reader.message())
            ALERT_HEADER -> header = readTranslatedText(reader.message())
            ALERT_DESCRIPTION -> description = readTranslatedText(reader.message())
            else -> reader.skip()
        }
    }
    if (header == null && description == null) return null
    return TransitAlert(periods, entities, cause, effect, severity, url, header, description)
}

private fun readTimeRange(range: ProtoReader): TransitActivePeriod {
    var start: Instant? = null
    var end: Instant? = null
    range.forEachField { key, reader ->
        when (key) {
            RANGE_START -> start = runCatching { Instant.ofEpochSecond(reader.varint()) }.getOrNull()
            RANGE_END -> end = runCatching { Instant.ofEpochSecond(reader.varint()) }.getOrNull()
            else -> reader.skip()
        }
    }
    return TransitActivePeriod(start, end)
}

private fun readInformedEntity(selector: ProtoReader): TransitInformedEntity {
    var result = TransitInformedEntity()
    selector.forEachField { key, reader ->
        result = when (key) {
            SELECTOR_AGENCY_ID -> result.copy(agencyId = reader.string())
            SELECTOR_ROUTE_ID -> result.copy(routeId = reader.string())
            SELECTOR_ROUTE_TYPE -> result.copy(routeType = reader.varint().toInt())
            SELECTOR_STOP_ID -> result.copy(stopId = reader.string())
            SELECTOR_DIRECTION_ID -> result.copy(directionId = reader.varint().toInt())
            SELECTOR_TRIP -> readSelectorTrip(reader.message(), result)
            else -> result.also { reader.skip() }
        }
    }
    return result
}

// I campi del TripDescriptor di un selettore, sopra [selector].
private fun readSelectorTrip(trip: ProtoReader, selector: TransitInformedEntity): TransitInformedEntity {
    var result = selector
    trip.forEachField { f, r ->
        result = when (f) {
            TRIP_ID -> result.copy(tripId = r.string())
            TRIP_START_TIME -> result.copy(tripStartTime = r.string())
            TRIP_START_DATE -> result.copy(tripStartDate = r.string())
            TRIP_ROUTE_ID -> result.copy(tripRouteId = r.string())
            TRIP_DIRECTION_ID -> result.copy(tripDirectionId = r.varint().toInt())
            else -> result.also { r.skip() }
        }
    }
    return result
}

// Traduzioni oltre MAX_TRANSLATIONS o piu' lunghe di MAX_TEXT_LENGTH o vuote si saltano; null se non ne resta nessuna.
private fun readTranslatedText(text: ProtoReader): TransitText? {
    val translations = mutableListOf<TransitTranslation>()
    text.forEachField { key, reader ->
        if (key != TEXT_TRANSLATION) return@forEachField reader.skip()
        var value: String? = null
        var language: String? = null
        reader.message().forEachField { f, r ->
            when (f) {
                TRANSLATION_TEXT -> value = r.string()
                TRANSLATION_LANGUAGE -> language = r.string()
                else -> r.skip()
            }
        }
        val s = value
        if (!s.isNullOrBlank() && s.length <= MAX_TEXT_LENGTH && translations.size < MAX_TRANSLATIONS) {
            translations += TransitTranslation(s, language?.takeIf { it.isNotEmpty() })
        }
    }
    return translations.takeIf { it.isNotEmpty() }?.let { TransitText(it) }
}

// I campi di TripDescriptor che servono; un descrittore ripetuto aggiorna solo i campi che ha.
private class TripDescriptorFields {
    var tripId: String? = null
    var startDate: LocalDate? = null
    var routeId: String? = null
    var directionId: Int? = null
    var startTime: String? = null
    var relationship = 0L

    // Senza trip_id la corsa si tiene solo se la indicano linea, ora e giorno (HSL); se no non si aggancia a nessuna corsa.
    fun keyId(): String? = tripId?.takeIf { it.isNotEmpty() }
        ?: if (routeId.isNullOrEmpty() || startTime.isNullOrEmpty() || startDate == null) null else ""
}

private fun readTripDescriptor(trip: ProtoReader, into: TripDescriptorFields) {
    trip.forEachField { f, r ->
        when (f) {
            TRIP_ID -> into.tripId = r.string()
            TRIP_ROUTE_ID -> into.routeId = r.string()
            TRIP_DIRECTION_ID -> into.directionId = r.varint().toInt()
            TRIP_START_TIME -> into.startTime = r.string()
            TRIP_START_DATE -> into.startDate = runCatching { LocalDate.parse(r.string(), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            TRIP_SCHEDULE_RELATIONSHIP -> into.relationship = r.varint()
            else -> r.skip()
        }
    }
}

private fun readTripUpdate(update: ProtoReader, budget: StopBudget): RealtimeTrip? {
    val trip = TripDescriptorFields()
    var delay: Int? = null
    val stops = mutableListOf<RealtimeStop>()
    update.forEachField { key, reader ->
        when (key) {
            TRIP_UPDATE_TRIP -> readTripDescriptor(reader.message(), trip)
            TRIP_UPDATE_STOP_TIME_UPDATE -> readStopTimeUpdate(reader.message())?.let {
                require(stops.size < MAX_STOPS_PER_TRIP && budget.left-- > 0) { "feed GTFS-RT con troppe fermate" }
                stops += it
            }
            TRIP_UPDATE_DELAY -> delay = reader.varint().toInt()
            else -> reader.skip()
        }
    }
    val id = trip.keyId() ?: return null
    return RealtimeTrip(
        id, trip.startDate, trip.relationship == TRIP_CANCELED, delay, stops, deleted = trip.relationship == TRIP_DELETED,
        routeId = trip.routeId?.takeIf { it.isNotEmpty() }, directionId = trip.directionId,
        startTime = trip.startTime?.takeIf { it.isNotEmpty() },
    )
}

// Null per uno StopTimeUpdate che non dice nulla (ne' fermata, ne' ora o ritardo, ne' NO_DATA): non si alloca.
private fun readStopTimeUpdate(update: ProtoReader): RealtimeStop? {
    var stopId: String? = null
    var stopSequence: Int? = null
    var relationship = 0L
    var arrival: Pair<Int?, Long?>? = null
    var departure: Pair<Int?, Long?>? = null
    update.forEachField { key, reader ->
        when (key) {
            STU_STOP_SEQUENCE -> stopSequence = reader.varint().toInt()
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
        noData = relationship == STU_NO_DATA, fromArrival = departure == null && arrival != null, stopSequence = stopSequence,
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

// Tetti degli avvisi: quello che li supera si salta, senza rifiutare il feed.
private const val MAX_ALERTS = 2_000
private const val MAX_INFORMED_ENTITIES = 500
private const val MAX_PERIODS_PER_ALERT = 50
private const val MAX_TRANSLATIONS = 20
private const val MAX_TEXT_LENGTH = 4_000

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
private const val ENTITY_ALERT = (5 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val ALERT_ACTIVE_PERIOD = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val ALERT_INFORMED_ENTITY = (5 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val ALERT_CAUSE = 6 shl WIRE_TYPE_BITS
private const val ALERT_EFFECT = 7 shl WIRE_TYPE_BITS
private const val ALERT_URL = (8 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val ALERT_HEADER = (10 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val ALERT_DESCRIPTION = (11 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val ALERT_SEVERITY = 14 shl WIRE_TYPE_BITS
private const val RANGE_START = 1 shl WIRE_TYPE_BITS
private const val RANGE_END = 2 shl WIRE_TYPE_BITS
private const val SELECTOR_AGENCY_ID = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val SELECTOR_ROUTE_ID = (2 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val SELECTOR_ROUTE_TYPE = 3 shl WIRE_TYPE_BITS
private const val SELECTOR_TRIP = (4 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val SELECTOR_STOP_ID = (5 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val SELECTOR_DIRECTION_ID = 6 shl WIRE_TYPE_BITS
private const val TEXT_TRANSLATION = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRANSLATION_TEXT = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRANSLATION_LANGUAGE = (2 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_ID = (1 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_START_TIME = (2 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_ROUTE_ID = (5 shl WIRE_TYPE_BITS) or WIRE_LENGTH
private const val TRIP_DIRECTION_ID = 6 shl WIRE_TYPE_BITS
private const val STU_STOP_SEQUENCE = 1 shl WIRE_TYPE_BITS
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
