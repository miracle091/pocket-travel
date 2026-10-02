package com.pockettravel.app.regions

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalLocale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.navigation.regionContainer
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.ai.AiAssistantScreen
import com.pockettravel.feature.ai.NavigationRequestMode
import com.pockettravel.feature.guide.GuideScreen
import com.pockettravel.feature.map.MapRouteViewModel
import com.pockettravel.feature.map.MapScreen
import com.pockettravel.feature.map.MapSourceKind
import com.pockettravel.feature.map.NavigationPlace
import com.pockettravel.feature.map.TravelMode
import com.pockettravel.core.ui.R as UiR

private enum class RegionTab(val key: String, @StringRes val label: Int) {
    GUIDE("guide", R.string.nav_guide),
    MAP("map", R.string.nav_map),
    NAVIGATION("navigation", R.string.nav_navigation),
    AI("ai", R.string.nav_assistant),
    ;

    companion object {
        fun fromKey(key: String): RegionTab = entries.firstOrNull { it.key == key } ?: GUIDE
    }
}

// Guida/Mappa/Navigatore/IA sono viste sorelle della stessa regione, senza bisogno di un proprio
// back-stack indipendente: il tab selezionato e' stato locale (rememberSaveable), non un nested
// NavHost — un nested graph qui sarebbe un'astrazione non necessaria per quattro viste che condividono
// la stessa "torna alla lista regioni".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionHubScreen(
    regionId: String,
    initialTab: String = "guide",
    onBack: () -> Unit,
    onOpenOfficialSource: (url: String) -> Unit = {},
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    // true quando l'hub e' il pannello di dettaglio accanto all'elenco regioni (schermi larghi):
    // barra in basso invece della rail, che finirebbe in mezzo allo schermo.
    compactNavigation: Boolean = false,
    viewModel: RegionHubViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable(regionId) { mutableStateOf(RegionTab.fromKey(initialTab)) }
    val displayName by viewModel.displayName.collectAsStateWithLifecycle()
    val regionMissing by viewModel.regionMissing.collectAsStateWithLifecycle()
    val mapState by viewModel.mapState.collectAsStateWithLifecycle()
    val aiAvailable by viewModel.aiAvailable.collectAsStateWithLifecycle()
    val uiLanguage = LocalLocale.current.platformLocale.language
    LaunchedEffect(uiLanguage) { viewModel.refreshAiAvailability() }
    // Tab IA salvata (o richiesta con tab=ai) ma non disponibile, o diventata tale (modello o chiave
    // eliminati): si torna alla Guida. Per il disegno vale subito la tab effettiva, senza un fotogramma
    // con la tab IA; lo stato salvato lo corregge l'effetto qui sotto.
    val currentTab = if (selectedTab == RegionTab.AI && !aiAvailable) RegionTab.GUIDE else selectedTab
    LaunchedEffect(aiAvailable, selectedTab) { if (!aiAvailable && selectedTab == RegionTab.AI) selectedTab = RegionTab.GUIDE }
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    // Qui e non dentro la tab: "Indicazioni" dalla Mappa le passa la destinazione prima di aprirla, e la notifica della
    // guida riporta in primo piano il Navigatore. Uno solo per l'app, come quello della barra principale.
    val (plannerViewModel, navigationViewModel) = navigatorViewModels()
    LaunchedEffect(navigationViewModel) {
        navigationViewModel.openNavigatorRequests.collect {
            if (navigationViewModel.target.value != null) selectedTab = RegionTab.NAVIGATION
        }
    }
    LaunchedEffect(regionMissing) { if (regionMissing) onBack() }

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    NavigationSuiteScaffold(
        modifier = Modifier.regionContainer(regionId),
        navigationSuiteItems = {
            RegionTab.entries.filter { it != RegionTab.AI || aiAvailable }.forEach { tab ->
                val selected = tab == currentTab
                item(
                    selected = selected,
                    onClick = { selectedTab = tab },
                    icon = { Icon(imageVector = tab.icon(selected), contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
        layoutType = if (compactNavigation) {
            NavigationSuiteType.NavigationBar
        } else {
            NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
        },
    ) {
        // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
        // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = { Text(displayName ?: regionId) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            // imePadding: con l'edge-to-edge la tastiera non ridimensiona piu' la finestra, il campo
            // di testo dell'assistente deve restare sopra la tastiera da solo.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                // Motion M3 "fade through" tra le tab sorelle.
                Crossfade(targetState = currentTab, animationSpec = tween(250), label = "regionTab") { tab ->
                when (tab) {
                    RegionTab.GUIDE -> GuideScreen(regionId = regionId, onOpenSource = onOpenSource)
                    RegionTab.MAP -> if (mapState == RegionMapState.LOADING) {
                        // Stato della mappa non ancora noto: nessun contenuto finche' non arriva.
                    } else {
                        // I pacchetti si installano separatamente: la mappa completa puo' mancare,
                        // ma la scheda mostra sempre qualcosa (anteprima, mondo online, o solo lo
                        // sfondo) invece di una schermata vuota — vedi MapSourceKind.
                        val mapViewModel: MapRouteViewModel = hiltViewModel()
                        LaunchedEffect(regionId) { mapViewModel.loadPins(regionId) }
                        // La mappa completa e' appena finita di scaricare: MapRouteViewModel non se
                        // ne accorgerebbe da solo (non osserva il file system).
                        val pins by mapViewModel.pins.collectAsStateWithLifecycle()
                        val hiddenCategories by mapViewModel.hiddenCategories.collectAsStateWithLifecycle()
                        val accessible by mapViewModel.accessible.collectAsStateWithLifecycle()
                        val onlyAccessible by mapViewModel.onlyAccessible.collectAsStateWithLifecycle()
                        val mapSource by mapViewModel.mapSource.collectAsStateWithLifecycle()
                        val transitState by viewModel.transitState.collectAsStateWithLifecycle()
                        val transitBoard by mapViewModel.transitBoard.collectAsStateWithLifecycle()
                        val sourceKind = mapSource.kind
                        val language = LocalLocale.current.platformLocale.language
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                MapScreen(
                                    tileSource = mapViewModel.tileSource,
                                    regionId = regionId,
                                    mapSource = mapSource,
                                    pins = pins,
                                    hiddenCategories = hiddenCategories,
                                    onHiddenCategoriesChange = mapViewModel::setHiddenCategories,
                                    hideInaccessible = accessible,
                                    onlyAccessible = onlyAccessible,
                                    onOnlyAccessibleChange = mapViewModel::setOnlyAccessible,
                                    // Prima il percorso nella tab Navigatore (partenza, mezzo, anteprima), poi "Avvia".
                                    onNavigate = { pin ->
                                        val name = pin.displayName(language) ?: pin.name.orEmpty()
                                        plannerViewModel.setDestination(NavigationPlace(name, pin.latitude, pin.longitude, regionId))
                                        selectedTab = RegionTab.NAVIGATION
                                    },
                                    transitPackage = transitState,
                                    transitBoard = transitBoard,
                                    onDownloadTransit = viewModel::downloadTransit,
                                    onTransitStopChange = mapViewModel::showDepartures,
                                )
                            }
                            if (sourceKind != MapSourceKind.FULL) {
                                MapDownloadBar(
                                    sourceKind = sourceKind,
                                    downloading = mapState == RegionMapState.DOWNLOADING,
                                    onDownload = viewModel::downloadMap,
                                )
                            }
                        }
                    }
                    RegionTab.NAVIGATION -> NavigatorScreen(regionId = regionId, plannerViewModel = plannerViewModel, navigationViewModel = navigationViewModel)
                    RegionTab.AI -> AiAssistantScreen(
                        regionId = regionId,
                        onOpenOfficialSource = onOpenOfficialSource,
                        // "Portami a ...": il Navigatore cerca la meta (tutte le regioni installate) col mezzo chiesto.
                        onNavigationRequest = { request ->
                            request.mode?.let { plannerViewModel.setTravelMode(it.toTravelMode()) }
                            plannerViewModel.searchDestination(request.destination)
                            selectedTab = RegionTab.NAVIGATION
                        },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun RegionTab.icon(selected: Boolean): ImageVector = when (this) {
    RegionTab.GUIDE -> if (selected) AppIcons.WorldFilled else AppIcons.World
    RegionTab.MAP -> if (selected) AppIcons.MapFilled else AppIcons.Map
    RegionTab.NAVIGATION -> ImageVector.vectorResource(UiR.drawable.ms_directions)
    RegionTab.AI -> if (selected) AppIcons.AiAssistantFilled else AppIcons.AiAssistant
}

// Sotto la mappa, non sopra: cosi' non copre mai il pulsante dei filtri
// (Modifier.align in MapScreen li posiziona dentro il suo stesso Box, che qui occupa lo spazio
// restante sopra la barra). Niente navigationBarsPadding: la barra sta sopra la navigazione della
// regione, che gestisce gia' gli inset di sistema (con il padding restava una fascia vuota).
@Composable
private fun MapDownloadBar(sourceKind: MapSourceKind, downloading: Boolean, onDownload: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = Spacing.xs,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(
                text = stringResource(
                    if (sourceKind == MapSourceKind.NONE) R.string.map_missing_notice else R.string.map_preview_notice,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (downloading) {
                PocketTravelLoadingIndicator()
            } else {
                FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.map_download)) }
            }
        }
    }
}

private fun NavigationRequestMode.toTravelMode(): TravelMode = when (this) {
    NavigationRequestMode.WALK -> TravelMode.WALK
    NavigationRequestMode.BIKE -> TravelMode.BIKE
    NavigationRequestMode.CAR -> TravelMode.CAR
}
