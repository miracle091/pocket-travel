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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import com.pockettravel.app.R
import com.pockettravel.app.crash.CrashContext
import com.pockettravel.app.navigation.graph.inAppBrowserScreen
import com.pockettravel.app.navigation.graph.licenseScreen
import com.pockettravel.app.navigation.graph.licensesScreen
import com.pockettravel.app.navigation.graph.moreScreen
import com.pockettravel.app.navigation.graph.navigatorScreen
import com.pockettravel.app.navigation.graph.onboardingScreen
import com.pockettravel.app.navigation.graph.regionHubScreen
import com.pockettravel.app.navigation.graph.regionPreviewScreen
import com.pockettravel.app.navigation.graph.regionsScreen
import com.pockettravel.app.navigation.graph.settingsScreen
import com.pockettravel.app.navigation.graph.sourcesScreen
import com.pockettravel.app.navigation.graph.storageScreen
import com.pockettravel.app.navigation.graph.tutorialScreen
import com.pockettravel.app.navigation.graph.updatesScreen
import com.pockettravel.app.navigation.graph.vaultScreen
import com.pockettravel.app.onboarding.OnboardingViewModel
import com.pockettravel.app.regions.LocalNavigatorViewModels
import com.pockettravel.app.regions.NavigatorViewModels
import com.pockettravel.app.updates.UpdateRequiredMessages
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.R as UiR

// Le destinazioni principali della barra/rail di navigazione (M3: il menu laterale modale e'
// sconsigliato, sostituito dalla navigation suite). La barra compare solo su queste: le
// schermate di dettaglio (hub regione, Fonti ufficiali, licenze...) occupano tutto lo spazio.
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
    // Activity creata dal tocco sulla notifica della navigazione (non gia' aperta): parte dal Navigatore.
    openNavigatorOnStart: Boolean = false,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    startDestinationViewModel: StartDestinationViewModel = hiltViewModel(),
    // Dell'activity, come gli altri ViewModel qui (fuori dal NavHost): li leggono le schermate con LocalNavigatorViewModels.
    navigatorViewModels: NavigatorViewModels = NavigatorViewModels(hiltViewModel(), hiltViewModel()),
    // Parametro per i test di navigazione su JVM (TestNavHostController); in produzione resta quello salvato e ripristinato.
    navController: NavHostController = rememberNavController(),
) {
    val startDestination = remember { startDestinationViewModel.startDestination }

    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    // Nome della schermata per il rapporto di una chiusura inattesa: la rotta senza i valori degli argomenti.
    LaunchedEffect(currentDestination) {
        CrashContext.screen = currentDestination?.route?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('.')
    }
    val currentTopLevel = TopLevelDestination.entries.firstOrNull { currentDestination?.hasRoute(it.route::class) == true }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // Notifica della navigazione toccata con l'app aperta: fuori dall'hub di una regione (che passa da solo alla sua scheda) si
    // apre il Navigatore della barra, che mostra la stessa navigazione.
    val navigationViewModel = navigatorViewModels.navigation
    LaunchedEffect(navigationViewModel) {
        navigationViewModel.openNavigatorRequests.collect {
            if (navigationViewModel.isNavigating && navController.currentDestination?.hasRoute<RegionHubRoute>() != true) {
                navController.navigateTopLevel(NavigatorRoute)
            }
        }
    }

    // Ultima regione e Navigatore dalla notifica (StartDestinationViewModel.startCommands): i collector di
    // openNavigatorRequests non c'erano ancora quando l'activity ha letto l'intent, quindi qui si naviga direttamente.
    LaunchedEffect(Unit) {
        startDestinationViewModel.startCommands(openNavigatorOnStart, navigationViewModel.isNavigating).forEach { command ->
            when (command) {
                is StartCommand.Open -> navController.navigate(command.route)
                is StartCommand.OpenTopLevel -> navController.navigateTopLevel(command.route)
            }
        }
    }

    OpenScreenRequestsEffect(navController, currentDestination)

    val navigationSuiteType = if (currentTopLevel != null) {
        NavigationSuiteScaffoldDefaults.navigationSuiteType(adaptiveInfo)
    } else {
        NavigationSuiteType.None
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
        layoutType = navigationSuiteType,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalNavigatorViewModels provides navigatorViewModels) {
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
            onboardingScreen(navController, onboardingViewModel)
            regionsScreen(navController, isExpanded)
            navigatorScreen(navController)
            vaultScreen()
            moreScreen(navController)
            updatesScreen(navController)
            settingsScreen(navController)
            tutorialScreen(navController, onboardingViewModel)
            storageScreen(navController)
            sourcesScreen(navController)
            licensesScreen(navController)
            licenseScreen(navController)
            regionHubScreen(navController)
            regionPreviewScreen(navController)
            inAppBrowserScreen(navController)
        }
        }
        }
        UpdateRequiredMessages(aboveBottomBar = navigationSuiteType in BOTTOM_BARS)
        }
    }
}

// Cambio di destinazione principale: una sola copia di ciascuna nel back stack, con lo stato
// (scroll, scheda) salvato e ripristinato; l'elenco regioni resta sempre in fondo, cosi' "Indietro"
// da Documenti/Altro ci torna.
internal fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(RegionsRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

internal fun NavHostController.navigateToOfficialSource(url: String) {
    val name = officialSourcesRegistry.firstOrNull { it.url == url }?.name.orEmpty()
    navigate(InAppBrowserRoute(url, name))
}

// Barre di navigazione in basso, che coprono già la barra di sistema (le rail stanno di lato).
private val BOTTOM_BARS = setOf(
    NavigationSuiteType.NavigationBar,
    NavigationSuiteType.ShortNavigationBarCompact,
    NavigationSuiteType.ShortNavigationBarMedium,
)

// Transizioni M3 (durate "medium" della spec di motion). Le animazioni Compose rispettano da sole
// l'impostazione di sistema "Rimuovi animazioni".
private const val MOTION_MS = 300

// Divisori di MOTION_MS per ritardi e durate delle dissolvenze.
private const val QUARTER = 4
private const val THIRD = 3
private const val SHARED_AXIS_OFFSET_DP = 30

private fun sharedAxisOffset(forward: Boolean): Int {
    val px = (SHARED_AXIS_OFFSET_DP * Resources.getSystem().displayMetrics.density).toInt()
    return if (forward) px else -px
}

internal fun sharedAxisEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(MOTION_MS)) { sharedAxisOffset(forward) } + fadeIn(tween(MOTION_MS / 2, delayMillis = MOTION_MS / QUARTER))

internal fun sharedAxisExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(MOTION_MS)) { -sharedAxisOffset(forward) } + fadeOut(tween(MOTION_MS / QUARTER))

private fun fadeThroughEnter(): EnterTransition =
    fadeIn(tween(MOTION_MS * 2 / THIRD, delayMillis = MOTION_MS / THIRD)) + scaleIn(tween(MOTION_MS * 2 / THIRD, delayMillis = MOTION_MS / THIRD), initialScale = 0.92f)

private fun fadeThroughExit(): ExitTransition = fadeOut(tween(MOTION_MS / THIRD))

// Destinazioni principali: "fade through" tra loro (cambio di sezione dalla barra), "shared axis"
// verso e dalle schermate di dettaglio.
// Notifica di aggiornamento o di allerta, o widget, toccati (OpenScreenRequests): dopo la regione iniziale; le pagine delle regioni
// sostituiscono quello che c'e' sopra Nazioni. Durante
// l'onboarding no: la richiesta resta per quando l'elenco delle nazioni c'e'.
@Composable
private fun OpenScreenRequestsEffect(navController: NavHostController, currentDestination: NavDestination?) {
    val openScreen by OpenScreenRequests.screen.collectAsStateWithLifecycle()
    LaunchedEffect(openScreen, currentDestination) {
        val screen = openScreen ?: return@LaunchedEffect
        if (currentDestination == null || currentDestination.hasRoute<OnboardingRoute>()) return@LaunchedEffect
        OpenScreenRequests.screen.value = null
        // Una sola copia: toccare una seconda notifica con la schermata gia' aperta non la impila di nuovo.
        val target = openScreenTarget(screen) ?: return@LaunchedEffect
        when {
            target.topLevel -> navController.navigateTopLevel(target.route)
            // La stessa regione gia' aperta: solo la scheda, senza ricreare la pagina (una risposta in corso si perderebbe).
            target.route is RegionHubRoute && navController.openRegionId() == target.route.regionId ->
                OpenScreenRequests.regionTab.value = target.route.tab
            // La pagina di una regione sopra Nazioni, come dall'elenco: sopra Documenti, il tocco dopo su Documenti la
            // salvava con la sua pila e la riapriva sopra la cassaforte.
            target.route is RegionHubRoute -> navController.navigate(target.route) {
                popUpTo(RegionsRoute)
                launchSingleTop = true
            }
            else -> navController.navigate(target.route) { launchSingleTop = true }
        }
    }
}

private fun NavBackStackEntry.isTopLevel() = TopLevelDestination.entries.any { destination.hasRoute(it.route::class) }

private fun NavBackStackEntry.isRegionHub() = destination.hasRoute<RegionHubRoute>()

private fun NavHostController.openRegionId(): String? = currentBackStackEntry?.takeIf { it.isRegionHub() }?.toRoute<RegionHubRoute>()?.regionId

internal val topLevelEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (initialState.isTopLevel()) fadeThroughEnter() else sharedAxisEnter(forward = true)
}
internal val topLevelExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    when {
        targetState.isTopLevel() -> fadeThroughExit()
        // Verso l'hub: container transform, l'elenco sfuma sotto la riga che si allarga.
        targetState.isRegionHub() -> containerFadeOut()
        else -> sharedAxisExit(forward = true)
    }
}
internal val topLevelPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    when {
        initialState.isTopLevel() -> fadeThroughEnter()
        initialState.isRegionHub() -> containerFadeIn()
        else -> sharedAxisEnter(forward = false)
    }
}
