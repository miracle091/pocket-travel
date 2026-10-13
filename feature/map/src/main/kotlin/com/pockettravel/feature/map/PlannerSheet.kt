package com.pockettravel.feature.map

import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import java.time.LocalDateTime
import com.pockettravel.core.ui.R as UiR

@Composable
internal fun PlannerSheet(
    to: NavigationPlace?,
    startsFromMe: Boolean,
    preview: PlannerPreview,
    recents: List<NavigationPlace>,
    onRecent: (NavigationPlace) -> Unit,
    onRemoveRecent: (NavigationPlace) -> Unit,
    onClearRecents: () -> Unit,
    onStart: () -> Unit,
    arriveBy: LocalDateTime?,
    reminder: LocalDateTime?,
    onArriveByChange: (LocalDateTime?) -> Unit,
    onSetReminder: (LocalDateTime) -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
    routingInstalled: Boolean,
    downloadProgress: Float?,
    downloadFailed: Boolean,
    onDownloadRouting: () -> Unit,
    routeProfile: RouteProfile,
    // Le regioni del catalogo senza rete stradale tra partenza e arrivo, in ordine (vuota se non si sono trovate).
    missingRegions: List<MissingRegion>,
    wheelchairOptions: (@Composable () -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.l)) {
        when {
            to == null -> Recents(recents, onRecent, onRemoveRecent, onClearRecents)
            preview is PlannerPreview.Ready -> RouteSummary(preview.route, startsFromMe, onStart, wheelchairOptions, arriveBy, reminder, onArriveByChange, onSetReminder)
            // Il download si propone se il catalogo dice quali regioni mancano tra partenza e arrivo, o se la regione aperta
            // non ha la rete stradale; altrimenti "nessun dato" vuol dire fuori dalle zone scaricate, e riscaricare non cambierebbe nulla.
            preview is PlannerPreview.Unavailable && preview.result == RouteResult.NoRoutingData && missingRegions.isNotEmpty() ->
                MissingRoutingCard(missingRegions, downloadProgress, downloadFailed, onDownloadRouting)
            preview is PlannerPreview.Unavailable && preview.result == RouteResult.NoRoutingData && !routingInstalled -> {
                if (downloadProgress != null) {
                    Text(
                        stringResource(R.string.planner_routing_progress, (downloadProgress * PERCENT_SCALE).toInt()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = Spacing.m),
                    )
                    DownloadProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.m))
                } else {
                    SheetMessage(
                        when {
                            downloadFailed -> stringResource(R.string.planner_routing_failed)
                            else -> stringResource(R.string.planner_no_routing_data)
                        },
                        stringResource(R.string.navigation_routing_download),
                        onDownloadRouting,
                    )
                }
            }
            preview is PlannerPreview.Unavailable && preview.result == RouteResult.NoRoutingData ->
                SheetMessage(stringResource(R.string.navigation_outside_routing))
            preview is PlannerPreview.Calculating -> Calculating(preview.progress, routeProfile)
            preview is PlannerPreview.NeedsPermission ->
                SheetMessage(stringResource(R.string.planner_permission), stringResource(R.string.navigation_permission_grant), onRequestPermission)
            preview is PlannerPreview.NoLocation ->
                RetryMessage(stringResource(R.string.planner_no_location), onRetry)
            preview is PlannerPreview.Unavailable -> RetryMessage(
                stringResource(
                    when (preview.result) {
                        RouteResult.NotFound -> R.string.navigation_not_found
                        RouteResult.TimedOut -> R.string.navigation_timed_out
                        else -> R.string.navigation_failed
                    },
                ),
                onRetry,
            )
            else -> Unit
        }
    }
}

// Indicatore Expressive (forme che cambiano) e, appena BRouter da' una stima, mezzo, percentuale e barra:
// un calcolo in auto puo' durare minuti, una scritta ferma sembrerebbe un blocco. TalkBack legge solo il
// titolo, non ogni cambio di percentuale.
@Composable
private fun Calculating(progress: Double, routeProfile: RouteProfile) {
    val mode = stringResource(
        when (routeProfile) {
            RouteProfile.WALK -> R.string.usage_mode_walk
            RouteProfile.BIKE -> R.string.usage_mode_bike
            RouteProfile.CAR -> R.string.usage_mode_car
        },
    )
    val title = stringResource(R.string.planner_calculating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Spacing.m).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        PocketTravelLoadingIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(Spacing.m))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (progress > 0) stringResource(R.string.planner_calculating_progress, mode, (progress * PERCENT_SCALE).toInt()) else mode,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
    if (progress > 0) {
        DownloadProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.m))
    }
}

@Composable
private fun Recents(recents: List<NavigationPlace>, onRecent: (NavigationPlace) -> Unit, onRemove: (NavigationPlace) -> Unit, onClearAll: () -> Unit) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // Titolo a sinistra, "cancella tutte" a destra: solo se c'e' qualcosa da cancellare.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.planner_recents),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.s).semantics { heading() },
        )
        if (recents.isNotEmpty()) {
            IconButton(onClick = { confirmClear = true }) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.planner_recents_clear))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(AppIcons.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.planner_recents_clear_title)) },
            text = { Text(stringResource(R.string.planner_recents_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text(stringResource(R.string.planner_recents_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (recents.isEmpty()) return
    // Righe larghe quanto il riquadro: l'icona allineata al titolo "Recenti", la X al cestino (un ListItem aggiungerebbe
    // il suo margine a quello del riquadro).
    recents.forEach { place ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onRecent(place) },
        ) {
            Icon(ImageVector.vectorResource(UiR.drawable.ms_history), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(Spacing.l))
            Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { onRemove(place) }) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_remove_recent, place.name))
            }
        }
    }
}

// Tempo in grande e distanza, poi "Avvia" e le svolte: il pannello di un percorso in Google Maps.
@Composable
private fun RouteSummary(
    route: Route,
    startsFromMe: Boolean,
    onStart: () -> Unit,
    wheelchairOptions: (@Composable () -> Unit)?,
    arriveBy: LocalDateTime?,
    reminder: LocalDateTime?,
    onArriveByChange: (LocalDateTime?) -> Unit,
    onSetReminder: (LocalDateTime) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.s)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(durationText(route.durationSeconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(distanceText(route.distanceMeters), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (startsFromMe) {
            // Pulsante tondo con l'icona, come la X che chiude la navigazione: il nome lo legge TalkBack.
            FilledIconButton(onClick = onStart, modifier = Modifier.size(56.dp)) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_navigation), contentDescription = stringResource(R.string.planner_start))
            }
        }
    }
    ArriveBy(route, arriveBy, reminder, onArriveByChange, onSetReminder)
    // Sotto tempo e distanza: cambia il percorso come il mezzo, ma non deve coprire la mappa in alto.
    wheelchairOptions?.invoke()
    if (!startsFromMe) {
        Text(stringResource(R.string.planner_preview_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s))
    Text(
        stringResource(R.string.planner_steps),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() },
    )
    // Row invece di ListItem, senza il suo margine laterale: le svolte si allineano al tempo e al titolo sopra.
    route.instructions.forEach { instruction ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = Spacing.s)
                .semantics(mergeDescendants = true) {},
        ) {
            Icon(turnIcon(instruction.type), contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.l))
            Column {
                Text(turnText(instruction), style = MaterialTheme.typography.bodyLarge)
                if (instruction.distanceToNextMeters > 0) {
                    Text(
                        distanceText(instruction.distanceToNextMeters),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite })
    if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
}

// Errore con "Riprova" come icona sulla stessa riga del messaggio, a destra.
@Composable
private fun RetryMessage(text: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.s)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(modifier = Modifier.width(Spacing.m))
        FilledTonalIconButton(onClick = onRetry) {
            Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.navigation_retry))
        }
    }
}

private const val PERCENT_SCALE = 100
