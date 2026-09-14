package com.pockettravel.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pockettravel.app.licenses.LicensesScreen
import com.pockettravel.app.navigation.PocketTravelDestinations.ARG_REGION_ID
import com.pockettravel.app.navigation.PocketTravelDestinations.LICENSES
import com.pockettravel.app.navigation.PocketTravelDestinations.ONBOARDING
import com.pockettravel.app.navigation.PocketTravelDestinations.REGIONS
import com.pockettravel.app.navigation.PocketTravelDestinations.REGION_HUB_PATTERN
import com.pockettravel.app.navigation.PocketTravelDestinations.SOURCES
import com.pockettravel.app.navigation.PocketTravelDestinations.STORAGE
import com.pockettravel.app.navigation.PocketTravelDestinations.VAULT
import com.pockettravel.app.navigation.PocketTravelDestinations.regionHub
import com.pockettravel.app.onboarding.OnboardingScreen
import com.pockettravel.app.onboarding.OnboardingViewModel
import com.pockettravel.app.regions.RegionHubScreen
import com.pockettravel.app.regions.RegionListScreen
import com.pockettravel.app.storage.StorageScreen
import com.pockettravel.feature.sources.OfficialSourcesScreen
import com.pockettravel.feature.vault.PassportVaultScreen

// Un solo NavHost per l'intera app (attività singola, vedi AndroidManifest): sostituisce
// il vecchio if/else hardcoded in MainActivity. Lo start destination si decide una volta
// sola leggendo il flag di onboarding in modo sincrono (OnboardingPreferences, già letto
// dal costruttore di OnboardingViewModel) — non reattivamente, perche' una volta completato
// l'onboarding la rotta iniziale non deve piu' cambiare per la vita del NavHost.
@Composable
fun PocketTravelNavHost(onboardingViewModel: OnboardingViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val startDestination = remember { if (onboardingViewModel.isCompleted.value) REGIONS else ONBOARDING }

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
                onOpenStorage = { navController.navigate(STORAGE) },
                onOpenSources = { navController.navigate(SOURCES) },
                onOpenLicenses = { navController.navigate(LICENSES) },
                onOpenVault = { navController.navigate(VAULT) },
            )
        }
        composable(STORAGE) {
            StorageScreen(onBack = { navController.popBackStack() })
        }
        composable(SOURCES) {
            OfficialSourcesScreen(onBack = { navController.popBackStack() })
        }
        composable(LICENSES) {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
        composable(VAULT) {
            PassportVaultScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = REGION_HUB_PATTERN,
            arguments = listOf(navArgument(ARG_REGION_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val regionId = backStackEntry.arguments?.getString(ARG_REGION_ID).orEmpty()
            RegionHubScreen(regionId = regionId, onBack = { navController.popBackStack() })
        }
    }
}
