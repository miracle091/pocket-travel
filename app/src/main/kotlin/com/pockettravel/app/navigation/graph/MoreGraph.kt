package com.pockettravel.app.navigation.graph

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.pockettravel.app.R
import com.pockettravel.app.licenses.LicenseScreen
import com.pockettravel.app.licenses.LicensesScreen
import com.pockettravel.app.more.MoreScreen
import com.pockettravel.app.navigation.InAppBrowserRoute
import com.pockettravel.app.navigation.LicenseRoute
import com.pockettravel.app.navigation.LicensesRoute
import com.pockettravel.app.navigation.MoreRoute
import com.pockettravel.app.navigation.RegionsRoute
import com.pockettravel.app.navigation.SettingsRoute
import com.pockettravel.app.navigation.SourcesRoute
import com.pockettravel.app.navigation.StorageRoute
import com.pockettravel.app.navigation.TutorialRoute
import com.pockettravel.app.navigation.UpdatesRoute
import com.pockettravel.app.navigation.topLevelEnter
import com.pockettravel.app.navigation.topLevelExit
import com.pockettravel.app.navigation.topLevelPopEnter
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.settings.SettingsScreen
import com.pockettravel.app.settings.SettingsViewModel
import com.pockettravel.app.storage.StorageScreen
import com.pockettravel.app.updates.UpdatesScreen
import com.pockettravel.app.updates.UpdatesViewModel
import com.pockettravel.app.updates.outdatedRegions
import com.pockettravel.app.updates.updatesCount
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ContextualHint
import com.pockettravel.feature.sources.OfficialSourcesScreen

internal fun NavGraphBuilder.moreScreen(navController: NavHostController) {
    composable<MoreRoute>(enterTransition = topLevelEnter, exitTransition = topLevelExit, popEnterTransition = topLevelPopEnter) { backStackEntry ->
        // Gli stessi ViewModel di Aggiornamenti (nella voce RegionsRoute sotto): il badge conta senza aprire la schermata.
        val regionsEntry = remember(backStackEntry) { navController.getBackStackEntry(RegionsRoute) }
        val listState by hiltViewModel<RegionListViewModel>(regionsEntry).uiState.collectAsStateWithLifecycle()
        val updatesState by hiltViewModel<UpdatesViewModel>(regionsEntry).uiState.collectAsStateWithLifecycle()
        MoreScreen(
            updatesCount = updatesCount(
                appUpdate = updatesState.appUpdate != null,
                regionUpdates = outdatedRegions(listState.items).size,
                guidesUpdate = updatesState.guidesUpdate != null,
                modelUpdate = updatesState.modelUpdate != null,
            ),
            onOpenUpdates = { navController.navigate(UpdatesRoute) },
            onOpenSettings = { navController.navigate(SettingsRoute) },
            onOpenSources = { navController.navigate(SourcesRoute) },
            onOpenStorage = { navController.navigate(StorageRoute) },
            onOpenTutorial = { navController.navigate(TutorialRoute) },
            onOpenLicenses = { navController.navigate(LicensesRoute) },
        )
    }
}

internal fun NavGraphBuilder.updatesScreen(navController: NavHostController) {
    composable<UpdatesRoute> { backStackEntry ->
        // Lo stesso ViewModel dell'elenco (nella voce RegionsRoute sotto): l'aggiornamento di una nazione parte dal suo percorso.
        val regionsEntry = remember(backStackEntry) { navController.getBackStackEntry(RegionsRoute) }
        UpdatesScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            regionListViewModel = hiltViewModel(regionsEntry),
            viewModel = hiltViewModel(regionsEntry),
        )
    }
}

internal fun NavGraphBuilder.settingsScreen(navController: NavHostController) {
    composable<SettingsRoute> {
        SettingsScreen(onBack = dropUnlessResumed { navController.popBackStack() })
    }
}

internal fun NavGraphBuilder.storageScreen(navController: NavHostController) {
    composable<StorageRoute> {
        StorageScreen(onBack = dropUnlessResumed { navController.popBackStack() })
    }
}

internal fun NavGraphBuilder.sourcesScreen(navController: NavHostController) {
    composable<SourcesRoute> {
        // Stesse preferenze delle Impostazioni: la nazionalita' sceglie le Fonti ufficiali del proprio paese.
        val nationality by hiltViewModel<SettingsViewModel>().nationality.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            Box(modifier = Modifier.weight(1f)) {
                OfficialSourcesScreen(
                    nationality = nationality,
                    onBack = dropUnlessResumed { navController.popBackStack() },
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
}

internal fun NavGraphBuilder.licensesScreen(navController: NavHostController) {
    composable<LicensesRoute> {
        LicensesScreen(
            viewModel = hiltViewModel(),
            onBack = dropUnlessResumed { navController.popBackStack() },
            onOpenLibrary = { navController.navigate(LicenseRoute(it)) },
            onOpenLink = { navController.navigate(InAppBrowserRoute(it)) },
        )
    }
}

internal fun NavGraphBuilder.licenseScreen(navController: NavHostController) {
    composable<LicenseRoute> { backStackEntry ->
        // Lo stesso ViewModel dell'elenco (sta nella voce LicensesRoute sotto): i dati sono gia' letti.
        LicenseScreen(
            viewModel = hiltViewModel(remember(backStackEntry) { navController.getBackStackEntry(LicensesRoute) }),
            uniqueId = backStackEntry.toRoute<LicenseRoute>().uniqueId,
            onBack = dropUnlessResumed { navController.popBackStack() },
            onOpenLink = { navController.navigate(InAppBrowserRoute(it)) },
        )
    }
}
