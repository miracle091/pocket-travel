package com.pockettravel.app.regions

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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

// Dimensioni di esempio dell'anteprima, in MiB (shift di 20 bit).
private const val MEBIBYTE_SHIFT = 20
private const val PREVIEW_SAN_MARINO_MB = 76L
private const val PREVIEW_ITALY_MB = 940L
private const val PREVIEW_ANDORRA_MB = 114L
private const val PREVIEW_JAPAN_MB = 566L

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
