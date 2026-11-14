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
)

/**
 * La corsa [ref] aperta nella vista della corsa, con le partenze successive della sua linea dal tabellone [items]; null se
 * la fermata del tabellone non e' nella corsa o la partenza non e' piu' nel tabellone. Le soppresse si saltano.
 */
internal fun tripToCatch(view: TripView, ref: TripRef, items: List<TransitDeparture>, now: LocalDateTime): TripToCatch? {
    val stop = view.stops.firstOrNull { it.isBoardStop }
    val group = items.firstOrNull { head -> head.trip == ref || head.later.any { it.trip == ref } }
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val departures = group?.let { listOf(it) + it.later }.orEmpty().dropWhile { it.trip != ref }
        .filter { it.realtime != DepartureRealtime.Cancelled }
        .map { minute.plusMinutes(it.inMinutes.toLong()) }
    return if (stop == null || departures.isEmpty()) {
        null
    } else {
        TripToCatch(stop.name, stop.latitude, stop.longitude, view.line, view.color, view.textColor, view.headsign, departures)
    }
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
