package com.pockettravel.feature.map

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.Spacing

/**
 * Le regioni senza rete stradale tra partenza e arrivo, in ordine dalla partenza, ciascuna col suo peso; l'icona in
 * alto le scarica tutte. Durante il download, al posto dell'icona l'avanzamento complessivo.
 */
@Composable
internal fun MissingRoutingCard(
    regions: List<MissingRegion>,
    downloadProgress: Float?,
    downloadFailed: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        when {
                            downloadFailed -> R.string.planner_routing_failed
                            // Solo regioni con la rete stradale solo auto: a piedi o in bici serve quella completa.
                            regions.all { it.carOnly } -> R.string.navigation_car_only_routing_list
                            else -> R.string.navigation_missing_routing_list
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (downloadProgress == null) {
                    Spacer(modifier = Modifier.width(Spacing.m))
                    FilledTonalIconButton(onClick = onDownload) {
                        Icon(
                            AppIcons.Download,
                            contentDescription = stringResource(
                                R.string.navigation_routing_download_all,
                                Formatter.formatShortFileSize(context, regions.sumOf { it.routingBytes }),
                            ),
                        )
                    }
                }
            }
            regions.forEachIndexed { index, region ->
                Row(modifier = Modifier.padding(top = Spacing.s)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(32.dp).alignByBaseline())
                    Text(region.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).alignByBaseline())
                    Text(
                        Formatter.formatShortFileSize(context, region.routingBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (downloadProgress != null) {
                Text(
                    stringResource(R.string.navigation_routing_progress_all, (downloadProgress * 100).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.m),
                )
                DownloadProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s))
            }
        }
    }
}
