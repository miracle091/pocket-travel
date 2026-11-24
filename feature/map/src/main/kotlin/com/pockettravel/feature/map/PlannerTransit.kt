package com.pockettravel.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.JourneyLeg
import com.pockettravel.core.data.TransitJourney
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.ui.Spacing
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.pockettravel.core.ui.R as UiR

/**
 * Anteprima del modo «Mezzi»: un riquadro per itinerario, dal meno cambi al piu' veloce, con orari, durata, linee e le
 * tratte. Solo da leggere: la guida passo passo non c'e' per i mezzi pubblici.
 */
@Composable
internal fun TransitJourneys(journeys: List<TransitJourney>) {
    if (journeys.isEmpty()) {
        Text(
            stringResource(R.string.planner_transit_none),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalLocale.current.platformLocale).withZone(ZoneId.systemDefault())
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(top = Spacing.s)) {
        journeys.forEach { JourneyCard(it, clock) }
    }
}

@Composable
private fun JourneyCard(journey: TransitJourney, clock: DateTimeFormatter) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${clock.format(journey.departure)} – ${clock.format(journey.arrival)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(minutesText(journey.departure, journey.arrival), style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                journey.legs.filterIsInstance<JourneyLeg.Ride>().forEach { LineBadge(it.line, it.color, it.textColor) }
            }
            Text(
                if (journey.transfers == 0) {
                    stringResource(R.string.planner_transit_direct)
                } else {
                    pluralStringResource(R.plurals.planner_transit_transfers, journey.transfers, journey.transfers)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            journey.legs.forEach { LegRow(it, clock) }
        }
    }
}

@Composable
private fun LegRow(leg: JourneyLeg, clock: DateTimeFormatter) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        val icon = when {
            leg !is JourneyLeg.Ride -> UiR.drawable.ms_directions_walk
            leg.mode in RAIL_MODES -> UiR.drawable.ms_train
            else -> UiR.drawable.ms_directions_bus
        }
        Icon(ImageVector.vectorResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        val text = when (leg) {
            is JourneyLeg.Walk -> {
                val minutes = minutes(leg.departure, leg.arrival)
                leg.toName?.let { stringResource(R.string.planner_transit_walk_to, minutes, it) }
                    ?: stringResource(R.string.planner_transit_walk_arrival, minutes)
            }
            is JourneyLeg.Ride -> {
                val towards = leg.headsign?.let { stringResource(R.string.planner_transit_towards, leg.line, it) } ?: leg.line
                val stops = pluralStringResource(R.plurals.planner_transit_stops, leg.stops, leg.stops)
                stringResource(
                    R.string.planner_transit_ride,
                    towards,
                    leg.fromName.orEmpty(),
                    clock.format(leg.departure),
                    leg.toName.orEmpty(),
                    clock.format(leg.arrival),
                    stops,
                )
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun minutes(from: Instant, to: Instant): Int = Duration.between(from, to).toMinutes().toInt().coerceAtLeast(0)

@Composable
private fun minutesText(from: Instant, to: Instant): String =
    formatMinutes(minutes(from, to)) { id, args -> stringResource(id, *args) }

// Mezzi su rotaia: l'icona del treno; gli altri quella dell'autobus.
private val RAIL_MODES = setOf(TransitMode.METRO, TransitMode.TRAIN, TransitMode.TRAM, TransitMode.MONORAIL)
