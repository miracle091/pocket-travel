package com.pockettravel.app.updates

import android.text.format.Formatter
import com.pockettravel.core.sync.SyncConfig
import androidx.core.net.toUri
import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.regions.RegionDownloadFlow
import com.pockettravel.app.regions.RegionRowActions
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.app.regions.downloadUi
import com.pockettravel.app.regions.label
import com.pockettravel.app.regions.rowActions
import com.pockettravel.app.regions.sizeText
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.core.sync.AppVersionEntry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.IndeterminateDownloadProgressIndicator
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing

internal const val PERCENT = 100

@Composable
internal fun AppSection(app: AppVersionEntry) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.updates_section_app))
    UpdateSurface {
        ListItem(
            supportingContent = { Text(stringResource(R.string.updates_app_subtitle)) },
            leadingContent = { Icon(AppIcons.Download, contentDescription = null) },
            trailingContent = {
                FilledTonalButton(onClick = { openReleasesPage(context) }) { Text(stringResource(R.string.updates_app_download)) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = { Text(stringResource(R.string.updates_app_title, app.versionName)) },
        )
    }
}

@Composable
internal fun RegionsSection(outdated: List<RegionUiItem>, pending: List<RegionUiItem>, rowActions: RegionRowActions, onUpdateAll: () -> Unit) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.updates_section_regions), modifier = Modifier.padding(top = Spacing.xl))
    UpdateSurface {
        Column {
            outdated.forEachIndexed { index, item ->
                UpdateRegionRow(item, rowActions)
                if (index < outdated.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
    if (pending.isNotEmpty()) {
        Button(onClick = onUpdateAll, modifier = Modifier.fillMaxWidth().padding(top = Spacing.m)) {
            Text(stringResource(R.string.updates_all, updateTotal(pending).text(context)))
        }
    }
}

@Composable
internal fun GuidesSection(guides: GuidesUpdate, syncing: Boolean, onUpdate: () -> Unit) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.updates_section_guides), modifier = Modifier.padding(top = Spacing.xl))
    UpdateSurface {
        Column {
            ListItem(
                supportingContent = {
                    Text(if (syncing) stringResource(R.string.updates_guides_syncing) else Formatter.formatShortFileSize(context, guides.sizeBytes))
                },
                leadingContent = { Icon(AppIcons.Book, contentDescription = null) },
                trailingContent = {
                    FilledTonalButton(onClick = onUpdate, enabled = !syncing) { Text(stringResource(R.string.updates_guides_now)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                content = { Text(stringResource(R.string.updates_guides_title)) },
            )
            if (syncing) {
                IndeterminateDownloadProgressIndicator(modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m))
            }
        }
    }
}

@Composable
internal fun ModelSection(model: AiModelManifestEntry, downloading: Boolean, progress: Float?, onDownload: () -> Unit) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.updates_section_model), modifier = Modifier.padding(top = Spacing.xl))
    UpdateSurface {
        Column {
            ListItem(
                supportingContent = {
                    Text(
                        if (downloading) {
                            stringResource(R.string.regions_status_downloading, ((progress ?: 0f) * PERCENT).toInt())
                        } else {
                            stringResource(R.string.updates_model_subtitle, Formatter.formatShortFileSize(context, model.sizeBytes))
                        },
                    )
                },
                leadingContent = { Icon(AppIcons.AiAssistant, contentDescription = null) },
                trailingContent = {
                    FilledTonalButton(onClick = onDownload, enabled = !downloading) { Text(stringResource(R.string.updates_model_download)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                content = { Text(model.modelVersion) },
            )
            if (downloading) {
                DownloadProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m),
                )
            }
        }
    }
}

@Composable
private fun UpdateRegionRow(item: RegionUiItem, actions: RegionRowActions) {
    val workInfo by remember(item.regionId) { actions.observeProgress(item.regionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val download = workInfo.downloadUi()
    val packages = item.packages.filter { it.status == RegionStatus.UPDATE_AVAILABLE }.map { stringResource(it.kind.label()) }.joinToString()
    Column {
        ListItem(
            supportingContent = {
                Text(
                    when {
                        download.isDownloading && download.installing -> stringResource(R.string.regions_status_installing)
                        download.isDownloading -> stringResource(
                            if (download.extractingMap) R.string.regions_status_extracting_map else R.string.regions_status_downloading,
                            (download.progress * PERCENT).toInt(),
                        )
                        else -> stringResource(R.string.updates_region_details, packages, item.sizeText())
                    },
                )
            },
            leadingContent = { CountryFlag(item.countryCode, size = 40.dp) { Icon(AppIcons.WorldFilled, contentDescription = null) } },
            trailingContent = {
                RegionDownloadFlow(item = item, actions = actions) { startDownload, _ ->
                    // Solo icona, come nell'elenco: un'etichetta schiaccerebbe il nome della nazione col testo grande.
                    FilledTonalIconButton(onClick = startDownload, enabled = !download.isDownloading) {
                        Icon(AppIcons.Download, contentDescription = stringResource(R.string.regions_update_region, item.displayName))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = { Text(item.displayName) },
        )
        if (download.isDownloading && download.installing) {
            IndeterminateDownloadProgressIndicator(modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m))
        } else if (download.isDownloading) {
            DownloadProgressIndicator(
                progress = { download.progress },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m),
            )
        }
    }
}

@Composable
private fun UpdateSurface(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

// Il peso delle nazioni da aggiornare, con "+ mappa" se tra i pacchetti c'è una mappa, che si pesa solo estraendola.
internal fun UpdateTotal.text(context: Context): String {
    val size = Formatter.formatShortFileSize(context, bytes)
    return when {
        !includesMap -> size
        bytes > 0 -> context.getString(R.string.regions_size_plus_map, size)
        else -> context.getString(R.string.regions_size_map_only)
    }
}

private fun openReleasesPage(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, SyncConfig.APP_RELEASES_URL.toUri()))
    } catch (_: ActivityNotFoundException) {
        // Nessun browser installato: non c'è dove aprire la pagina.
    }
}
