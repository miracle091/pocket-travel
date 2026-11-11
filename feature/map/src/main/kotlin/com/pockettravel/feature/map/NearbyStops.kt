package com.pockettravel.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.NEARBY_STOPS_RADIUS_M
import com.pockettravel.core.data.NEARBY_STOP_DEPARTURES
import com.pockettravel.core.data.NearbyStop
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.delay
import com.pockettravel.core.ui.R as UiR

// Il caricamento si mostra solo se dura piu' di cosi': una lettura veloce non fa lampeggiare l'indicatore.
private const val LOADING_DELAY_MILLIS = 300L

/**
 * Il pannello "Fermate vicine": da dove si cerca, i filtri per mezzo e le fermate con le prossime partenze.
 * Un tocco su una fermata la apre nel tabellone ([onStopClick]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NearbyStopsSheet(
    state: NearbyStopsUi,
    onModeChange: (TransitMode?) -> Unit,
    onStopClick: (NearbyStop) -> Unit,
    onDismiss: () -> Unit,
) {
    val showLoading by produceState(false, state.loading) {
        value = false
        if (state.loading) {
            delay(LOADING_DELAY_MILLIS)
            value = true
        }
    }
    val rows = state.visibleRows
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xxl)) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.nearby_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(if (state.origin == NearbyOrigin.POSITION) R.string.nearby_origin_position else R.string.nearby_origin_map),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.modes.size > 1) {
                item(key = "modes") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Spacing.m),
                    ) {
                        val selected = state.mode?.takeIf { it in state.modes }
                        FilterChip(selected = selected == null, onClick = { onModeChange(null) }, label = { Text(stringResource(R.string.nearby_mode_all)) })
                        state.modes.forEach { mode ->
                            FilterChip(selected = selected == mode, onClick = { onModeChange(mode) }, label = { Text(stringResource(mode.label())) })
                        }
                    }
                }
            }
            if (state.loading && rows.isEmpty()) {
                if (showLoading) {
                    item(key = "loading") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                            modifier = Modifier.padding(top = Spacing.l),
                        ) {
                            PocketTravelLoadingIndicator()
                            Text(stringResource(R.string.transit_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.nearby_empty, NEARBY_STOPS_RADIUS_M.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.l),
                    )
                }
            }
            items(rows, key = { "${it.stop.name}|${it.stop.latitude}|${it.stop.longitude}" }) { row ->
                NearbyStopItem(row, onClick = { onStopClick(row.stop) })
            }
        }
    }
}

@Composable
private fun NearbyStopItem(row: NearbyStopRow, onClick: () -> Unit) {
    val stop = row.stop
    val modes = stop.modes.sortedBy { it.ordinal }.map { stringResource(it.label()) }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.nearby_open_departures), onClick = onClick)
            .padding(vertical = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.favorite) {
                Icon(
                    ImageVector.vectorResource(UiR.drawable.ms_star_fill),
                    contentDescription = stringResource(R.string.nearby_favorite),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.s))
            }
            Text(text = stop.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(text = distanceText(stop.distanceMeters), style = MaterialTheme.typography.bodyMedium)
        }
        if (modes.isNotEmpty()) {
            Text(text = modes.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stop.next.isEmpty()) {
            Text(text = stringResource(R.string.transit_none_soon), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stop.next.take(NEARBY_STOP_DEPARTURES).forEach { NearbyDeparture(it) }
    }
}

// Come una riga del tabellone, in piccolo: sigla della linea col suo colore, direzione e minuti all'arrivo.
@Composable
private fun NearbyDeparture(departure: TransitDeparture) {
    val relative = if (departure.inMinutes <= 0) stringResource(R.string.transit_now) else stringResource(R.string.transit_in_min, departure.inMinutes)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        LineBadge(departure)
        Text(
            text = departure.headsign ?: stringResource(departure.mode.label()),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = relative, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Il pin con cui si apre il tabellone di una fermata dell'elenco: il suo nome e le sue coordinate, la categoria dal mezzo. */
internal fun NearbyStop.toPin(): MapPin = MapPin(
    id = "stop:$name",
    name = name,
    latitude = latitude,
    longitude = longitude,
    category = when {
        TransitMode.TRAIN in modes -> PoiCategory.TRENO
        TransitMode.METRO in modes -> PoiCategory.METRO
        TransitMode.FERRY in modes -> PoiCategory.TRAGHETTO
        else -> PoiCategory.AUTOBUS
    },
    // Niente tag OSM: le fermate vengono dagli orari, e il tabellone le cerca attorno alle coordinate.
    osmTag = "",
    phone = null,
)
