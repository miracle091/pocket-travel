package com.pockettravel.app.navigation.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.pockettravel.app.navigation.InAppBrowserRoute
import com.pockettravel.app.navigation.RegionContainerTransformScope
import com.pockettravel.app.navigation.RegionHubRoute
import com.pockettravel.app.navigation.RegionPreviewRoute
import com.pockettravel.app.navigation.RegionsRoute
import com.pockettravel.app.navigation.containerFadeIn
import com.pockettravel.app.navigation.containerFadeOut
import com.pockettravel.app.navigation.navigateToOfficialSource
import com.pockettravel.app.navigation.sharedAxisEnter
import com.pockettravel.app.navigation.sharedAxisExit
import com.pockettravel.app.navigation.topLevelEnter
import com.pockettravel.app.navigation.topLevelExit
import com.pockettravel.app.navigation.topLevelPopEnter
import com.pockettravel.app.regions.RegionHubScreen
import com.pockettravel.app.regions.RegionListScreen
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionPreviewScreen
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionWorldMap
import com.pockettravel.app.regions.rowActions

internal fun NavGraphBuilder.regionsScreen(navController: NavHostController, isExpanded: Boolean) {
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
}

internal fun NavGraphBuilder.regionHubScreen(navController: NavHostController) {
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
}

internal fun NavGraphBuilder.regionPreviewScreen(navController: NavHostController) {
    composable<RegionPreviewRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<RegionPreviewRoute>()
        // Lo stesso ViewModel dell'elenco (sta nella voce RegionsRoute sotto): il download parte dal suo percorso.
        val listViewModel: RegionListViewModel = hiltViewModel(remember(backStackEntry) { navController.getBackStackEntry(RegionsRoute) })
        val listState by listViewModel.uiState.collectAsStateWithLifecycle()
        RegionPreviewScreen(
            regionId = route.regionId,
            displayName = route.name.ifBlank { route.regionId },
            onBack = dropUnlessResumed { navController.popBackStack() },
            item = listState.items.firstOrNull { it.regionId == route.regionId },
            downloading = route.regionId in listState.downloadingRegions,
            rowActions = listViewModel.rowActions(),
            // Il download completo si segue dalla lista Regioni (barra di avanzamento gia'
            // presente li'), non duplicata qui: torna semplicemente indietro.
            onDownloadFull = dropUnlessResumed { navController.popBackStack() },
            onOpenSource = { url, title -> navController.navigate(InAppBrowserRoute(url, title)) },
        )
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
                // Nessuna regione scelta: il pannello di destra mostra la mappa delle nazioni, dove
                // toccare una nazione equivale a sceglierlo dall'elenco.
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
