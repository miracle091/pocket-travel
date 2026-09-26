package com.pockettravel.app.regions

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.app.navigation.regionContainer
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
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
    // false nel layout lista-dettaglio dei tablet: li' la mappa del mondo sta gia' nel pannello
    // di destra, il pulsante Elenco/Mappa non serve.
    showMapToggle: Boolean = true,
    viewModel: RegionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RegionListContent(
        showMapToggle = showMapToggle,
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onCheckUpdates = viewModel::checkForUpdatesNow,
        onRetry = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
        rowActions = RegionRowActions(
            observeProgress = viewModel::observeDownloadProgress,
            onDownload = viewModel::download,
            onDelete = viewModel::delete,
            onDownloadPackage = viewModel::downloadPackage,
            onDeletePackage = viewModel::deletePackage,
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
    val onDownloadPackage: (regionId: String, kind: PackageKind) -> Unit,
    val onDeletePackage: (regionId: String, kind: PackageKind) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RegionListContent(
    uiState: RegionListUiState,
    showMapToggle: Boolean = true,
    onQueryChange: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showMap by rememberSaveable { mutableStateOf(false) }
    val message = uiState.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    // Top app bar grande che si comprime scorrendo l'elenco.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.regions_title)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (showMapToggle) {
                        IconButton(
                            onClick = {
                                // La mappa mostra tutto il catalogo: una ricerca in corso filtrerebbe i paesi.
                                if (!showMap) onQueryChange("")
                                showMap = !showMap
                            },
                        ) {
                            Icon(
                                imageVector = if (showMap) AppIcons.ListView else AppIcons.Map,
                                contentDescription = stringResource(if (showMap) R.string.regions_show_list else R.string.regions_show_map),
                            )
                        }
                    }
                    IconButton(onClick = onCheckUpdates) {
                        Icon(imageVector = AppIcons.Refresh, contentDescription = stringResource(R.string.regions_check_updates))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (showMap && showMapToggle) {
            RegionWorldMap(
                items = uiState.items,
                rowActions = rowActions,
                onRegionClick = onRegionClick,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
            return@Scaffold
        }
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
                ) { PocketTravelLoadingIndicator() }

                uiState.loadError != null && uiState.items.isEmpty() -> EmptyState(
                    icon = AppIcons.OfflineWifi,
                    title = stringResource(uiState.loadError),
                    modifier = Modifier.fillMaxSize(),
                    action = { FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.regions_retry)) } },
                )

                uiState.items.isEmpty() && uiState.replaced.isEmpty() && uiState.query.isNotBlank() -> EmptyState(
                    icon = AppIcons.Search,
                    title = stringResource(R.string.regions_search_empty_title),
                    subtitle = stringResource(R.string.regions_search_empty_subtitle, uiState.query),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> RegionGroupedList(
                    items = uiState.items,
                    replaced = uiState.replaced,
                    searching = uiState.query.isNotBlank(),
                    rowActions = rowActions,
                    onRegionClick = onRegionClick,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // Testo tenuto qui e non riletto da uiState.query: lo stato del ViewModel arriva in ritardo
    // (combine su Dispatchers.IO) e, usato come valore del campo, faceva perdere caratteri e
    // riportava il cursore all'inizio mentre si scriveva.
    var text by rememberSaveable { mutableStateOf(query) }
    val onTextChange = { value: String ->
        text = value
        onQueryChange(value)
    }
    // Barra di ricerca M3 "docked" usata solo come campo: filtra l'elenco sotto mentre si scrive,
    // senza aprire una vista di risultati separata.
    DockedSearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = text,
                onQueryChange = onTextChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(stringResource(R.string.regions_search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = if (text.isNotEmpty()) {
                    {
                        IconButton(onClick = { onTextChange("") }) {
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
    replaced: List<ReplacedRegionItem>,
    searching: Boolean,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
) {
    // Le regioni sostituite stanno in cima alle nazioni scaricate, anche se non ce ne sono altre.
    val groups = groupRegions(items).let { grouped ->
        if (replaced.isNotEmpty() && grouped.none { it.first == RegionGroup.Downloaded }) listOf(RegionGroup.Downloaded to emptyList<RegionUiItem>()) + grouped else grouped
    }
    val listState = rememberLazyListState()
    // "Scegli" su una regione sostituita apre continente e paese, poi scorre fino al paese.
    var scrollToKey by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ReplacedRegionItem?>(null) }
    val otherLabel = stringResource(R.string.continent_other)
    val downloadedLabel = stringResource(R.string.regions_downloaded)
    // Gruppi a scomparsa: "Nazioni scaricate" aperto di default, i continenti chiusi. Qui si salvano
    // solo i gruppi che l'utente ha invertito rispetto al default (anche alla rotazione). Durante
    // una ricerca sono tutti aperti, per vedere subito i risultati.
    var toggled by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val rowsByGroup = groups.associate { (group, regions) ->
        val entries = if (group == RegionGroup.Downloaded) regions.map { RegionListEntry.Single(it) } else countryEntries(regions)
        val replacedRows = if (group == RegionGroup.Downloaded) replaced.map { ListRow.Replaced(it) } else emptyList()
        group.key to (entries to replacedRows + visibleRows(entries, isExpanded = { searching || "country_$it" in toggled }))
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
    ) {
        groups.forEach { (group, regions) ->
            val key = group.key
            // Nelle nazioni scaricate ogni regione resta una riga: sono poche e servono subito.
            val (entries, rows) = rowsByGroup.getValue(key)
            val expandedByDefault = group == RegionGroup.Downloaded
            val expanded = searching || ((key in toggled) != expandedByDefault)
            item(key = "header_$key") {
                ContinentHeader(
                    title = when (group) {
                        RegionGroup.Downloaded -> downloadedLabel
                        is RegionGroup.Continent -> group.name ?: otherLabel
                    },
                    count = entries.size + if (group == RegionGroup.Downloaded) replaced.size else 0,
                    expanded = expanded,
                    enabled = !searching,
                    onToggle = { toggled = ArrayList(if (key in toggled) toggled - key else toggled + key) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (expanded) {
                // Un gruppo per continente: righe su una superficie tonale arrotondata, separate da
                // divisori, invece di una card per riga. I paesi divisi in piu' regioni (es. gli stati
                // USA) sono una riga a scomparsa con le loro regioni sotto.
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    val shape = groupShape(index, rows.size)
                    // La riga di una regione e' il contenitore che si trasforma nell'hub quando la si apre.
                    val containerModifier = if (row is ListRow.Region) Modifier.regionContainer(row.item.regionId) else Modifier
                    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.animateItem().then(containerModifier)) {
                        Column {
                            when (row) {
                                is ListRow.Region -> RegionRow(
                                    item = row.item,
                                    actions = rowActions,
                                    onClick = { onRegionClick(row.item) },
                                    title = if (row.inCountry) row.item.groupLabel ?: row.item.displayName else row.item.displayName,
                                    modifier = if (row.inCountry) Modifier.padding(start = Spacing.xl) else Modifier,
                                )
                                is ListRow.Replaced -> ReplacedRegionRow(
                                    item = row.item,
                                    onOpen = {
                                        onRegionClick(
                                            RegionUiItem(row.item.regionId, row.item.displayName, row.item.sizeBytes, RegionStatus.INSTALLED, countryCode = row.item.countryCode),
                                        )
                                    },
                                    onChoose = {
                                        val continent = items.firstOrNull { it.groupName == row.item.groupName }?.continent
                                        val keys = listOf(RegionGroup.Continent(continent).key, "country_${row.item.groupName}")
                                        toggled = ArrayList(toggled - keys.toSet() + keys)
                                        scrollToKey = "country_${row.item.groupName}"
                                    },
                                    onDelete = { deleting = row.item },
                                )
                                is ListRow.CountryHeader -> CountryRow(
                                    country = row.country,
                                    expanded = row.expanded,
                                    enabled = !searching,
                                    onToggle = {
                                        val countryKey = "country_${row.country.name}"
                                        toggled = ArrayList(if (countryKey in toggled) toggled - countryKey else toggled + countryKey)
                                    },
                                )
                            }
                            if (index < rows.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                        }
                    }
                }
            }
        }
    }
    // Indice della riga cercata nell'ordine della LazyColumn: intestazione di ogni gruppo, poi le sue righe se aperto.
    LaunchedEffect(scrollToKey, rowsByGroup) {
        val target = scrollToKey ?: return@LaunchedEffect
        var index = 0
        for ((group, _) in groups) {
            index++
            val expanded = searching || ((group.key in toggled) != (group == RegionGroup.Downloaded))
            if (!expanded) continue
            val rows = rowsByGroup.getValue(group.key).second
            val found = rows.indexOfFirst { it.key == target }
            if (found >= 0) {
                listState.animateScrollToItem(index + found)
                scrollToKey = null
                return@LaunchedEffect
            }
            index += rows.size
        }
    }
    deleting?.let { item ->
        ConfirmationDialog(
            title = stringResource(R.string.regions_delete_title, item.displayName),
            message = stringResource(R.string.regions_delete_message),
            onConfirm = { deleting = null; rowActions.onDelete(item.regionId) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ContinentHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val stateText = stringResource(if (expanded) R.string.continent_expanded else R.string.continent_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.continent_collapse else R.string.continent_expand)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.s)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.l)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = stateText
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(R.plurals.continent_count, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            Icon(
                imageVector = AppIcons.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = Spacing.s).rotate(rotation),
            )
        }
    }
}

// Regione installata che il manifest ha diviso in regioni piu' piccole: si apre ancora (i dati sono
// sul dispositivo), non si aggiorna piu'; "Scegli" porta alle regioni che la sostituiscono.
@Composable
private fun ReplacedRegionRow(item: ReplacedRegionItem, onOpen: () -> Unit, onChoose: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onOpen)) {
        ListItem(
            headlineContent = { Text(item.displayName) },
            supportingContent = { Text(stringResource(R.string.regions_replaced)) },
            leadingContent = {
                CountryFlag(item.countryCode, size = 40.dp) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(AppIcons.WorldFilled, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(Spacing.s))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Row(modifier = Modifier.padding(start = 72.dp, end = Spacing.s, bottom = Spacing.s)) {
            FilledTonalButton(onClick = onChoose) { Text(stringResource(R.string.regions_replaced_choose)) }
            Spacer(modifier = Modifier.width(Spacing.s))
            TextButton(onClick = onDelete) { Text(stringResource(R.string.regions_replaced_delete)) }
        }
    }
}

// Riga di un paese diviso in piu' regioni: bandiera, nome, quante regioni, freccia per aprirla.
@Composable
private fun CountryRow(country: RegionListEntry.Country, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "countryChevron")
    val stateText = stringResource(if (expanded) R.string.continent_expanded else R.string.continent_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.continent_collapse else R.string.continent_expand)
    ListItem(
        headlineContent = { Text(country.name) },
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
    )
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
internal fun RegionRow(
    item: RegionUiItem,
    actions: RegionRowActions,
    onClick: () -> Unit,
    title: String = item.displayName,
    modifier: Modifier = Modifier,
) {
    val workInfo by remember(item.regionId) { actions.observeProgress(item.regionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val isDownloading = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val progress = workInfo?.progress?.let { data ->
        val total = data.getLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 0L)
        if (total > 0) data.getLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 0L) / total.toFloat() else 0f
    } ?: 0f
    val size = Formatter.formatShortFileSize(LocalContext.current, item.sizeBytes)
    val installed = item.status != RegionStatus.NOT_INSTALLED

    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(
                    if (isDownloading) {
                        stringResource(R.string.regions_status_downloading, (progress * 100).toInt())
                    } else {
                        stringResource(item.statusLabel(), size)
                    },
                )
            },
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
            trailingContent = { RegionActionButton(item = item, isDownloading = isDownloading, actions = actions) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick),
        )
        if (isDownloading) {
            DownloadProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m),
            )
        }
    }
}

@Composable
private fun RegionActionButton(item: RegionUiItem, isDownloading: Boolean, actions: RegionRowActions) {
    val context = LocalContext.current
    var showPackages by rememberSaveable { mutableStateOf(false) }
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
        RegionStatus.UPDATE_AVAILABLE, RegionStatus.INSTALLED -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.status == RegionStatus.UPDATE_AVAILABLE) {
                // Con il testo molto grande il pulsante con l'etichetta schiaccerebbe il nome della
                // regione fino a spezzarlo lettera per lettera: solo icona, come "Scarica".
                if (LocalDensity.current.fontScale >= 1.5f) {
                    FilledTonalIconButton(onClick = startDownload, enabled = !isDownloading) {
                        Icon(AppIcons.Download, contentDescription = stringResource(R.string.regions_update_region, item.displayName))
                    }
                } else {
                    FilledTonalButton(onClick = startDownload, enabled = !isDownloading) {
                        Text(stringResource(R.string.regions_update))
                    }
                }
            }
            // Mappa, percorsi e POI uno per uno, ed "Elimina tutto": vedi RegionPackagesSheet.
            IconButton(onClick = { showPackages = true }) {
                Icon(AppIcons.MoreVert, contentDescription = stringResource(R.string.regions_packages, item.displayName))
            }
        }
    }

    if (showPackages) {
        RegionPackagesSheet(item = item, isDownloading = isDownloading, actions = actions, onDismiss = { showPackages = false })
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
private fun RegionUiItem.statusLabel(): Int = when (status) {
    RegionStatus.NOT_INSTALLED -> R.string.regions_status_not_installed
    // I POI extra si scaricano solo su richiesta: senza, la regione e' comunque completa.
    RegionStatus.INSTALLED ->
        if (packages.any { it.status == RegionStatus.NOT_INSTALLED && it.kind != PackageKind.POI_EXTRA }) {
            R.string.regions_status_partial
        } else {
            R.string.regions_status_installed
        }
    RegionStatus.UPDATE_AVAILABLE -> R.string.regions_status_update
}

// Ordine dei continenti come in pilot-regions.sh/assemble-site.sh; un continente sconosciuto va in
// coda, le regioni senza continente (manifest pubblicati prima del campo) nel gruppo "Altro".
private val CONTINENT_ORDER = listOf("Europa", "Asia", "Africa", "Nord America", "Sud America", "Oceania")

// Gruppo dell'elenco regioni: prima "Nazioni scaricate" (scaricate o da aggiornare), poi i continenti
// con le sole nazioni non ancora scaricate.
internal sealed interface RegionGroup {
    val key: String

    data object Downloaded : RegionGroup {
        override val key = "downloaded"
    }

    data class Continent(val name: String?) : RegionGroup {
        override val key = "continent_" + (name ?: "")
    }
}

// Voce di un continente: una regione, oppure un paese diviso in piu' regioni (stesso groupName).
internal sealed interface RegionListEntry {
    data class Single(val item: RegionUiItem) : RegionListEntry

    data class Country(val name: String, val countryCode: String?, val items: List<RegionUiItem>) : RegionListEntry
}

/** Raccoglie in una voce paese le regioni con lo stesso groupName (se sono piu' d'una), nell'ordine per nome. */
internal fun countryEntries(regions: List<RegionUiItem>): List<RegionListEntry> {
    val collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
    val byGroup = regions.filter { it.groupName != null }.groupBy { it.groupName!! }.filterValues { it.size > 1 }
    val entries = regions.filter { it.groupName !in byGroup }.map<RegionUiItem, RegionListEntry> { RegionListEntry.Single(it) } +
        byGroup.map { (name, items) ->
            RegionListEntry.Country(name, items.first().countryCode, items.sortedWith(compareBy(collator) { it.groupLabel ?: it.displayName }))
        }
    return entries.sortedWith(
        compareBy(collator) { entry ->
            when (entry) {
                is RegionListEntry.Single -> entry.item.displayName
                is RegionListEntry.Country -> entry.name
            }
        },
    )
}

// Righe visibili di un continente aperto: i paesi chiusi mostrano solo la propria riga.
internal sealed interface ListRow {
    val key: String

    data class Region(val item: RegionUiItem, val inCountry: Boolean) : ListRow {
        override val key = item.regionId
    }

    data class Replaced(val item: ReplacedRegionItem) : ListRow {
        override val key = "replaced_" + item.regionId
    }

    data class CountryHeader(val country: RegionListEntry.Country, val expanded: Boolean) : ListRow {
        override val key = "country_" + country.name
    }
}

internal fun visibleRows(entries: List<RegionListEntry>, isExpanded: (String) -> Boolean): List<ListRow> = entries.flatMap { entry ->
    when (entry) {
        is RegionListEntry.Single -> listOf(ListRow.Region(entry.item, inCountry = false))
        is RegionListEntry.Country -> {
            val expanded = isExpanded(entry.name)
            listOf(ListRow.CountryHeader(entry, expanded)) + if (expanded) entry.items.map { ListRow.Region(it, inCountry = true) } else emptyList()
        }
    }
}

internal fun groupRegions(items: List<RegionUiItem>): List<Pair<RegionGroup, List<RegionUiItem>>> {
    val collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.PRIMARY }
    val byName = compareBy(collator) { item: RegionUiItem -> item.displayName }
    val (downloaded, others) = items.partition { it.status != RegionStatus.NOT_INSTALLED }
    val downloadedGroup = downloaded
        // Da aggiornare prima delle gia' aggiornate, poi per nome.
        .sortedWith(compareBy<RegionUiItem> { it.status != RegionStatus.UPDATE_AVAILABLE }.then(byName))
        .takeIf { it.isNotEmpty() }
        ?.let { listOf(RegionGroup.Downloaded to it) }
        .orEmpty()
    val continentGroups = others
        .groupBy { it.continent }
        .toList()
        .sortedBy { (continent, _) ->
            when (continent) {
                null -> Int.MAX_VALUE
                in CONTINENT_ORDER -> CONTINENT_ORDER.indexOf(continent)
                else -> CONTINENT_ORDER.size
            }
        }
        .map { (continent, regions) -> RegionGroup.Continent(continent) to regions.sortedWith(byName) }
    return downloadedGroup + continentGroups
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
            rowActions = RegionRowActions(
                observeProgress = { flowOf(null) },
                onDownload = {},
                onDelete = {},
                onDownloadPackage = { _, _ -> },
                onDeletePackage = { _, _ -> },
            ),
            onRegionClick = {},
        )
    }
}
