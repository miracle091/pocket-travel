package com.pockettravel.app.regions

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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalLocale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.pockettravel.app.R
import com.pockettravel.app.navigation.regionContainer
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ContextualHint
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.ai.AiAssistantScreen
import com.pockettravel.feature.ai.NavigationRequestProfile
import com.pockettravel.feature.guide.GuideScreen
import com.pockettravel.feature.map.MapRouteViewModel
import com.pockettravel.feature.map.MapScreen
import com.pockettravel.feature.map.MapSourceKind
import com.pockettravel.feature.map.NavigationPlace
import com.pockettravel.feature.map.RouteProfile
import com.pockettravel.core.ui.R as UiR

// Guida/Mappa/Navigatore/IA sono viste sorelle della stessa regione, senza bisogno di un proprio
// back-stack indipendente: la scheda selezionata e' stato locale (rememberSaveable), non un nested
// NavHost — un nested graph qui sarebbe un'astrazione non necessaria per quattro viste che condividono
// la stessa "torna alla lista regioni".
private const val TAB_CROSSFADE_MS = 250

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
    val hubState by viewModel.uiState.collectAsStateWithLifecycle()
    val displayName = hubState.displayName
    val splitCountry = hubState.splitCountry
    val regionMissing = hubState.regionMissing
    val mapState = hubState.mapState
    val aiAvailable = hubState.aiAvailable
    val uiLanguage = LocalLocale.current.platformLocale.language
    LaunchedEffect(uiLanguage) { viewModel.refreshAiAvailability() }
    // Scheda IA salvata (o richiesta con tab=ai) ma non disponibile, o diventata tale (modello o chiave
    // eliminati): si torna alla Guida. Per il disegno vale subito la scheda effettiva, senza un fotogramma
    // con la scheda IA; lo stato salvato lo corregge l'effetto qui sotto.
    val currentTab = if (selectedTab == RegionTab.AI && !aiAvailable) RegionTab.GUIDE else selectedTab
    LaunchedEffect(aiAvailable, selectedTab) { if (!aiAvailable && selectedTab == RegionTab.AI) selectedTab = RegionTab.GUIDE }
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    // Qui e non dentro la scheda: "Percorso" dalla Mappa le passa la destinazione prima di aprirla, e la notifica della
    // navigazione riporta in primo piano il Navigatore. Uno solo per l'app, come quello della barra principale.
    val (plannerViewModel, navigationViewModel) = navigatorViewModels()
    LaunchedEffect(navigationViewModel) {
        navigationViewModel.openNavigatorRequests.collect {
            if (navigationViewModel.isNavigating) selectedTab = RegionTab.NAVIGATION
        }
    }
    LaunchedEffect(regionMissing) { if (regionMissing) onBack() }
    // Il secondo tocco su Indietro arriva mentre l'hub sta uscendo: senza il controllo toglierebbe dalla pila anche
    // la schermata sotto. Vale per i tocchi, non per l'uscita automatica qui sopra (puo' partire durante l'ingresso).
    val onBackTap = dropUnlessResumed { onBack() }
    val tabStateHolder = rememberSaveableStateHolder()

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
            // La Guida non ha la barra del titolo: Indietro e' sulla riga dei filtri (GuideScreen).
            topBar = {
                if (currentTab != RegionTab.GUIDE) TopAppBar(
                    title = { Text(displayName ?: regionId) },
                    navigationIcon = {
                        IconButton(onClick = onBackTap) {
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
                // Motion M3 "fade through" tra le schede sorelle.
                // SaveableStateProvider: lasciando una scheda se ne conserva lo stato (la camera della Mappa).
                Crossfade(targetState = currentTab, animationSpec = tween(TAB_CROSSFADE_MS), label = "regionTab") { tab ->
                tabStateHolder.SaveableStateProvider(tab.key) {
                when (tab) {
                    RegionTab.GUIDE -> Box(modifier = Modifier.statusBarsPadding()) { GuideScreen(regionId = regionId, onOpenSource = onOpenSource, onBack = onBackTap) }
                    RegionTab.MAP -> {
                        // I pacchetti si installano separatamente: la mappa completa puo' mancare,
                        // ma la scheda mostra sempre qualcosa (mappa d'insieme, mappa del mondo, o solo lo
                        // sfondo) invece di una schermata vuota — vedi MapSourceKind.
                        val mapViewModel: MapRouteViewModel = hiltViewModel()
                        LaunchedEffect(regionId) { mapViewModel.loadPins(regionId) }
                        // La mappa completa e' appena finita di scaricare: MapRouteViewModel non se
                        // ne accorgerebbe da solo (non osserva il file system).
                        val mapScreen by mapViewModel.uiState.collectAsStateWithLifecycle()
                        val knownSource = mapScreen.mapSource
                        // Stato della mappa o sorgente non ancora noti: nessun contenuto finche' non arrivano
                        // (altrimenti la barra "Scarica la mappa" lampeggerebbe).
                        if (mapState != RegionMapState.LOADING && knownSource != null) {
                            val sourceKind = knownSource.kind
                            val language = LocalLocale.current.platformLocale.language
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    MapScreen(
                                        tileSource = mapViewModel.tileSource,
                                        regionId = regionId,
                                        mapSource = knownSource,
                                        pins = mapScreen.pins,
                                        hiddenCategories = mapScreen.hiddenCategories,
                                        // Categorie dell'area inquadrata, filtrate comprese: i segnalini non contengono quelle nascoste.
                                        areaCategories = mapScreen.presentCategories,
                                        onHiddenCategoriesChange = mapViewModel::setHiddenCategories,
                                        hideInaccessible = mapScreen.accessible,
                                        onlyAccessible = mapScreen.onlyAccessible,
                                        onOnlyAccessibleChange = mapViewModel::setOnlyAccessible,
                                        // Prima il percorso nella scheda Navigatore (partenza, modo, anteprima del percorso), poi "Avvia".
                                        onNavigate = { pin ->
                                            val name = pin.displayName(language) ?: pin.name.orEmpty()
                                            plannerViewModel.setDestination(NavigationPlace(name, pin.latitude, pin.longitude, regionId))
                                            selectedTab = RegionTab.NAVIGATION
                                        },
                                        transitPackage = hubState.transitState,
                                        transitBoard = mapScreen.transitBoard,
                                        transitUpdateResult = hubState.transitUpdateResult,
                                        onDownloadTransit = viewModel::downloadTransit,
                                        onTransitStopChange = mapViewModel::showDepartures,
                                        onViewportChange = mapViewModel::setViewport,
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
                    }
                    RegionTab.NAVIGATION -> RegionNavigatorScreen(regionId = regionId, plannerViewModel = plannerViewModel, navigationViewModel = navigationViewModel)
                    RegionTab.AI -> Column(modifier = Modifier.fillMaxSize()) {
                        ContextualHint(
                            hintId = "ai",
                            icon = AppIcons.AiAssistant,
                            title = stringResource(R.string.hint_ai_title),
                            body = stringResource(R.string.hint_ai_body),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            AiAssistantScreen(
                                regionId = regionId,
                                splitCountry = splitCountry,
                                onOpenOfficialSource = onOpenOfficialSource,
                                // "Portami a ...": il Navigatore cerca la destinazione (tutte le regioni installate) col modo chiesto.
                                onNavigationRequest = { request ->
                                    request.profile?.let { plannerViewModel.setRouteProfile(it.toRouteProfile()) }
                                    plannerViewModel.searchDestination(request.destination, request.destinationWithTime)
                                    selectedTab = RegionTab.NAVIGATION
                                },
                            )
                        }
                    }
                }
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
// regione, che gestisce gia' gli inset di sistema (con il padding resterebbe una fascia vuota).
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

private fun NavigationRequestProfile.toRouteProfile(): RouteProfile = when (this) {
    NavigationRequestProfile.WALK -> RouteProfile.WALK
    NavigationRequestProfile.BIKE -> RouteProfile.BIKE
    NavigationRequestProfile.CAR -> RouteProfile.CAR
}
