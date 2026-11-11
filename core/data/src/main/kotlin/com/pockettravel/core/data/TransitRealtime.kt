package com.pockettravel.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Tempo reale dei mezzi pubblici: i TripUpdates di un feed GTFS-RT (scaricato dall'app dall'ente, vedi
 * TransitFeedInfo.realtimeUrl) agganciati alle partenze del tabellone con i trip_id e gli stop_id del GTFS che la
 * pipeline tiene nelle reti con il tempo reale (tabelle gtfs_trip e gtfs_stop di transit.db).
 */

/**
 * I TripUpdates di un feed, per trip_id; [timestamp] e' l'header del feed (null se manca). [tripsWithoutId] sono i
 * TripUpdates senza trip_id (HSL), indicati da [RealtimeTrip.routeId], [RealtimeTrip.startTime] e start_date; [alerts]
 * gli avvisi di servizio dello stesso feed.
 */
data class RealtimeFeed(
    val timestamp: Instant?,
    val trips: Map<String, List<RealtimeTrip>>,
    val tripsWithoutId: List<RealtimeTrip> = emptyList(),
    val alerts: List<TransitAlert> = emptyList(),
)

/**
 * Un TripUpdate: [startDate] e' il giorno di servizio (null se il feed non lo dice: vale per la corsa di oggi);
 * [cancelled] per schedule_relationship CANCELED, [deleted] per DELETED (la corsa non si mostra: GTFS-RT chiede di
 * non presentarla come soppressa); [delaySeconds] e' trip_update.delay. [routeId], [directionId] e [startTime]
 * ("HH:MM:SS" dell'orario di partenza della corsa, anche oltre le 24:00) sono i campi di TripDescriptor che
 * identificano la corsa senza trip_id; in quel caso [tripId] e' vuoto.
 */
data class RealtimeTrip(
    val tripId: String,
    val startDate: LocalDate?,
    val cancelled: Boolean,
    val delaySeconds: Int?,
    val stops: List<RealtimeStop>,
    val deleted: Boolean = false,
    val routeId: String? = null,
    val directionId: Int? = null,
    val startTime: String? = null,
)

/**
 * Uno StopTimeUpdate: [skipped] per schedule_relationship SKIPPED, [noData] per NO_DATA (nessuna previsione per la
 * fermata e per le successive senza un aggiornamento: Autolinee Toscane lo usa per le ultime); ritardo e ora prevista
 * (secondi Unix) della partenza, o dell'arrivo se la partenza manca ([fromArrival]: l'ora prevista e' quella d'arrivo, e
 * da sola non dice il ritardo della partenza). [stopSequence] e' stop_sequence, per agganciare la fermata quando manca lo
 * stop_id.
 */
data class RealtimeStop(
    val stopId: String?,
    val skipped: Boolean,
    val delaySeconds: Int?,
    val timeEpochSeconds: Long?,
    val noData: Boolean = false,
    val fromArrival: Boolean = false,
    val stopSequence: Int? = null,
)

/** Il tempo reale di una partenza: ritardo in minuti (negativo se in anticipo, 0 in orario) o corsa soppressa. */
sealed interface DepartureRealtime {
    data class Delay(val minutes: Int) : DepartureRealtime

    data object Cancelled : DepartureRealtime
}

/**
 * Quello che serve per agganciare una partenza al feed: id GTFS di corsa e fermata, giorno di servizio e ora
 * programmata (secondi Unix) alla fermata.
 */
internal data class RealtimeKey(val tripId: String, val stopId: String?, val serviceDate: LocalDate, val scheduledEpochSeconds: Long)

/**
 * Secondi Unix dell'ora GTFS [minute] (minuti dal "mezzogiorno meno 12 ore" del giorno di servizio, anche oltre 1440)
 * del giorno [serviceDate] nel fuso [zone].
 */
internal fun gtfsEpochSeconds(serviceDate: LocalDate, minute: Int, zone: ZoneId): Long =
    serviceDate.atTime(NOON, 0).atZone(zone).minusHours(NOON.toLong()).toEpochSecond() + minute * SECONDS_PER_MINUTE

/**
 * Il tempo reale della partenza [key] nel feed, come dice GTFS-RT: la corsa del giorno (start_date, o l'unica senza
 * data); soppressa se la corsa e' CANCELED o la fermata SKIPPED, nessuno se la fermata e' NO_DATA; il ritardo dello StopTimeUpdate della fermata, se no
 * quello dell'ultimo prima della fermata (in ordine di ora programmata: il ritardo si propaga in avanti), se no
 * trip_update.delay. Null se il feed non ha la corsa, o la ha solo con fermate dopo questa (la corsa e' gia' passata).
 */
// La corsa del giorno: con start_date uguale, o l'unica senza data.
internal fun tripOf(key: RealtimeKey, feed: RealtimeFeed): RealtimeTrip? = feed.trips[key.tripId]?.let { candidates ->
    candidates.firstOrNull { it.startDate == key.serviceDate } ?: candidates.singleOrNull { it.startDate == null }
}

internal fun realtimeOf(key: RealtimeKey, feed: RealtimeFeed): DepartureRealtime? = realtimeOfTrip(key, tripOf(key, feed))

/** Come [realtimeOf], con la corsa [trip] del feed gia' trovata (per trip_id o per chiavi); null senza corsa. */
internal fun realtimeOfTrip(key: RealtimeKey, trip: RealtimeTrip?): DepartureRealtime? {
    if (trip == null) return null
    val atStop = key.stopId?.let { id -> stopPassage(trip, id, key.scheduledEpochSeconds) }
    return when {
        trip.cancelled || atStop?.skipped == true -> DepartureRealtime.Cancelled
        atStop?.noData == true -> null
        else -> (atStop?.let { stopDelaySeconds(it, key.scheduledEpochSeconds) } ?: propagatedDelaySeconds(trip, key.scheduledEpochSeconds))
            ?.let(::delayOf)
    }
}

// Lo StopTimeUpdate della fermata [stopId]; se la corsa la tocca due volte (linee circolari) quello con l'ora programmata
// (ora prevista meno ritardo, o l'ora prevista) piu' vicina a [scheduled]; senza ore, il primo.
private fun stopPassage(trip: RealtimeTrip, stopId: String, scheduled: Long): RealtimeStop? =
    trip.stops.filter { it.stopId == stopId }.minByOrNull { stop ->
        stop.timeEpochSeconds?.let { abs(it - (stop.delaySeconds ?: 0) - scheduled) } ?: Long.MAX_VALUE
    }

// Senza la fermata: il ritardo dell'ultimo StopTimeUpdate prima di [scheduled]; nessuno se ci sono solo quelli dopo, o se
// dopo quello c'e' un NO_DATA (vale anche per le fermate seguenti; senza ora non si sa se viene prima di questa: nel
// dubbio niente ritardo); se nessuno si puo' ordinare, trip_update.delay.
private fun propagatedDelaySeconds(trip: RealtimeTrip, scheduled: Long): Int? {
    // Ora programmata di ogni StopTimeUpdate (ora prevista meno ritardo): solo quelli che hanno entrambi si ordinano.
    val timed = trip.stops.mapIndexedNotNull { index, stop ->
        val delay = stop.delaySeconds
        val time = stop.timeEpochSeconds
        if (delay == null || time == null || stop.skipped) null else Triple(time - delay, delay, index)
    }
    val before = timed.filter { it.first <= scheduled }.maxByOrNull { it.first }
    return when {
        before != null -> before.second.takeIf { trip.stops.drop(before.third + 1).none { it.noData } }
        timed.isNotEmpty() -> null
        else -> trip.delaySeconds
    }
}

/** Corsa e fermata (id di transit.db), giorno e minuto di servizio GTFS di una partenza, per il tempo reale. */
internal data class RealtimeCall(val trip: Int, val stop: Int, val serviceDate: LocalDate, val serviceMinute: Int, val route: Int = 0)

/**
 * Le partenze [found] con il tempo reale di [feed]: [calls] da' per ciascuna corsa, fermata e giorno di servizio,
 * gtfs_trip e gtfs_stop di transit.db gli id GTFS. Le partenze stimate (rete scaduta da poco) restano senza; quelle
 * delle corse DELETED si tolgono.
 */
internal fun applyRealtime(
    db: TransitQuery,
    found: List<TransitDeparture>,
    calls: List<RealtimeCall>,
    feed: RealtimeFeed,
    zone: ZoneId,
    tripKeys: Boolean = false,
): List<TransitDeparture> = applyRealtimeIndexed(db, found, calls, feed, zone, tripKeys).map { it.second }

/** Come [applyRealtime], con l'indice in [found] di ogni partenza rimasta. */
internal fun applyRealtimeIndexed(
    db: TransitQuery,
    found: List<TransitDeparture>,
    calls: List<RealtimeCall>,
    feed: RealtimeFeed,
    zone: ZoneId,
    // La transit.db ha le chiavi delle corse (meta trip_keys e gtfs_routes): le corse senza trip_id si agganciano con quelle.
    tripKeys: Boolean = false,
): List<Pair<Int, TransitDeparture>> {
    if (found.isEmpty()) return emptyList()
    fun names(table: String, column: String, ids: Collection<Int>): Map<Int, String> =
        db.query("SELECT id, $column FROM $table WHERE id IN (${ids.distinct().joinToString(",")})") { it.int(0) to it.string(1).orEmpty() }.toMap()
    val tripIds = names("gtfs_trip", "trip_id", calls.map { it.trip })
    val stopIds = names("gtfs_stop", "stop_id", calls.map { it.stop })
    val keyed = if (tripKeys) keyedTrips(feed) else emptyMap()
    val keys = if (keyed.isNotEmpty()) readTripKeys(db, calls) else null
    return found.mapIndexedNotNull { i, departure ->
        val call = calls[i]
        val tripId = tripIds[call.trip]
        if (departure.estimated || tripId == null) return@mapIndexedNotNull i to departure
        val key = RealtimeKey(tripId, stopIds[call.stop], call.serviceDate, gtfsEpochSeconds(call.serviceDate, call.serviceMinute, zone))
        val trip = tripOf(key, feed) ?: keys?.keyOf(call)?.let { keyed[it] }
        if (trip?.deleted == true) return@mapIndexedNotNull null
        val status = realtimeOfTrip(key, trip)
        i to departure.copy(realtime = status, inMinutes = departure.inMinutes + status.delayMinutes)
    }
}

// Ritardo di uno StopTimeUpdate della fermata stessa: quello dichiarato, o l'ora prevista di partenza meno quella
// programmata, per difetto al minuto (in transit.db l'orario ha solo i minuti: 10:05:40 per le 10:05 e' in orario).
private fun stopDelaySeconds(stop: RealtimeStop, scheduled: Long): Int? =
    stop.delaySeconds ?: stop.timeEpochSeconds?.takeIf { !stop.fromArrival }?.let { time ->
        (Math.floorDiv(time - scheduled, SECONDS_PER_MINUTE.toLong()) * SECONDS_PER_MINUTE).coerceIn(-MAX_DELAY_SECONDS, MAX_DELAY_SECONDS).toInt()
    }

// Un ritardo oltre un giorno e' un errore del feed: niente tempo reale per quella partenza.
private fun delayOf(seconds: Int) =
    DepartureRealtime.Delay((seconds / SECONDS_PER_MINUTE.toDouble()).roundToInt()).takeIf { abs(seconds) < MAX_DELAY_SECONDS }

/** Minuti da aggiungere all'ora programmata: il ritardo, 0 per una corsa soppressa o senza tempo reale. */
val DepartureRealtime?.delayMinutes: Int get() = (this as? DepartureRealtime.Delay)?.minutes ?: 0

private const val NOON = 12
private const val SECONDS_PER_MINUTE = 60
private const val MAX_DELAY_SECONDS = 86_400L
