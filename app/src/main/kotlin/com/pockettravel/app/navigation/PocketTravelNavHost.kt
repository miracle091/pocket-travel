package com.pockettravel.app.navigation

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import com.pockettravel.app.R
import com.pockettravel.app.browser.InAppBrowserScreen
import com.pockettravel.app.licenses.LicensesScreen
import com.pockettravel.app.settings.SettingsScreen
import com.pockettravel.app.settings.SettingsViewModel
import com.pockettravel.app.more.MoreScreen
import com.pockettravel.app.onboarding.OnboardingScreen
import com.pockettravel.app.onboarding.OnboardingViewModel
import com.pockettravel.app.regions.GlobalNavigatorScreen
import com.pockettravel.app.regions.RegionHubScreen
import com.pockettravel.app.regions.RegionListScreen
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionPreviewScreen
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionWorldMap
import com.pockettravel.app.regions.navigatorViewModels
import com.pockettravel.app.regions.rowActions
import com.pockettravel.app.storage.StorageScreen
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ContextualHint
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.feature.sources.OfficialSourcesScreen
import com.pockettravel.feature.vault.DocumentsScreen

// Le destinazioni principali della barra/rail di navigazione (M3: il menu laterale modale e'
// sconsigliato, sostituito dalla navigation suite). La barra compare solo su queste: le
// schermate di dettaglio (hub regione, fonti, licenze...) occupano tutto lo spazio.
private enum class TopLevelDestination(val route: Any, @StringRes val label: Int) {
    REGIONS(RegionsRoute, R.string.nav_regions),
    NAVIGATOR(NavigatorRoute, R.string.nav_navigator),
    VAULT(VaultRoute, R.string.nav_documents),
    MORE(MoreRoute, R.string.nav_more),
}

@Composable
private fun TopLevelDestination.icon(selected: Boolean): ImageVector = when (this) {
    TopLevelDestination.REGIONS -> if (selected) AppIcons.WorldFilled else AppIcons.World
    TopLevelDestination.NAVIGATOR -> ImageVector.vectorResource(UiR.drawable.ms_directions)
    TopLevelDestination.VAULT -> if (selected) AppIcons.DocumentsFilled else AppIcons.Documents
    TopLevelDestination.MORE -> if (selected) AppIcons.MoreFilled else AppIcons.More
}

// Un solo NavHost per l'intera app (attivita' singola, vedi AndroidManifest). Lo start destination si
// decide una volta sola in modo sincrono (StartDestinationViewModel), non reattivamente: una volta
// scelta, la rotta iniziale non cambia piu' per la vita del NavHost.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PocketTravelNavHost(
    // Activity creata dal tocco sulla notifica della guida (non gia' aperta): parte dal Navigatore.
    openNavigatorOnStart: Boolean = false,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    startDestinationViewModel: StartDestinationViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val startDestination = remember { startDestinationViewModel.startDestination }
    val initialRegionId = remember { startDestinationViewModel.initialRegionId }
    var initialRegionOpened by rememberSaveable { mutableStateOf(false) }

    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val currentTopLevel = TopLevelDestination.entries.firstOrNull { currentDestination?.hasRoute(it.route::class) == true }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // Notifica della guida toccata con l'app aperta: fuori dall'hub di una regione (che passa da solo alla sua tab) si
    // apre il Navigatore della barra, che mostra la stessa guida.
    val (_, navigationViewModel) = navigatorViewModels()
    LaunchedEffect(navigationViewModel) {
        navigationViewModel.openNavigatorRequests.collect {
            if (navigationViewModel.target.value != null && navController.currentDestination?.hasRoute<RegionHubRoute>() != true) {
                navController.navigateTopLevel(NavigatorRoute)
            }
        }
    }

    // App gia' in uso: riparte dalla mappa dell'ultima regione, impilata sopra l'elenco regioni
    // (una volta sola, non dopo una ricreazione dell'activity: il back stack e' gia' ripristinato).
    LaunchedEffect(Unit) {
        if (!initialRegionOpened && initialRegionId != null) {
            initialRegionOpened = true
            navController.navigate(RegionHubRoute(initialRegionId, tab = "map"))
        }
    }

    // Dopo la regione iniziale, che altrimenti resterebbe in cima: i collector di openNavigatorRequests non c'erano ancora
    // quando l'activity ha letto l'intent, quindi qui si naviga direttamente (solo se una guida e' in corso).
    LaunchedEffect(Unit) {
        if (openNavigatorOnStart && startDestination != OnboardingRoute && navigationViewModel.target.value != null) {
            navController.navigateTopLevel(NavigatorRoute)
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == currentTopLevel
                item(
                    selected = selected,
                    onClick = { navController.navigateTopLevel(destination.route) },
                    icon = { Icon(imageVector = destination.icon(selected), contentDescription = null) },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
        layoutType = if (currentTopLevel != null) {
            NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
        } else {
            NavigationSuiteType.None
        },
    ) {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // Motion M3: "shared axis" orizzontale tra una schermata e il suo dettaglio (avanti da
            // destra, indietro verso destra, anche durante il gesto di back predittivo).
            enterTransition = { sharedAxisEnter(forward = true) },
            exitTransition = { sharedAxisExit(forward = true) },
            popEnterTransition = { sharedAxisEnter(forward = false) },
            popExitTransition = { sharedAxisExit(forward = false) },
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = {
                        navController.navigate(RegionsRoute) {
                            popUpTo(OnboardingRoute) { inclusive = true }
                        }
                    },
                )
            }
            composable<RegionsRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                val onPreviewClick = { regionId: String, displayName: String ->
                    navController.navigate(RegionPreviewRoute(regionId, displayName))
                }
                if (isExpanded) {
                    RegionsListDetail(navController = navController, onPreviewClick = onPreviewClick)
                } else {
                    RegionContainerTransformScope(this) {
                        RegionListScreen(
                            onRegionClick = { regionId -> navController.navigate(RegionHubRoute(regionId)) },
                            onPreviewClick = onPreviewClick,
                        )
                    }
                }
            }
            composable<NavigatorRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                GlobalNavigatorScreen(onOpenCountries = { navController.navigateTopLevel(RegionsRoute) })
            }
            composable<VaultRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                // Il suggerimento sta sotto la barra di stato (inset consumato, DocumentsScreen non lo ripete).
                Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                    ContextualHint(
                        hintId = "vault",
                        icon = AppIcons.Passport,
                        title = stringResource(R.string.hint_vault_title),
                        body = stringResource(R.string.hint_vault_body),
                    )
                    Box(modifier = Modifier.weight(1f)) { DocumentsScreen() }
                }
            }
            composable<MoreRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                MoreScreen(
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenSources = { navController.navigate(SourcesRoute) },
                    onOpenStorage = { navController.navigate(StorageRoute) },
                    onOpenTutorial = { navController.navigate(TutorialRoute) },
                    onOpenLicenses = { navController.navigate(LicensesRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable<TutorialRoute> {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = { navController.popBackStack() },
                )
            }
            composable<StorageRoute> {
                StorageScreen(onBack = { navController.popBackStack() })
            }
            composable<SourcesRoute> {
                // Stesse preferenze delle Impostazioni: la nazionalita' sceglie le fonti del proprio paese.
                val nationality by hiltViewModel<SettingsViewModel>().nationality.collectAsStateWithLifecycle()
                Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                    Box(modifier = Modifier.weight(1f)) {
                        OfficialSourcesScreen(
                            nationality = nationality,
                            onBack = { navController.popBackStack() },
                            onOpenSource = { url, title -> navController.navigate(InAppBrowserRoute(url, title)) },
                        )
                    }
                    ContextualHint(
                        hintId = "sources",
                        icon = AppIcons.OfficialAuthority,
                        title = stringResource(R.string.hint_sources_title),
                        body = stringResource(R.string.hint_sources_body),
                    )
                }
            }
            composable<LicensesRoute> {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
            composable<RegionHubRoute>(
                // Dall'elenco regioni la riga si trasforma nell'hub (container transform): la
                // schermata sotto sfuma invece di scorrere, per non spostare la riga che si allarga.
                enterTransition = { if (initialState.destination.hasRoute<RegionsRoute>()) containerFadeIn() else sharedAxisEnter(forward = true) },
                popExitTransition = { if (targetState.destination.hasRoute<RegionsRoute>()) containerFadeOut() else sharedAxisExit(forward = false) },
            ) { backStackEntry ->
                val route = backStackEntry.toRoute<RegionHubRoute>()
                RegionContainerTransformScope(this) {
                    RegionHubScreen(
                        regionId = route.regionId,
                        initialTab = route.tab,
                        onBack = { if (!navController.popBackStack()) navController.navigate(RegionsRoute) },
                        onOpenOfficialSource = { url -> navController.navigateToOfficialSource(url) },
                        onOpenSource = { url, title -> navController.navigate(InAppBrowserRoute(url, title)) },
                    )
                }
            }
            composable<RegionPreviewRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<RegionPreviewRoute>()
                // Lo stesso ViewModel dell'elenco (sta nella voce RegionsRoute sotto): il download parte dal suo percorso.
                val listViewModel: RegionListViewModel = hiltViewModel(remember(backStackEntry) { navController.getBackStackEntry(RegionsRoute) })
                val listState by listViewModel.uiState.collectAsStateWithLifecycle()
                RegionPreviewScreen(
                    regionId = route.regionId,
                    displayName = route.name.ifBlank { route.regionId },
                    onBack = { navController.popBackStack() },
                    item = listState.items.firstOrNull { it.regionId == route.regionId },
                    rowActions = listViewModel.rowActions(),
                    // Il download completo si segue dalla lista Regioni (barra di avanzamento gia'
                    // presente li'), non duplicata qui: torna semplicemente indietro.
                    onDownloadFull = { navController.popBackStack() },
                    onOpenSource = { url, title -> navController.navigate(InAppBrowserRoute(url, title)) },
                )
            }
            composable<InAppBrowserRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<InAppBrowserRoute>()
                InAppBrowserScreen(url = route.url, title = route.title, onBack = { navController.popBackStack() })
            }
        }
        }
        }
    }
}

// Schermi larghi (>= 840 dp): elenco regioni e hub della regione scelta affiancati, invece di
// un elenco a tutta larghezza che apre l'hub a schermo intero.
@Composable
private fun RegionsListDetail(
    navController: NavHostController,
    onPreviewClick: (regionId: String, displayName: String) -> Unit,
    regionListViewModel: RegionListViewModel = hiltViewModel(),
) {
    var selectedRegionId by rememberSaveable { mutableStateOf<String?>(null) }
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.width(400.dp).fillMaxHeight()) {
            RegionListScreen(onRegionClick = { selectedRegionId = it }, onPreviewClick = onPreviewClick, showMapToggle = false)
        }
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val regionId = selectedRegionId
            if (regionId == null) {
                // Nessuna regione scelta: il pannello di destra mostra la mappa del mondo, dove
                // toccare un paese equivale a sceglierlo dall'elenco.
                val uiState by regionListViewModel.uiState.collectAsStateWithLifecycle()
                RegionWorldMap(
                    items = uiState.items,
                    rowActions = regionListViewModel.rowActions(),
                    onRegionClick = { item ->
                        if (item.status == RegionStatus.NOT_INSTALLED) {
                            onPreviewClick(item.regionId, item.displayName)
                        } else {
                            selectedRegionId = item.regionId
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                key(regionId) {
                    RegionHubScreen(
                        regionId = regionId,
                        onBack = { selectedRegionId = null },
                        onOpenOfficialSource = { url -> navController.navigateToOfficialSource(url) },
                        onOpenSource = { url, title -> navController.navigate(InAppBrowserRoute(url, title)) },
                        compactNavigation = true,
                    )
                }
            }
        }
    }
}

// Cambio di destinazione principale: una sola copia di ciascuna nel back stack, con lo stato
// (scroll, tab) salvato e ripristinato; l'elenco regioni resta sempre in fondo, cosi' "Indietro"
// da Documenti/Altro ci torna.
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(RegionsRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateToOfficialSource(url: String) {
    val name = officialSourcesRegistry.firstOrNull { it.url == url }?.name.orEmpty()
    navigate(InAppBrowserRoute(url, name))
}

// Transizioni M3 (durate "medium" della spec di motion). Le animazioni Compose rispettano da sole
// l'impostazione di sistema "Rimuovi animazioni".
private const val MOTION_MS = 300
private const val SHARED_AXIS_OFFSET_DP = 30

private fun sharedAxisOffset(forward: Boolean): Int {
    val px = (SHARED_AXIS_OFFSET_DP * Resources.getSystem().displayMetrics.density).toInt()
    return if (forward) px else -px
}

private fun sharedAxisEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(MOTION_MS)) { sharedAxisOffset(forward) } + fadeIn(tween(MOTION_MS / 2, delayMillis = MOTION_MS / 4))

private fun sharedAxisExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(MOTION_MS)) { -sharedAxisOffset(forward) } + fadeOut(tween(MOTION_MS / 4))

private fun fadeThroughEnter(): EnterTransition =
    fadeIn(tween(MOTION_MS * 2 / 3, delayMillis = MOTION_MS / 3)) + scaleIn(tween(MOTION_MS * 2 / 3, delayMillis = MOTION_MS / 3), initialScale = 0.92f)

private fun fadeThroughExit(): ExitTransition = fadeOut(tween(MOTION_MS / 3))

// Destinazioni principali: "fade through" tra loro (cambio di sezione dalla barra), "shared axis"
// verso e dalle schermate di dettaglio.
private fun NavBackStackEntry.isTopLevel() = TopLevelDestination.entries.any { destination.hasRoute(it.route::class) }

private fun NavBackStackEntry.isRegionHub() = destination.hasRoute<RegionHubRoute>()

private val topLevelEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (initialState.isTopLevel()) fadeThroughEnter() else sharedAxisEnter(forward = true)
}
private val topLevelExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    when {
        targetState.isTopLevel() -> fadeThroughExit()
        // Verso l'hub: container transform, l'elenco sfuma sotto la riga che si allarga.
        targetState.isRegionHub() -> containerFadeOut()
        else -> sharedAxisExit(forward = true)
    }
}
private val topLevelPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    when {
        initialState.isTopLevel() -> fadeThroughEnter()
        initialState.isRegionHub() -> containerFadeIn()
        else -> sharedAxisEnter(forward = false)
    }
}
