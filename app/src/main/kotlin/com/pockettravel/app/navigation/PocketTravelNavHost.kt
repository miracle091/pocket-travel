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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowSizeClass
import com.pockettravel.app.R
import com.pockettravel.app.browser.InAppBrowserScreen
import com.pockettravel.app.licenses.LicensesScreen
import com.pockettravel.app.more.MoreScreen
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_NAME
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_REGION_ID
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_TAB
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_TITLE
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_URL
import com.pockettravel.app.navigation.PocketTravelDestinations.IN_APP_BROWSER_PATTERN
import com.pockettravel.app.navigation.PocketTravelDestinations.LICENSES
import com.pockettravel.app.navigation.PocketTravelDestinations.MORE
import com.pockettravel.app.navigation.PocketTravelDestinations.ONBOARDING
import com.pockettravel.app.navigation.PocketTravelDestinations.REGIONS
import com.pockettravel.app.navigation.PocketTravelDestinations.REGION_HUB_PATTERN
import com.pockettravel.app.navigation.PocketTravelDestinations.REGION_PREVIEW_PATTERN
import com.pockettravel.app.navigation.PocketTravelDestinations.SOURCES
import com.pockettravel.app.navigation.PocketTravelDestinations.STORAGE
import com.pockettravel.app.navigation.PocketTravelDestinations.TUTORIAL
import com.pockettravel.app.navigation.PocketTravelDestinations.VAULT
import com.pockettravel.app.navigation.PocketTravelDestinations.inAppBrowser
import com.pockettravel.app.navigation.PocketTravelDestinations.regionHub
import com.pockettravel.app.navigation.PocketTravelDestinations.regionPreview
import com.pockettravel.app.onboarding.OnboardingScreen
import com.pockettravel.app.onboarding.OnboardingViewModel
import com.pockettravel.app.regions.RegionHubScreen
import com.pockettravel.app.regions.RegionListScreen
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionRowActions
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionWorldMap
import com.pockettravel.app.regions.RegionPreviewScreen
import com.pockettravel.app.storage.StorageScreen
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.feature.sources.OfficialSourcesScreen
import com.pockettravel.feature.vault.PassportVaultScreen

// Le tre destinazioni principali della barra/rail di navigazione (M3: il menu laterale modale e'
// sconsigliato, sostituito dalla navigation suite). La barra compare solo su queste tre: le
// schermate di dettaglio (hub regione, fonti, licenze...) occupano tutto lo spazio.
private enum class TopLevelDestination(val route: String, @StringRes val label: Int) {
    REGIONS(PocketTravelDestinations.REGIONS, R.string.nav_regions),
    VAULT(PocketTravelDestinations.VAULT, R.string.nav_documents),
    MORE(PocketTravelDestinations.MORE, R.string.nav_more),
}

@Composable
private fun TopLevelDestination.icon(selected: Boolean): ImageVector = when (this) {
    TopLevelDestination.REGIONS -> if (selected) AppIcons.WorldFilled else AppIcons.World
    TopLevelDestination.VAULT -> if (selected) AppIcons.DocumentsFilled else AppIcons.Documents
    TopLevelDestination.MORE -> if (selected) AppIcons.MoreFilled else AppIcons.More
}

// Un solo NavHost per l'intera app (attività singola, vedi AndroidManifest): sostituisce
// il vecchio if/else hardcoded in MainActivity. Lo start destination si decide una volta
// sola in modo sincrono (StartDestinationViewModel) — non reattivamente, perche' una volta
// scelta la rotta iniziale non deve piu' cambiare per la vita del NavHost.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PocketTravelNavHost(
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    startDestinationViewModel: StartDestinationViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val startDestination = remember { startDestinationViewModel.startDestination }
    val initialRegionId = remember { startDestinationViewModel.initialRegionId }
    var initialRegionOpened by rememberSaveable { mutableStateOf(false) }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val currentTopLevel = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // App gia' in uso: riparte dalla mappa dell'ultima regione, impilata sopra l'elenco regioni
    // (una volta sola, non dopo una ricreazione dell'activity: il back stack e' gia' ripristinato).
    LaunchedEffect(Unit) {
        if (!initialRegionOpened && initialRegionId != null) {
            initialRegionOpened = true
            navController.navigate(regionHub(initialRegionId, tab = "map"))
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
            composable(ONBOARDING) {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = {
                        navController.navigate(REGIONS) {
                            popUpTo(ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(REGIONS, enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                val onPreviewClick = { regionId: String, displayName: String ->
                    navController.navigate(regionPreview(regionId, displayName))
                }
                if (isExpanded) {
                    RegionsListDetail(navController = navController, onPreviewClick = onPreviewClick)
                } else {
                    RegionContainerTransformScope(this) {
                        RegionListScreen(
                            onRegionClick = { regionId -> navController.navigate(regionHub(regionId)) },
                            onPreviewClick = onPreviewClick,
                        )
                    }
                }
            }
            composable(VAULT, enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                PassportVaultScreen()
            }
            composable(MORE, enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) {
                MoreScreen(
                    onOpenSources = { navController.navigate(SOURCES) },
                    onOpenStorage = { navController.navigate(STORAGE) },
                    onOpenTutorial = { navController.navigate(TUTORIAL) },
                    onOpenLicenses = { navController.navigate(LICENSES) },
                )
            }
            composable(TUTORIAL) {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = { navController.popBackStack() },
                )
            }
            composable(STORAGE) {
                StorageScreen(onBack = { navController.popBackStack() })
            }
            composable(SOURCES) {
                OfficialSourcesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSource = { url, title -> navController.navigate(inAppBrowser(url, title)) },
                )
            }
            composable(LICENSES) {
                LicensesScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = REGION_HUB_PATTERN,
                arguments = listOf(
                    navArgument(ARG_REGION_ID) { type = NavType.StringType },
                    navArgument(ARG_TAB) { type = NavType.StringType; defaultValue = "guide" },
                ),
                // Dall'elenco regioni la riga si trasforma nell'hub (container transform): la
                // schermata sotto sfuma invece di scorrere, per non spostare la riga che si allarga.
                enterTransition = { if (initialState.destination.route == REGIONS) fadeIn(tween(MOTION_MS)) else sharedAxisEnter(forward = true) },
                popExitTransition = { if (targetState.destination.route == REGIONS) fadeOut(tween(MOTION_MS)) else sharedAxisExit(forward = false) },
            ) { backStackEntry ->
                val regionId = backStackEntry.arguments?.getString(ARG_REGION_ID).orEmpty()
                val tab = backStackEntry.arguments?.getString(ARG_TAB) ?: "guide"
                RegionContainerTransformScope(this) {
                    RegionHubScreen(
                        regionId = regionId,
                        initialTab = tab,
                        onBack = { if (!navController.popBackStack()) navController.navigate(REGIONS) },
                        onOpenOfficialSource = { url -> navController.navigateToOfficialSource(url) },
                        onOpenSource = { url, title -> navController.navigate(inAppBrowser(url, title)) },
                    )
                }
            }
            composable(
                route = REGION_PREVIEW_PATTERN,
                arguments = listOf(
                    navArgument(ARG_REGION_ID) { type = NavType.StringType },
                    navArgument(ARG_NAME) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { backStackEntry ->
                val regionId = backStackEntry.arguments?.getString(ARG_REGION_ID).orEmpty()
                val displayName = backStackEntry.arguments?.getString(ARG_NAME).orEmpty()
                RegionPreviewScreen(
                    regionId = regionId,
                    displayName = displayName.ifBlank { regionId },
                    onBack = { navController.popBackStack() },
                    // Il download completo si segue dalla lista Regioni (barra di avanzamento gia'
                    // presente li'), non duplicata qui: torna semplicemente indietro.
                    onDownloadFull = { navController.popBackStack() },
                    onOpenSource = { url, title -> navController.navigate(inAppBrowser(url, title)) },
                )
            }
            composable(
                route = IN_APP_BROWSER_PATTERN,
                arguments = listOf(
                    navArgument(ARG_URL) { type = NavType.StringType },
                    navArgument(ARG_TITLE) { type = NavType.StringType; defaultValue = "" },
                ),
            ) { backStackEntry ->
                val url = backStackEntry.arguments?.getString(ARG_URL).orEmpty()
                val title = backStackEntry.arguments?.getString(ARG_TITLE).orEmpty()
                InAppBrowserScreen(url = url, title = title, onBack = { navController.popBackStack() })
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
                    rowActions = RegionRowActions(
                        observeProgress = regionListViewModel::observeDownloadProgress,
                        onDownload = regionListViewModel::download,
                        onDelete = regionListViewModel::delete,
                        onDownloadPackage = regionListViewModel::downloadPackage,
                        onDeletePackage = regionListViewModel::deletePackage,
                    ),
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
                        onOpenSource = { url, title -> navController.navigate(inAppBrowser(url, title)) },
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
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(REGIONS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateToOfficialSource(url: String) {
    val name = officialSourcesRegistry.firstOrNull { it.url == url }?.name.orEmpty()
    navigate(inAppBrowser(url, name))
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
private fun NavBackStackEntry.isTopLevel() = TopLevelDestination.entries.any { it.route == destination.route }

private fun NavBackStackEntry.isRegionHub() = destination.route == REGION_HUB_PATTERN

private val topLevelEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (initialState.isTopLevel()) fadeThroughEnter() else sharedAxisEnter(forward = true)
}
private val topLevelExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    when {
        targetState.isTopLevel() -> fadeThroughExit()
        // Verso l'hub: container transform, l'elenco sfuma sotto la riga che si allarga.
        targetState.isRegionHub() -> fadeOut(tween(MOTION_MS))
        else -> sharedAxisExit(forward = true)
    }
}
private val topLevelPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    when {
        initialState.isTopLevel() -> fadeThroughEnter()
        initialState.isRegionHub() -> fadeIn(tween(MOTION_MS))
        else -> sharedAxisEnter(forward = false)
    }
}
