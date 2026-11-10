package com.pockettravel.app.regions

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.IndeterminateDownloadProgressIndicator
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork

// Regione installata che il catalogo ha diviso in regioni piu' piccole: si apre ancora (i dati sono
// sul dispositivo), non si aggiorna piu'; "Scegli" porta alle regioni che la sostituiscono.
@Composable
internal fun ReplacedRegionRow(item: ReplacedRegionItem, onOpen: () -> Unit, onChoose: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onOpen)) {
        ListItem(
            supportingContent = { Text(stringResource(R.string.regions_replaced)) },
            leadingContent = {
                CountryFlag(item.countryCode, size = 40.dp) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(AppIcons.WorldFilled, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(Spacing.s))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            content = { Text(item.displayName) },
        )
        Row(modifier = Modifier.padding(start = 72.dp, end = Spacing.s, bottom = Spacing.s)) {
            FilledTonalButton(onClick = onChoose) { Text(stringResource(R.string.regions_replaced_choose)) }
            Spacer(modifier = Modifier.width(Spacing.s))
            TextButton(onClick = onDelete) { Text(stringResource(R.string.regions_replaced_delete)) }
        }
    }
}

// Riga di una nazione divisa in piu' regioni: bandiera, nome, quante regioni, freccia per aprirla.
@Composable
internal fun CountryRow(country: RegionListEntry.Country, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "countryChevron")
    val stateText = stringResource(if (expanded) R.string.continent_expanded else R.string.continent_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.country_regions_collapse else R.string.country_regions_expand)
    ListItem(
        supportingContent = { Text(pluralStringResource(R.plurals.country_region_count, country.items.size, country.items.size)) },
        leadingContent = {
            CountryFlag(country.countryCode, size = 40.dp) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Icon(AppIcons.World, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.s))
                }
            }
        },
        trailingContent = if (enabled) {
            { Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(enabled = enabled, onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        content = { Text(country.name) },
    )
}

@Composable
internal fun groupShape(index: Int, size: Int) = MaterialTheme.shapes.large.let { large ->
    when {
        size == 1 -> large
        index == 0 -> large.copy(bottomStart = ZeroCorner, bottomEnd = ZeroCorner)
        index == size - 1 -> large.copy(topStart = ZeroCorner, topEnd = ZeroCorner)
        else -> RoundedCornerShape(0)
    }
}

private val ZeroCorner = CornerSize(0)

private const val PERCENT_SCALE = 100

// Riusata anche dallo step di onboarding "Scarica una regione". Il tap e' sempre attivo: per una
// regione non installata porta a un'anteprima della guida Wikivoyage (senza scaricare
// mappa/rete stradale), vedi RegionPreviewScreen.
@Composable
internal fun RegionRow(
    item: RegionUiItem,
    actions: RegionRowActions,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = item.displayName,
    // Catalogo in lettura: le nazioni installate dicono il controllo invece di dimensione e stato, che possono cambiare.
    checking: CatalogCheck? = null,
) {
    val workInfo by remember(item.regionId) { actions.observeProgress(item.regionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val download = workInfo.downloadUi()
    val (isDownloading, installing) = download
    val progress = download.progress
    val installed = item.status != RegionStatus.NOT_INSTALLED

    Column(modifier = modifier) {
        ListItem(
            supportingContent = { Text(regionRowStatus(item, download, checking?.takeIf { installed })) },
            leadingContent = {
                CountryFlag(item.countryCode, size = 40.dp) {
                    Surface(
                        shape = CircleShape,
                        color = if (installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Icon(
                            imageVector = if (installed) AppIcons.WorldFilled else AppIcons.World,
                            contentDescription = null,
                            tint = if (installed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.s),
                        )
                    }
                }
            },
            trailingContent = { RegionActionButton(item = item, downloading = isDownloading, actions = actions) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick),
            content = { Text(title) },
        )
        if (isDownloading && installing) {
            IndeterminateDownloadProgressIndicator(modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m))
        } else if (isDownloading) {
            DownloadProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m),
            )
        }
    }
}

// Riga sotto il nome: download in corso, controllo del catalogo, oppure dimensione e stato.
@Composable
private fun regionRowStatus(item: RegionUiItem, download: RegionDownloadUi, checking: CatalogCheck?): String = when {
    download.isDownloading && download.installing -> stringResource(R.string.regions_status_installing)
    download.isDownloading -> stringResource(
        if (download.extractingMap) R.string.regions_status_extracting_map else R.string.regions_status_downloading,
        (download.progress * PERCENT_SCALE).toInt(),
    )
    checking == CatalogCheck.UPDATES -> stringResource(R.string.regions_checking_updates)
    checking == CatalogCheck.CATALOG -> stringResource(R.string.regions_checking_catalog)
    else -> stringResource(item.statusLabel(), item.sizeText())
}

/** Avanzamento di un download di regione, letto dal lavoro: usato dalle righe dell'elenco e da Aggiornamenti. */
internal data class RegionDownloadUi(val isDownloading: Boolean, val installing: Boolean, val extractingMap: Boolean, val progress: Float)

internal fun WorkInfo?.downloadUi(): RegionDownloadUi {
    val isDownloading = this?.state == WorkInfo.State.RUNNING || this?.state == WorkInfo.State.ENQUEUED
    // Due fasi: prima i file (byte), poi l'estrazione della mappa (byte di tile), ognuna da 0 a 100%.
    val mapBytesTotal = this?.progress?.getLong(RegionPackageDownloadWorker.KEY_MAP_BYTES_TOTAL, 0L) ?: 0L
    val extractingMap = mapBytesTotal > 0
    val installing = this?.progress?.getBoolean(RegionPackageDownloadWorker.KEY_INSTALLING, false) == true
    val progress = this?.progress?.let { data ->
        if (extractingMap) {
            data.getLong(RegionPackageDownloadWorker.KEY_MAP_BYTES_DONE, 0L) / mapBytesTotal.toFloat()
        } else {
            val total = data.getLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 0L)
            if (total > 0) data.getLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 0L) / total.toFloat() else 0f
        }
    } ?: 0f
    return RegionDownloadUi(isDownloading, installing, extractingMap, progress)
}

@Composable
private fun RegionActionButton(item: RegionUiItem, downloading: Boolean, actions: RegionRowActions) {
    var showPackages by rememberSaveable { mutableStateOf(false) }
    // Con le voci della copia salvata, download e scelte dei contenuti aspettano il catalogo del sito.
    val isDownloading = downloading || item.fromSavedCopy
    RegionDownloadFlow(item = item, actions = actions) { startDownload, onDownloadClick ->
        when (item.status) {
            // Nazione grande non ancora scaricata: prima la scelta fra tutta la nazione e una zona.
            RegionStatus.NOT_INSTALLED -> FilledTonalIconButton(onClick = onDownloadClick, enabled = !isDownloading) {
                Icon(AppIcons.Download, contentDescription = stringResource(R.string.regions_download, item.displayName))
            }
            RegionStatus.UPDATE_AVAILABLE, RegionStatus.INSTALLED -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.status == RegionStatus.UPDATE_AVAILABLE) {
                    // Solo icona, come "Scarica": un'etichetta schiaccerebbe il nome della regione (col testo grande fino a
                    // spezzarlo lettera per lettera). TalkBack legge "Aggiorna <regione>".
                    FilledTonalIconButton(onClick = startDownload, enabled = !isDownloading) {
                        Icon(AppIcons.Download, contentDescription = stringResource(R.string.regions_update_region, item.displayName))
                    }
                }
                // Mappa, rete stradale e POI uno per uno, ed "Elimina tutto": vedi RegionPackagesSheet.
                IconButton(onClick = { showPackages = true }) {
                    Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.regions_packages, item.displayName))
                }
            }
        }
    }

    if (showPackages) {
        RegionPackagesSheet(item = item, isDownloading = isDownloading, actions = actions, onDismiss = { showPackages = false })
    }
}

/**
 * Il percorso di download di una regione, comune all'elenco e all'anteprima: avviso per i download grandi su rete
 * cellulare, scelta fra tutta la nazione e una zona per le nazioni grandi. [content] riceve [startDownload] (download
 * diretto, con l'avviso) e [onDownloadClick], il gesto "Scarica" di una regione non installata (con la scelta della zona).
 */
@Composable
internal fun RegionDownloadFlow(
    item: RegionUiItem,
    actions: RegionRowActions,
    content: @Composable (startDownload: () -> Unit, onDownloadClick: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var showLargeDownloadWarning by rememberSaveable { mutableStateOf(false) }
    var showZoneChoice by rememberSaveable { mutableStateOf(false) }
    var pickingZone by rememberSaveable { mutableStateOf(false) }
    val size = item.sizeText()

    val startDownload = {
        if ((item.sizeBytes > LARGE_DOWNLOAD_WARNING_BYTES || item.includesMap) && isOnCellularNetwork(context)) {
            showLargeDownloadWarning = true
        } else {
            actions.onDownload(item.regionId)
        }
    }
    content(startDownload) { if (item.bbox?.isLarge() == true) showZoneChoice = true else startDownload() }

    if (showZoneChoice) {
        ZoneChoiceDialog(
            displayName = item.displayName,
            splitCountry = item.splitCountry,
            onWholeRegion = { showZoneChoice = false; actions.onZoneChange(item.regionId, null, false); startDownload() },
            onPickZone = { showZoneChoice = false; pickingZone = true },
            onDismiss = { showZoneChoice = false },
        )
    }
    if (pickingZone && item.bbox != null) {
        ZoneSelection(item = item, download = true, actions = actions, onDone = { pickingZone = false })
    }

    if (showLargeDownloadWarning) {
        ConfirmationDialog(
            title = stringResource(R.string.regions_large_download_title),
            message = stringResource(R.string.regions_large_download_message, item.displayName, size),
            confirmLabel = stringResource(R.string.regions_large_download_confirm),
            destructive = false,
            onConfirm = { showLargeDownloadWarning = false; actions.onDownload(item.regionId) },
            onDismiss = { showLargeDownloadWarning = false },
        )
    }
}

// Peso da scaricare (o installato): con la mappa tra i pacchetti da scaricare si aggiunge "+ mappa",
// perche' il suo peso si conosce solo estraendola (per la Lettonia 248 MB contro 6,3 MB di file).
@Composable
internal fun RegionUiItem.sizeText(): String {
    val bytes = Formatter.formatShortFileSize(LocalContext.current, sizeBytes)
    return when {
        !includesMap -> bytes
        sizeBytes > 0 -> stringResource(R.string.regions_size_plus_map, bytes)
        else -> stringResource(R.string.regions_size_map_only)
    }
}

@StringRes
private fun RegionUiItem.statusLabel(): Int = when (status) {
    RegionStatus.NOT_INSTALLED -> R.string.regions_status_not_installed
    // I POI extra e gli orari dei mezzi pubblici si scaricano solo su richiesta: senza, la regione e' comunque completa.
    RegionStatus.INSTALLED ->
        if (packages.any { it.status == RegionStatus.NOT_INSTALLED && it.kind != PackageKind.POI_EXTRA && it.kind != PackageKind.TRANSIT }) {
            R.string.regions_status_partial
        } else {
            R.string.regions_status_installed
        }
    RegionStatus.UPDATE_AVAILABLE -> R.string.regions_status_update
}
