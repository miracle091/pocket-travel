package com.pockettravel.feature.map

import android.text.format.DateFormat
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
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
import com.pockettravel.core.ui.R as UiR
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.TimeZone

/**
 * Stato del pacchetto orari dei mezzi pubblici della regione, per il riquadro delle fermate: [UNKNOWN] non mostra
 * nulla (catalogo non letto), [NOT_OFFERED] dice che per questa zona non ci sono orari.
 */
enum class TransitPackageState { UNKNOWN, INSTALLED, AVAILABLE, DOWNLOADING, NOT_OFFERED }

/** Esito di "Aggiorna" o "Scarica" quando non c'e' un download in corso: catalogo senza orari piu' recenti, o errore. */
enum class TransitUpdateResult { UP_TO_DATE, FAILED }

/** I riquadri di queste categorie hanno la sezione "Prossime partenze". */
val TRANSIT_CATEGORIES = setOf(PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TRAGHETTO)

/**
 * I mezzi di una stazione o di un terminal (non delle fermate railway=halt): le sue fermate GTFS di quei mezzi si cercano
 * piu' lontano dal punto OSM. Vuoto per gli altri POI.
 */
fun transitStationModes(osmTag: String): Set<TransitMode> = when (osmTag) {
    "railway=station" -> setOf(TransitMode.TRAIN, TransitMode.METRO)
    "amenity=bus_station" -> setOf(TransitMode.BUS, TransitMode.TROLLEYBUS)
    "amenity=ferry_terminal" -> setOf(TransitMode.FERRY)
    else -> emptySet()
}

/**
 * "Prossime partenze" nel riquadro di un POI di trasporto: le righe del tabellone, la scadenza degli
 * orari e le fonti; se gli orari non sono installati, il pulsante per scaricarli. [board] e' null
 * finche' si legge. [onDownload] scarica o aggiorna il pacchetto orari della regione; [updateResult] e' l'esito
 * dell'ultimo tocco, se non ha avviato un download.
 */
@Composable
internal fun TransitDeparturesSection(
    state: TransitPackageState,
    board: TransitBoard?,
    showAccessibility: Boolean,
    updateResult: TransitUpdateResult?,
    onDownload: () -> Unit,
) {
    if (state == TransitPackageState.UNKNOWN) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(R.string.transit_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        when (state) {
            TransitPackageState.NOT_OFFERED -> Note(stringResource(R.string.transit_not_offered))
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
                    // Accessibilita' solo con "In sedia a rotelle", e solo se la rete la indica.
                    if (showAccessibility) {
                        board.stopWheelchair?.let { accessible ->
                            AccessibilityRow(accessible, if (accessible) R.string.transit_stop_wheelchair_yes else R.string.transit_stop_wheelchair_no)
                        }
                    }
                    // Reti scadute accanto a reti valide: si dice quali partenze mancano, sopra l'elenco.
                    if (board.expired.isNotEmpty()) {
                        board.expired.forEach { ExpiredFeedNote(it) }
                        TextButton(onClick = onDownload) { Text(stringResource(R.string.transit_update)) }
                    }
                    if (board.items.isEmpty()) Note(stringResource(R.string.transit_none_soon))
                    board.items.forEach { DepartureRow(it, showAccessibility) }
                    // Con le sole reti scadute da poco la scadenza e' gia' nella nota sopra.
                    if (board.daysLeft >= 0) Note(stringResource(R.string.transit_valid_until, formatDate(board.validUntil)))
                    if (board.expiresSoon) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.transit_expiring),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            // "Aggiorna" e' gia' sopra, accanto alle reti scadute.
                            if (board.expired.isEmpty()) TextButton(onClick = onDownload) { Text(stringResource(R.string.transit_update)) }
                        }
                    }
                    Sources(board.feeds)
                }
            }
        }
        when (updateResult) {
            TransitUpdateResult.UP_TO_DATE -> Note(stringResource(R.string.transit_up_to_date))
            TransitUpdateResult.FAILED -> Text(
                stringResource(R.string.transit_update_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }
}

private const val MINUTES_PER_DAY = 24 * 60
private const val MILLIS_PER_MINUTE = 60_000L

@Composable
private fun DepartureRow(departure: TransitDeparture, showAccessibility: Boolean) {
    // Ora della rete nel formato del telefono (24 h o AM/PM, anche per TalkBack). Il formatter va in UTC: minuteOfDay e'
    // gia' un orario locale della rete e non va spostato col fuso del telefono; GTFS supera le 24 h dopo mezzanotte.
    val context = LocalContext.current
    val (clock, laterClocks) = remember(departure.minuteOfDay, departure.later, context) {
        val format = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun clockOf(minute: Int) = format.format(Date(minute % MINUTES_PER_DAY * MILLIS_PER_MINUTE))
        clockOf(departure.minuteOfDay) to departure.later.joinToString(", ") { clockOf(it.minuteOfDay) }
    }
    // Le partenze successive della stessa linea e direzione: una riga per linea, anche nelle fermate affollate.
    val laterText = laterClocks.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.transit_later, it) }
    val relative = if (departure.inMinutes <= 0) stringResource(R.string.transit_now) else stringResource(R.string.transit_in_min, departure.inMinutes)
    val relativeSpoken = if (departure.inMinutes <= 0) {
        stringResource(R.string.transit_now)
    } else {
        pluralStringResource(R.plurals.transit_in_minutes_spoken, departure.inMinutes, departure.inMinutes)
    }
    val mode = stringResource(departure.mode.label()).let { if (departure.estimated) stringResource(R.string.transit_mode_estimated, it) else it }
    // Una sola voce per TalkBack: linea, mezzo, direzione e ora, senza leggere i pezzi a uno a uno.
    // Senza "direzione" quando ripete la linea (reti che usano il percorso come sigla): TalkBack lo direbbe due volte.
    val description = departure.headsign?.takeIf { it != departure.line }?.let {
        stringResource(R.string.transit_departure_description, departure.line, mode, it, clock, relativeSpoken)
    } ?: stringResource(R.string.transit_departure_description_no_headsign, departure.line, mode, clock, relativeSpoken)
    val accessible = departure.wheelchair.takeIf { showAccessibility }
    val accessibleText = accessible?.let { stringResource(if (it) R.string.transit_departure_wheelchair_yes else R.string.transit_departure_wheelchair_no) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(description, laterText, accessibleText).joinToString(", ")
        },
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
            if (laterText != null) {
                Text(text = laterText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (accessible != null) AccessibilityIcon(accessible)
        Column(horizontalAlignment = Alignment.End) {
            Text(text = clock, style = MaterialTheme.typography.titleMedium)
            Text(text = relative, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Carrozzina: nel colore del testo se accessibile, barrata e in quello di errore se no (la barra la distingue
// anche senza colori). Il significato sta anche nel testo (fermata) e nella descrizione per TalkBack (partenza).
@Composable
private fun AccessibilityIcon(accessible: Boolean) {
    val tint = if (accessible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Icon(
        ImageVector.vectorResource(UiR.drawable.ms_accessible),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp).drawWithContent {
            drawContent()
            if (!accessible) drawLine(tint, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 2.dp.toPx())
        },
    )
}

@Composable
private fun AccessibilityRow(accessible: Boolean, @StringRes label: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        AccessibilityIcon(accessible)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExpiredFeedNote(board: TransitBoard.Expired) {
    val until = formatDate(board.validUntil)
    val name = board.feeds.firstOrNull()?.name
    val text = when {
        board.estimated && name != null -> stringResource(R.string.transit_feed_estimated, name, until)
        board.estimated -> stringResource(R.string.transit_feed_estimated_unnamed, until)
        name != null -> stringResource(R.string.transit_feed_expired, name, until)
        else -> stringResource(R.string.transit_feed_expired_unnamed, until)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
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
