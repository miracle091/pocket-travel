package com.pockettravel.app.regions

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.FabPosition
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.app.navigation.regionContainer
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.IndeterminateDownloadProgressIndicator
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.text.Collator
import java.util.Locale

@Composable
fun RegionListScreen(
    onRegionClick: (String) -> Unit,
    onPreviewClick: (regionId: String, displayName: String) -> Unit,
    // false nel layout lista-dettaglio dei tablet: li' la mappa delle nazioni sta gia' nel pannello
    // di destra, il pulsante Elenco/Mappa non serve.
    showMapToggle: Boolean = true,
    viewModel: RegionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    LaunchedEffect(locale) { viewModel.setLocale(locale) }
    RegionListContent(
        showMapToggle = showMapToggle,
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::refresh,
        onMessageShown = viewModel::onMessageShown,
        rowActions = viewModel.rowActions(),
        downloadingRegions = uiState.downloadingRegions,
        onRegionClick = { item ->
            if (item.status == RegionStatus.NOT_INSTALLED) {
                onPreviewClick(item.regionId, item.displayName)
            } else {
                onRegionClick(item.regionId)
            }
        },
    )
}

// Tutte le azioni di una riga regione, dal ViewModel dell'elenco: le usano l'elenco, la mappa delle nazioni dei tablet e l'anteprima.
internal fun RegionListViewModel.rowActions() = RegionRowActions(
    observeProgress = ::observeDownloadProgress,
    onDownload = ::download,
    onDelete = ::delete,
    onDownloadPackage = ::downloadPackage,
    onDeletePackage = ::deletePackage,
    onTransitNetworkChange = ::setTransitNetwork,
    observeMapLight = ::observeMapLight,
    onMapLightChange = ::setMapLight,
    onZoneChange = ::setZone,
    regionsInZone = ::regionsInZone,
    isInstalledWhole = ::isInstalledWhole,
    observeRoutingChoice = ::observeRoutingChoice,
    onRoutingChoiceChange = ::setRoutingChoice,
)

// Azioni di una riga regione, raccolte per poter essere fornite dal ViewModel (schermata e step
// di onboarding) o da valori finti (@Preview). Data class: i riferimenti a metodi del ViewModel sono
// uguali tra ricomposizioni, cosi' un'istanza ricreata non impedisce di saltare le righe.
internal data class RegionRowActions(
    val observeProgress: (regionId: String) -> Flow<WorkInfo?>,
    val onDownload: (regionId: String) -> Unit,
    val onDelete: (regionId: String) -> Unit,
    val onDownloadPackage: (regionId: String, kind: PackageKind) -> Unit,
    val onDeletePackage: (regionId: String, kind: PackageKind) -> Unit,
    // Mezzi pubblici con piu' reti: aggiunge o toglie una rete (foglio Contenuti).
    val onTransitNetworkChange: (regionId: String, feedId: String, included: Boolean) -> Unit = { _, _, _ -> },
    // Mappa leggera (senza la z14): scelta dell'utente o, senza, com'e' la mappa installata (foglio Contenuti).
    val observeMapLight: (regionId: String) -> Flow<Boolean> = { flowOf(false) },
    val onMapLightChange: (regionId: String, light: Boolean) -> Unit = { _, _ -> },
    // Zona di una nazione grande (null = tutto); con download la regione non installata si scarica subito.
    val onZoneChange: (regionId: String, zone: RegionZone?, download: Boolean) -> Unit = { _, _, _ -> },
    // Regioni del catalogo dentro una zona (id, nome), dalla piu' presente: per le zone che prendono altre nazioni.
    val regionsInZone: suspend (zone: RegionZone) -> List<Pair<String, String>> = { emptyList() },
    // La regione e' installata per intero (senza zona): una zona scelta da un'altra nazione non deve ridurla.
    val isInstalledWhole: suspend (regionId: String) -> Boolean = { false },
    // Per quali modi e' la rete stradale (foglio Contenuti, sotto Rete stradale).
    val observeRoutingChoice: (regionId: String) -> Flow<RoutingVariantOption> = { flowOf(RoutingVariantOption.ALL) },
    val onRoutingChoiceChange: (regionId: String, choice: RoutingVariantOption) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RegionListContent(
    uiState: RegionListUiState,
    showMapToggle: Boolean = true,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onMessageShown: () -> Unit,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
    // Regioni in download o in coda: stanno gia' con le nazioni scaricate.
    downloadingRegions: Set<String> = emptySet(),
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

    // Senza barra in alto: la voce "Nazioni" della barra in basso dice gia' dove si e'. Mappa ed elenco si scambiano con
    // il pulsante in basso al centro. Dentro NavigationSuiteScaffold gli inset in basso li gestisce la barra: qui solo
    // quello della barra di stato.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (showMapToggle) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // La mappa mostra tutto il catalogo: una ricerca in corso filtrerebbe le nazioni.
                        if (!showMap) onQueryChange("")
                        showMap = !showMap
                    },
                    icon = { Icon(if (showMap) AppIcons.ListView else AppIcons.Map, contentDescription = null) },
                    text = { Text(stringResource(if (showMap) R.string.regions_show_list else R.string.regions_show_map)) },
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize().padding(innerPadding).windowInsetsPadding(WindowInsets.statusBars)
        if (showMap && showMapToggle) {
            RegionWorldMap(
                items = uiState.items,
                rowActions = rowActions,
                onRegionClick = onRegionClick,
                modifier = contentModifier,
            )
            return@Scaffold
        }
        Column(modifier = contentModifier) {
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

                else -> Column {
                    // Catalogo irraggiungibile ma nazioni installate: si aprono lo stesso, il catalogo torna con "Riprova".
                    if (uiState.loadError != null) OfflineCatalogNotice(onRetry)
                    RegionGroupedList(
                    items = uiState.items,
                    downloading = downloadingRegions,
                    checking = uiState.checking,
                    replaced = uiState.replaced,
                    searching = uiState.query.isNotBlank(),
                    rowActions = rowActions,
                    onRegionClick = onRegionClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineCatalogNotice(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s)) {
            Icon(AppIcons.OfflineWifi, contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(stringResource(R.string.regions_offline_installed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onRetry) { Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.regions_retry)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // Testo tenuto qui e non riletto da uiState.query: lo stato del ViewModel arriva in ritardo
    // (combine su Dispatchers.IO) e, usato come valore del campo, farebbe perdere caratteri e
    // riporterebbe il cursore all'inizio durante la scrittura.
    val textFieldState = rememberTextFieldState(query)
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { currentOnQueryChange(it) }
    }
    // Barra di ricerca M3 usata solo come campo: filtra l'elenco sotto mentre si scrive, senza
    // una vista di risultati espansa (nessun ExpandedDockedSearchBar).
    val searchBarState = rememberSearchBarState()
    SearchBar(
        state = searchBarState,
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = {},
                placeholder = { Text(stringResource(R.string.regions_search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = if (textFieldState.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { textFieldState.clearText() }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.regions_search_clear))
                        }
                    }
                } else {
                    null
                },
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun RegionGroupedList(
    items: List<RegionUiItem>,
    downloading: Set<String>,
    checking: CatalogCheck?,
    replaced: List<ReplacedRegionItem>,
    searching: Boolean,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
) {
    // Le regioni sostituite stanno in cima alle nazioni scaricate, anche se non ce ne sono altre.
    val groups = remember(items, replaced, downloading) {
        groupRegions(items, downloading).let { grouped ->
            if (replaced.isNotEmpty() && grouped.none { it.first == RegionGroup.Downloaded }) listOf(RegionGroup.Downloaded to emptyList<RegionUiItem>()) + grouped else grouped
        }
    }
    val listState = rememberLazyListState()
    // "Scegli" su una regione sostituita apre continente e nazione, poi scorre fino alla nazione.
    var scrollToKey by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ReplacedRegionItem?>(null) }
    val otherLabel = stringResource(R.string.continent_other)
    val downloadedLabel = stringResource(R.string.regions_downloaded)
    // Gruppi a scomparsa: "Nazioni scaricate" aperto di default, i continenti chiusi. Qui si salvano
    // solo i gruppi che l'utente ha invertito rispetto al default (anche alla rotazione). Durante
    // una ricerca sono tutti aperti, per vedere subito i risultati.
    // Lista immutabile sostituita a ogni modifica (mai mutata sul posto); ArrayList per il salvataggio.
    var toggled by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    val rowsByGroup = remember(groups, replaced, searching, toggled) {
        groups.associate { (group, regions) ->
            // Stessi nomi e stessi gruppi nelle nazioni scaricate e nei continenti; tra le scaricate le nazioni con
            // una regione da aggiornare vengono prima, e le loro regioni sono aperte di default.
            val downloaded = group == RegionGroup.Downloaded
            val entries = countryEntries(regions).let { all -> if (downloaded) all.sortedBy { !it.hasUpdate() } else all }
            val replacedRows = if (downloaded) replaced.map { ListRow.Replaced(it) } else emptyList()
            val prefix = if (downloaded) DOWNLOADED_COUNTRY_PREFIX else COUNTRY_PREFIX
            group.key to (entries to replacedRows + visibleRows(entries, prefix) { searching || (("$prefix$it" in toggled) != downloaded) })
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, bottom = Spacing.l),
    ) {
        groups.forEach { (group, _) ->
            val key = group.key
            val (entries, rows) = rowsByGroup.getValue(key)
            val expandedByDefault = group == RegionGroup.Downloaded
            val expanded = searching || ((key in toggled) != expandedByDefault)
            item(key = "header_$key") {
                ContinentHeader(
                    title = when (group) {
                        RegionGroup.Downloaded -> downloadedLabel
                        is RegionGroup.Continent -> group.name?.let { continentLabel(it) } ?: otherLabel
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
                // divisori, invece di una card per riga. Le nazioni divise in piu' regioni (es. gli stati
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
                                    checking = checking,
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
                                        val keys = listOf(RegionGroup.Continent(continent).key, "$COUNTRY_PREFIX${row.item.groupName}")
                                        toggled = ArrayList(toggled - keys.toSet() + keys)
                                        scrollToKey = "$COUNTRY_PREFIX${row.item.groupName}"
                                    },
                                    onDelete = { deleting = row.item },
                                )
                                is ListRow.CountryHeader -> CountryRow(
                                    country = row.country,
                                    expanded = row.expanded,
                                    enabled = !searching,
                                    onToggle = { toggled = ArrayList(if (row.key in toggled) toggled - row.key else toggled + row.key) },
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

// Regione installata che il catalogo ha diviso in regioni piu' piccole: si apre ancora (i dati sono
// sul dispositivo), non si aggiorna piu'; "Scegli" porta alle regioni che la sostituiscono.
@Composable
private fun ReplacedRegionRow(item: ReplacedRegionItem, onOpen: () -> Unit, onChoose: () -> Unit, onDelete: () -> Unit) {
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
private fun CountryRow(country: RegionListEntry.Country, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
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
private fun groupShape(index: Int, size: Int) = MaterialTheme.shapes.large.let { large ->
    when {
        size == 1 -> large
        index == 0 -> large.copy(bottomStart = ZeroCorner, bottomEnd = ZeroCorner)
        index == size - 1 -> large.copy(topStart = ZeroCorner, topEnd = ZeroCorner)
        else -> RoundedCornerShape(0)
    }
}

private val ZeroCorner = CornerSize(0)

private const val PERCENT_SCALE = 100

// Dimensioni di esempio dell'anteprima, in MiB (shift di 20 bit).
private const val MEBIBYTE_SHIFT = 20
private const val PREVIEW_SAN_MARINO_MB = 76L
private const val PREVIEW_ITALY_MB = 940L
private const val PREVIEW_ANDORRA_MB = 114L
private const val PREVIEW_JAPAN_MB = 566L

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
            trailingContent = { RegionActionButton(item = item, isDownloading = isDownloading, actions = actions) },
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
private fun RegionActionButton(item: RegionUiItem, isDownloading: Boolean, actions: RegionRowActions) {
    var showPackages by rememberSaveable { mutableStateOf(false) }
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

// Ordine dei continenti come in regions.sh/assemble-site.sh; un continente sconosciuto va in
// coda, le regioni senza continente (cataloghi pubblicati prima del campo) nel gruppo "Altro".
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

// Il catalogo usa i nomi italiani dei continenti come identificativi (anche nei tag delle release, vedi
// assemble-site.sh): qui il nome nella lingua dell'interfaccia, quello del catalogo se sconosciuto.
@Composable
private fun continentLabel(name: String): String = when (name) {
    "Europa" -> stringResource(R.string.continent_europe)
    "Asia" -> stringResource(R.string.continent_asia)
    "Africa" -> stringResource(R.string.continent_africa)
    "Nord America" -> stringResource(R.string.continent_north_america)
    "Sud America" -> stringResource(R.string.continent_south_america)
    "Oceania" -> stringResource(R.string.continent_oceania)
    else -> name
}

// Voce di un continente: una regione, oppure una nazione divisa in piu' regioni (stesso groupName).
internal sealed interface RegionListEntry {
    data class Single(val item: RegionUiItem) : RegionListEntry

    data class Country(val name: String, val countryCode: String?, val items: List<RegionUiItem>) : RegionListEntry
}

/**
 * Raccoglie in una voce nazione le regioni con lo stesso groupName, anche quando nell'elenco ce n'e' una sola
 * (le altre sono nell'altra sezione), nell'ordine per nome.
 */
internal fun countryEntries(regions: List<RegionUiItem>): List<RegionListEntry> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val byGroup = regions.mapNotNull { item -> item.groupName?.let { it to item } }.groupBy({ it.first }, { it.second })
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

// Righe visibili di un continente aperto: le nazioni chiuse mostrano solo la propria riga.
internal sealed interface ListRow {
    val key: String

    data class Region(val item: RegionUiItem, val inCountry: Boolean) : ListRow {
        override val key = item.regionId
    }

    data class Replaced(val item: ReplacedRegionItem) : ListRow {
        override val key = "replaced_" + item.regionId
    }

    // La stessa nazione puo' stare sia tra le scaricate sia nel suo continente: la chiave dice la sezione.
    data class CountryHeader(val country: RegionListEntry.Country, val expanded: Boolean, override val key: String = COUNTRY_PREFIX + country.name) : ListRow
}

internal const val COUNTRY_PREFIX = "country_"
internal const val DOWNLOADED_COUNTRY_PREFIX = "downloaded_country_"

private fun RegionListEntry.hasUpdate(): Boolean = when (this) {
    is RegionListEntry.Single -> item.status == RegionStatus.UPDATE_AVAILABLE
    is RegionListEntry.Country -> items.any { it.status == RegionStatus.UPDATE_AVAILABLE }
}

internal fun visibleRows(
    entries: List<RegionListEntry>,
    keyPrefix: String = COUNTRY_PREFIX,
    isExpanded: (String) -> Boolean,
): List<ListRow> = entries.flatMap { entry ->
    when (entry) {
        is RegionListEntry.Single -> listOf(ListRow.Region(entry.item, inCountry = false))
        is RegionListEntry.Country -> {
            val expanded = isExpanded(entry.name)
            listOf(ListRow.CountryHeader(entry, expanded, keyPrefix + entry.name)) +
                if (expanded) entry.items.map { ListRow.Region(it, inCountry = true) } else emptyList()
        }
    }
}

/** [downloading]: le regioni con un download in corso o in coda, che stanno gia' con le nazioni scaricate. */
internal fun groupRegions(items: List<RegionUiItem>, downloading: Set<String> = emptySet()): List<Pair<RegionGroup, List<RegionUiItem>>> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val byName = compareBy(collator) { item: RegionUiItem -> item.displayName }
    val (downloaded, others) = items.partition { it.status != RegionStatus.NOT_INSTALLED || it.regionId in downloading }
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
                    RegionUiItem("san-marino", "San Marino", PREVIEW_SAN_MARINO_MB shl MEBIBYTE_SHIFT, RegionStatus.INSTALLED, "Europa"),
                    RegionUiItem("italia", "Italia", PREVIEW_ITALY_MB shl MEBIBYTE_SHIFT, RegionStatus.UPDATE_AVAILABLE, "Europa"),
                    RegionUiItem("andorra", "Andorra", PREVIEW_ANDORRA_MB shl MEBIBYTE_SHIFT, RegionStatus.NOT_INSTALLED, "Europa"),
                    RegionUiItem("giappone", "Giappone", PREVIEW_JAPAN_MB shl MEBIBYTE_SHIFT, RegionStatus.NOT_INSTALLED, "Asia"),
                ),
            ),
            onQueryChange = {},
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
