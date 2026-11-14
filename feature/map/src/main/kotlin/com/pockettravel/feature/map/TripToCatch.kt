package com.pockettravel.feature.map

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.pockettravel.core.data.DepartureRealtime
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TripRef
import com.pockettravel.core.data.TripView
import com.pockettravel.core.ui.Spacing
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * "Raggiungi la fermata": la corsa da prendere alla fine della guida a piedi. [departures]: la corsa scelta e le successive
 * della stessa linea e direzione dalla stessa fermata, in ordine, all'ora del telefono; quando la prima non si prende piu'
 * il Navigatore passa alla seguente.
 */
data class TripToCatch(
    val stopName: String,
    val latitude: Double,
    val longitude: Double,
    val line: String,
    val color: Int?,
    val textColor: Int?,
    val headsign: String?,
    val departures: List<LocalDateTime>,
) {
    companion object {
        /** Una riga di testo per salvarla con la navigazione attiva: campi separati da tabulazioni, partenze in minuti dall'epoca. */
        fun encode(trip: TripToCatch): String = listOf(
            trip.stopName.clean(), trip.latitude, trip.longitude, trip.line.clean(), trip.color ?: "", trip.textColor ?: "", trip.headsign?.clean() ?: "",
            trip.departures.joinToString(",") { "${it.toEpochSecond(ZoneOffset.UTC) / SECONDS_PER_MINUTE}" },
        ).joinToString("\t")

        /** null se [text] manca o non si legge. */
        fun decode(text: String?): TripToCatch? {
            val fields = text?.split('\t')?.takeIf { it.size == FIELD_COUNT } ?: return null
            val departures = fields[DEPARTURES].split(',').mapNotNull { it.toLongOrNull() }
                .map { LocalDateTime.ofEpochSecond(it * SECONDS_PER_MINUTE, 0, ZoneOffset.UTC) }
            val latitude = fields[1].toDoubleOrNull()
            val longitude = fields[2].toDoubleOrNull()
            return if (latitude == null || longitude == null || departures.isEmpty()) {
                null
            } else {
                TripToCatch(
                    fields[0], latitude, longitude, fields[LINE], fields[COLOR].toIntOrNull(), fields[TEXT_COLOR].toIntOrNull(),
                    fields[HEADSIGN].ifEmpty { null }, departures,
                )
            }
        }

        private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

        private const val SECONDS_PER_MINUTE = 60
        private const val FIELD_COUNT = 8
        private const val LINE = 3
        private const val COLOR = 4
        private const val TEXT_COLOR = 5
        private const val HEADSIGN = 6
        private const val DEPARTURES = 7
    }
}

/**
 * La corsa [ref] aperta nella vista della corsa, con le partenze successive della sua linea dal tabellone [items]; null se
 * la fermata del tabellone non e' nella corsa o la partenza non e' piu' nel tabellone.
 */
internal fun tripToCatch(view: TripView, ref: TripRef, items: List<TransitDeparture>, now: LocalDateTime): TripToCatch? {
    val stop = view.stops.firstOrNull { it.isBoardStop }
    val group = items.firstOrNull { head -> head.trip == ref || head.later.any { it.trip == ref } }
    val departures = group?.let { listOf(it) + it.later }.orEmpty().dropWhile { it.trip != ref }
    return stop?.let { tripToCatch(departures, view.headsign, it.name, it.latitude, it.longitude, now) }
}

/** La partenza [departure] di una riga del tabellone e le sue successive, alla fermata [stopName] della mappa. */
internal fun tripToCatch(departure: TransitDeparture, stopName: String, latitude: Double, longitude: Double, now: LocalDateTime): TripToCatch? =
    tripToCatch(listOf(departure) + departure.later, departure.headsign, stopName, latitude, longitude, now)

// Le soppresse si saltano; l'ora e' quella del telefono piu' i minuti che mancano (con il ritardo del tempo reale).
private fun tripToCatch(
    departures: List<TransitDeparture>,
    headsign: String?,
    stopName: String,
    latitude: Double,
    longitude: Double,
    now: LocalDateTime,
): TripToCatch? {
    val first = departures.firstOrNull() ?: return null
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val times = departures.filter { it.realtime != DepartureRealtime.Cancelled }.map { minute.plusMinutes(it.inMinutes.toLong()) }
    return times.takeIf { it.isNotEmpty() }?.let { TripToCatch(stopName, latitude, longitude, first.line, first.color, first.textColor, headsign, it) }
}

/**
 * La partenza da prendere al posto di [current] con [remainingSeconds] di cammino ancora: la prima successiva che si fa
 * in tempo a prendere; null senza [departures], se [current] si prende ancora o se nessuna successiva si prende.
 */
internal fun nextDeparture(departures: List<LocalDateTime>?, current: LocalDateTime, remainingSeconds: Double, now: LocalDateTime): LocalDateTime? =
    if (departures == null || minutesLate(current, remainingSeconds, now) <= 0) {
        null
    } else {
        departures.firstOrNull { it > current && minutesLate(it, remainingSeconds, now) <= 0 }
    }

/** La linea, la direzione e l'ora della corsa da prendere, nel pianificatore e nel Navigatore. */
@Composable
internal fun TripToCatchLine(trip: TripToCatch, departure: LocalDateTime) {
    val context = LocalContext.current
    val time = remember(departure) { DateFormat.getTimeFormat(context).format(Date.from(departure.atZone(ZoneId.systemDefault()).toInstant())) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
        LineBadge(trip.line, trip.color, trip.textColor)
        Spacer(modifier = Modifier.width(Spacing.s))
        Text(
            text = trip.headsign?.let { stringResource(R.string.trip_to_catch_headsign, it, time) } ?: stringResource(R.string.trip_to_catch, time),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
