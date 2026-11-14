package com.pockettravel.feature.map

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.DepartureRealtime
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TripRef
import com.pockettravel.core.data.TripStopTime
import com.pockettravel.core.data.TripView
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import java.util.Date
import java.util.TimeZone
import com.pockettravel.core.ui.R as UiR

/**
 * La vista della corsa di una partenza: le fermate in ordine fino al capolinea, con l'ora programmata, il ritardo se
 * c'e' il tempo reale e la fermata del tabellone in evidenza. Con [onReachStop] "Raggiungi la fermata" porta a piedi alla
 * fermata per prendere questa corsa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripSheet(trip: TripUi, onDismiss: () -> Unit, onReachStop: ((TripToCatch) -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val view = trip.view
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            when {
                trip.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    PocketTravelLoadingIndicator()
                    Text(stringResource(R.string.trip_loading), style = MaterialTheme.typography.bodyMedium)
                }
                view == null -> Text(stringResource(R.string.trip_not_found), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    val toCatch = trip.toCatch
                    if (toCatch != null && onReachStop != null) {
                        Button(onClick = { onReachStop(toCatch) }) {
                            Icon(ImageVector.vectorResource(UiR.drawable.ms_directions_walk), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(Spacing.s))
                            Text(stringResource(R.string.trip_reach_stop))
                        }
                    }
                    TripStops(view)
                }
            }
        }
    }
}

@Composable
private fun TripStops(view: TripView) {
    val mode = stringResource(view.mode.label())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        modifier = Modifier.semantics(mergeDescendants = true) { heading() },
    ) {
        LineBadge(view.line, view.color, view.textColor)
        Column {
            Text(text = view.headsign ?: mode, style = MaterialTheme.typography.titleMedium)
            if (view.headsign != null) Text(text = mode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val clockOf = networkClock()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        itemsIndexed(view.stops) { index, stop -> TripStopRow(stop, clockOf(stop.minuteOfDay), terminus = index == 0 || index == view.stops.lastIndex) }
    }
}

@Composable
private fun TripStopRow(stop: TripStopTime, clock: String, terminus: Boolean) {
    val cancelled = stop.realtime == DepartureRealtime.Cancelled
    val delay = (stop.realtime as? DepartureRealtime.Delay)?.minutes ?: 0
    val (delayText, delaySpoken) = realtimeTexts(cancelled, delay)
    val note = boardingNote(stop, terminus)
    val here = if (stop.isBoardStop) stringResource(R.string.trip_board_stop) else null
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(clock, stop.name, here, delaySpoken, note).joinToString(", ")
        },
    ) {
        Column(modifier = Modifier.widthIn(min = 64.dp)) {
            Text(
                text = clock,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (stop.isBoardStop) FontWeight.Bold else null,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            if (delayText != null) {
                Text(
                    text = delayText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (delay < 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stop.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (stop.isBoardStop) FontWeight.Bold else null,
                color = if (stop.isBoardStop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            listOfNotNull(here, note).forEach {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Soppressione, ritardo o anticipo: il testo a vista e quello per TalkBack; null in orario o senza tempo reale.
@Composable
private fun realtimeTexts(cancelled: Boolean, delay: Int): Pair<String?, String?> = when {
    cancelled -> stringResource(R.string.transit_cancelled).let { it to it }
    delay > 0 -> stringResource(R.string.transit_delay, delay) to pluralStringResource(R.plurals.transit_delay_spoken, delay, delay)
    delay < 0 -> stringResource(R.string.transit_early, -delay) to pluralStringResource(R.plurals.transit_early_spoken, -delay, -delay)
    else -> null to null
}

// Solo salita o solo discesa; ai capolinea (dove si sale soltanto o si scende soltanto) non si dice.
@Composable
private fun boardingNote(stop: TripStopTime, terminus: Boolean): String? = when {
    terminus -> null
    !stop.pickup && stop.dropOff -> stringResource(R.string.trip_drop_off_only)
    !stop.dropOff && stop.pickup -> stringResource(R.string.trip_pickup_only)
    else -> null
}

/**
 * L'ora sull'orologio della rete nel formato del dispositivo (24 h o AM/PM): il formatter va in UTC perche' il minuto e'
 * gia' un orario locale della rete; GTFS supera le 24 h dopo mezzanotte.
 */
@Composable
internal fun networkClock(): (Int) -> String {
    val context = LocalContext.current
    return remember(context) {
        val format = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val clock: (Int) -> String = { minute -> format.format(Date(Math.floorMod(minute, MINUTES_PER_DAY) * MILLIS_PER_MINUTE)) }
        clock
    }
}

// "Partenze successive:" con un pulsante per orario, che apre la corsa di quella partenza.
@Composable
internal fun LaterTripButtons(later: List<TransitDeparture>, clockOf: (Int) -> String, labels: LaterLabels, onOpenTrip: (TripRef) -> Unit) {
    FlowRow(verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.transit_later_trips),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        later.forEach { item ->
            val texts = laterTexts(listOf(item), clockOf, labels) ?: return@forEach
            val spoken = stringResource(R.string.transit_open_trip_at, texts.spoken)
            TextButton(
                onClick = { item.trip?.let(onOpenTrip) },
                modifier = Modifier.semantics { contentDescription = spoken },
            ) { Text(texts.visible) }
        }
    }
}

private const val MINUTES_PER_DAY = 24 * 60
private const val MILLIS_PER_MINUTE = 60_000L
