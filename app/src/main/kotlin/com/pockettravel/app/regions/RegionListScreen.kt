package com.pockettravel.app.regions

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun RegionListScreen(
    onRegionClick: (String) -> Unit,
    onPreviewClick: (regionId: String, displayName: String) -> Unit,
    viewModel: RegionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RegionListContent(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onCheckUpdates = viewModel::checkForUpdatesNow,
        onRetry = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
        rowActions = RegionRowActions(
            observeProgress = viewModel::observeDownloadProgress,
            onDownload = viewModel::download,
            onDelete = viewModel::delete,
        ),
        onRegionClick = { item ->
            if (item.status == RegionStatus.NOT_INSTALLED) {
                onPreviewClick(item.regionId, item.displayName)
            } else {
                onRegionClick(item.regionId)
            }
        },
    )
}

// Azioni di una riga regione, raccolte per poter essere fornite dal ViewModel (schermata e step
// di onboarding) o da valori finti (@Preview).
internal class RegionRowActions(
    val observeProgress: (regionId: String) -> Flow<WorkInfo?>,
    val onDownload: (regionId: String) -> Unit,
    val onDelete: (regionId: String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionListContent(
    uiState: RegionListUiState,
    onQueryChange: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.regions_title)) },
                actions = {
                    IconButton(onClick = onCheckUpdates) {
                        Icon(imageVector = AppIcons.Refresh, contentDescription = stringResource(R.string.regions_check_updates))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            RegionSearchField(
                query = uiState.query,
                onQueryChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
            when {
                uiState.isLoading && uiState.items.isEmpty() && uiState.query.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.loadError != null && uiState.items.isEmpty() -> EmptyState(
                    icon = AppIcons.OfflineWifi,
                    title = stringResource(uiState.loadError),
                    modifier = Modifier.fillMaxSize(),
                    action = { FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.regions_retry)) } },
                )

                uiState.items.isEmpty() && uiState.query.isNotBlank() -> EmptyState(
                    icon = AppIcons.Search,
                    title = stringResource(R.string.regions_search_empty_title),
                    subtitle = stringResource(R.string.regions_search_empty_subtitle, uiState.query),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> RegionGroupedList(items = uiState.items, rowActions = rowActions, onRegionClick = onRegionClick)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // Barra di ricerca M3 "docked" usata solo come campo: filtra l'elenco sotto mentre si scrive,
    // senza aprire una vista di risultati separata.
    DockedSearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(stringResource(R.string.regions_search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.regions_search_clear))
                        }
                    }
                } else {
                    null
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
    ) {}
}

@Composable
private fun RegionGroupedList(
    items: List<RegionUiItem>,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
) {
    val groups = groupByContinent(items)
    val otherLabel = stringResource(R.string.continent_other)
    LazyColumn(
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
    ) {
        groups.forEach { (continent, regions) ->
            item(key = "header_${continent ?: ""}") {
                Text(
                    text = continent ?: otherLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s)
                        .semantics { heading() },
                )
            }
            // Un gruppo per continente: righe su una superficie tonale arrotondata, separate da
            // divisori, invece di una card per riga.
            itemsIndexed(regions, key = { _, item -> item.regionId }) { index, item ->
                val shape = groupShape(index, regions.size)
                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        RegionRow(item = item, actions = rowActions, onClick = { onRegionClick(item) })
                        if (index < regions.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun groupShape(index: Int, size: Int) = MaterialTheme.shapes.large.let { large ->
    when {
        size == 1 -> large
        index == 0 -> large.copy(bottomStart = ZeroCorner, bottomEnd = ZeroCorner)
        index == size - 1 -> large.copy(topStart = ZeroCorner, topEnd = ZeroCorner)
        else -> RoundedCornerShape(0)
    }
}

private val ZeroCorner = CornerSize(0)

// Riusata anche dallo step di onboarding "Scarica una regione". Il tap e' sempre attivo: per una
// regione non installata porta a un'anteprima della guida Wikivoyage (senza scaricare
// mappa/routing), vedi RegionPreviewScreen.
@Composable
internal fun RegionRow(item: RegionUiItem, actions: RegionRowActions, onClick: () -> Unit) {
    val workInfo by remember(item.regionId) { actions.observeProgress(item.regionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val isDownloading = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val progress = workInfo?.progress?.let { data ->
        val total = data.getLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 0L)
        if (total > 0) data.getLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 0L) / total.toFloat() else 0f
    } ?: 0f
    val size = Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes)
    val installed = item.status != RegionStatus.NOT_INSTALLED

    Column {
        ListItem(
            headlineContent = { Text(item.displayName) },
            supportingContent = {
                Text(
                    if (isDownloading) {
                        stringResource(R.string.regions_status_downloading, (progress * 100).toInt())
                    } else {
                        stringResource(item.status.label(), size)
                    },
                )
            },
            leadingContent = {
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
            },
            trailingContent = { RegionActionButton(item = item, isDownloading = isDownloading, actions = actions) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick),
        )
        if (isDownloading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m),
            )
        }
    }
}

@Composable
private fun RegionActionButton(item: RegionUiItem, isDownloading: Boolean, actions: RegionRowActions) {
    val context = LocalContext.current
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showLargeDownloadWarning by rememberSaveable { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes)

    val startDownload = {
        if (item.sizeBytes > LARGE_DOWNLOAD_WARNING_BYTES && isOnCellularNetwork(context)) {
            showLargeDownloadWarning = true
        } else {
            actions.onDownload(item.regionId)
        }
    }

    when (item.status) {
        RegionStatus.NOT_INSTALLED -> FilledTonalIconButton(onClick = startDownload, enabled = !isDownloading) {
            Icon(AppIcons.Download, contentDescription = stringResource(R.string.regions_download, item.displayName))
        }
        RegionStatus.UPDATE_AVAILABLE -> FilledTonalButton(onClick = startDownload, enabled = !isDownloading) {
            Text(stringResource(R.string.regions_update))
        }
        RegionStatus.INSTALLED -> IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.regions_delete, item.displayName))
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.regions_delete_title, item.displayName),
            message = stringResource(R.string.regions_delete_message),
            onConfirm = { showDeleteConfirm = false; actions.onDelete(item.regionId) },
            onDismiss = { showDeleteConfirm = false },
        )
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

@StringRes
private fun RegionStatus.label(): Int = when (this) {
    RegionStatus.NOT_INSTALLED -> R.string.regions_status_not_installed
    RegionStatus.INSTALLED -> R.string.regions_status_installed
    RegionStatus.UPDATE_AVAILABLE -> R.string.regions_status_update
}

// Ordine dei continenti come in pilot-regions.sh/assemble-site.sh; un continente sconosciuto va in
// coda, le regioni senza continente (manifest pubblicati prima del campo) nel gruppo "Altro".
private val CONTINENT_ORDER = listOf("Europa", "Asia", "Africa", "Nord America", "Sud America", "Oceania", "Territori disabitati")

internal fun groupByContinent(items: List<RegionUiItem>): List<Pair<String?, List<RegionUiItem>>> {
    val collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
    return items
        .groupBy { it.continent }
        .toList()
        .sortedBy { (continent, _) ->
            when (continent) {
                null -> Int.MAX_VALUE
                in CONTINENT_ORDER -> CONTINENT_ORDER.indexOf(continent)
                else -> CONTINENT_ORDER.size
            }
        }
        .map { (continent, regions) -> continent to regions.sortedWith(compareBy(collator) { it.displayName }) }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun RegionListPreview() {
    PocketTravelTheme(dynamicColor = false) {
        RegionListContent(
            uiState = RegionListUiState(
                isLoading = false,
                items = listOf(
                    RegionUiItem("san-marino", "San Marino", 76L shl 20, RegionStatus.INSTALLED, "Europa"),
                    RegionUiItem("italia", "Italia", 940L shl 20, RegionStatus.UPDATE_AVAILABLE, "Europa"),
                    RegionUiItem("andorra", "Andorra", 114L shl 20, RegionStatus.NOT_INSTALLED, "Europa"),
                    RegionUiItem("giappone", "Giappone", 566L shl 20, RegionStatus.NOT_INSTALLED, "Asia"),
                ),
            ),
            onQueryChange = {},
            onCheckUpdates = {},
            onRetry = {},
            onMessageShown = {},
            rowActions = RegionRowActions(observeProgress = { flowOf(null) }, onDownload = {}, onDelete = {}),
            onRegionClick = {},
        )
    }
}
