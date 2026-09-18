package com.pockettravel.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pockettravel.app.browser.InAppBrowserScreen
import com.pockettravel.app.licenses.LicensesScreen
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_NAME
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_REGION_ID
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_TAB
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_TITLE
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_URL
import com.pockettravel.app.navigation.PocketTravelDestinations.IN_APP_BROWSER_PATTERN
import com.pockettravel.app.navigation.PocketTravelDestinations.LICENSES
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
import com.pockettravel.app.regions.RegionPreviewScreen
import com.pockettravel.app.storage.StorageScreen
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.feature.sources.OfficialSourcesScreen
import com.pockettravel.feature.vault.PassportVaultScreen

// Un solo NavHost per l'intera app (attività singola, vedi AndroidManifest): sostituisce
// il vecchio if/else hardcoded in MainActivity. Lo start destination si decide una volta
// sola in modo sincrono (StartDestinationViewModel) — non reattivamente, perche' una volta
// scelta la rotta iniziale non deve piu' cambiare per la vita del NavHost.
@Composable
fun PocketTravelNavHost(
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    startDestinationViewModel: StartDestinationViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val startDestination = remember { startDestinationViewModel.startDestination }

    NavHost(navController = navController, startDestination = startDestination) {
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
        composable(REGIONS) {
            RegionListScreen(
                onRegionClick = { regionId -> navController.navigate(regionHub(regionId)) },
                onPreviewClick = { regionId, displayName -> navController.navigate(regionPreview(regionId, displayName)) },
                onOpenStorage = { navController.navigate(STORAGE) },
                onOpenSources = { navController.navigate(SOURCES) },
                onOpenLicenses = { navController.navigate(LICENSES) },
                onOpenVault = { navController.navigate(VAULT) },
                onOpenTutorial = { navController.navigate(TUTORIAL) },
                onOpenMap = { regionId -> navController.navigate(regionHub(regionId, tab = "map")) },
                onOpenAi = { regionId -> navController.navigate(regionHub(regionId, tab = "ai")) },
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
        composable(VAULT) {
            PassportVaultScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = REGION_HUB_PATTERN,
            arguments = listOf(
                navArgument(ARG_REGION_ID) { type = NavType.StringType },
                navArgument(ARG_TAB) { type = NavType.StringType; defaultValue = "guide" },
            ),
        ) { backStackEntry ->
            val regionId = backStackEntry.arguments?.getString(ARG_REGION_ID).orEmpty()
            val tab = backStackEntry.arguments?.getString(ARG_TAB) ?: "guide"
            RegionHubScreen(
                regionId = regionId,
                initialTab = tab,
                // Se questa e' la rotta di avvio (mappa dell'ultima regione, vedi
                // StartDestinationViewModel) non c'e' nulla da impilare sotto: popBackStack()
                // fallirebbe silenziosamente, lasciando la freccia Indietro morta.
                onBack = { if (!navController.popBackStack()) navController.navigate(REGIONS) },
                onOpenOfficialSource = { url ->
                    val name = officialSourcesRegistry.firstOrNull { it.url == url }?.name.orEmpty()
                    navController.navigate(inAppBrowser(url, name))
                },
                onOpenSource = { url, title -> navController.navigate(inAppBrowser(url, title)) },
            )
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
