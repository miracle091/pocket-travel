package com.pockettravel.feature.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitFeedInfo
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Stato del pacchetto orari dei mezzi pubblici della regione, per la scheda delle fermate: [UNKNOWN] non mostra nulla. */
enum class TransitPackageState { UNKNOWN, INSTALLED, AVAILABLE, DOWNLOADING }

/** Le schede di queste categorie hanno la sezione "Prossime partenze". */
val TRANSIT_CATEGORIES = setOf(PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TRAGHETTO)

/**
 * "Prossime partenze" nella scheda di un POI di trasporto: le righe del tabellone, la scadenza degli
 * orari e le fonti; se gli orari non sono installati, il pulsante per scaricarli. [board] e' null
 * finche' si legge. [onDownload] scarica o aggiorna il pacchetto orari della regione.
 */
@Composable
internal fun TransitDeparturesSection(state: TransitPackageState, board: TransitBoard?, onDownload: () -> Unit) {
    if (state == TransitPackageState.UNKNOWN) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(R.string.transit_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            TransitPackageState.AVAILABLE -> FilledTonalButton(onClick = onDownload) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.transit_download), modifier = Modifier.padding(start = Spacing.s))
            }
            TransitPackageState.DOWNLOADING -> Loading(stringResource(R.string.transit_downloading))
            else -> when (board) {
                null -> Loading(stringResource(R.string.transit_loading))
                TransitBoard.NoStops -> Note(stringResource(R.string.transit_no_stops))
                is TransitBoard.Expired -> {
                    Note(stringResource(R.string.transit_expired, formatDate(board.validUntil)))
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.transit_update)) }
                    Sources(board.feeds)
                }
                is TransitBoard.Departures -> {
                    if (board.items.isEmpty()) Note(stringResource(R.string.transit_none_soon))
                    board.items.forEach { DepartureRow(it) }
                    Note(stringResource(R.string.transit_valid_until, formatDate(board.validUntil)))
                    if (board.expiresSoon) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.transit_expiring),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDownload) { Text(stringResource(R.string.transit_update)) }
                        }
                    }
                    Sources(board.feeds)
                }
            }
        }
    }
}

@Composable
private fun DepartureRow(departure: TransitDeparture) {
    val clock = "%02d:%02d".format(departure.minuteOfDay / 60, departure.minuteOfDay % 60)
    val relative = if (departure.inMinutes <= 0) stringResource(R.string.transit_now) else stringResource(R.string.transit_in_min, departure.inMinutes)
    val relativeSpoken = if (departure.inMinutes <= 0) {
        stringResource(R.string.transit_now)
    } else {
        pluralStringResource(R.plurals.transit_in_minutes_spoken, departure.inMinutes, departure.inMinutes)
    }
    val mode = stringResource(departure.mode.label())
    // Una sola voce per TalkBack: linea, mezzo, direzione e ora, senza leggere i pezzi a uno a uno.
    val description = departure.headsign?.let {
        stringResource(R.string.transit_departure_description, departure.line, mode, it, clock, relativeSpoken)
    } ?: stringResource(R.string.transit_departure_description_no_headsign, departure.line, mode, clock, relativeSpoken)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        // Il colore della linea e' solo decorazione: sigla, mezzo e destinazione stanno in chiaro.
        Surface(
            shape = MaterialTheme.shapes.small,
            color = departure.color?.let(::Color) ?: MaterialTheme.colorScheme.secondaryContainer,
            contentColor = departure.textColor?.let(::Color) ?: MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = departure.line,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(min = 44.dp, max = 96.dp).padding(horizontal = Spacing.s, vertical = Spacing.xs),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = departure.headsign ?: mode, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (departure.headsign != null) {
                Text(text = mode, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = clock, style = MaterialTheme.typography.titleMedium)
            Text(text = relative, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        PocketTravelLoadingIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Sources(feeds: List<TransitFeedInfo>) {
    // Con la data in cui gli orari sono stati presi dalla fonte (Licence Ouverte, Renfe).
    feeds.distinctBy { it.attribution to it.dataDate }.forEach { feed ->
        val text = feed.dataDate?.let { stringResource(R.string.transit_source_dated, feed.attribution, formatDate(it)) }
            ?: stringResource(R.string.transit_source, feed.attribution)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale))

@StringRes
private fun TransitMode.label(): Int = when (this) {
    TransitMode.TRAM -> R.string.transit_mode_tram
    TransitMode.METRO -> R.string.transit_mode_metro
    TransitMode.TRAIN -> R.string.transit_mode_train
    TransitMode.BUS -> R.string.transit_mode_bus
    TransitMode.TROLLEYBUS -> R.string.transit_mode_trolleybus
    TransitMode.FERRY -> R.string.transit_mode_ferry
    TransitMode.CABLE -> R.string.transit_mode_cable
    TransitMode.MONORAIL -> R.string.transit_mode_monorail
    TransitMode.OTHER -> R.string.transit_mode_other
}
