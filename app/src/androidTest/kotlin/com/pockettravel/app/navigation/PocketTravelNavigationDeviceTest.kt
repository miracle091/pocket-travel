package com.pockettravel.app.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Il grafo di rotte che PocketTravelNavHost usa per l'avvio e per le schermate di dettaglio, senza Hilt: l'onboarding
// lascia il posto all'elenco senza poterci tornare, una schermata aperta due volte da una notifica resta una sola e
// gli argomenti dell'anteprima e dell'hub arrivano alla destinazione.
class PocketTravelNavigationDeviceTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var navController: TestNavHostController
    private var preview: RegionPreviewRoute? = null
    private var hub: RegionHubRoute? = null

    @Before
    fun setUp() {
        preview = null
        hub = null
    }

    private fun setContent(start: Any) {
        compose.setContent {
            navController = TestNavHostController(LocalContext.current).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            NavHost(navController, startDestination = start) {
                composable<OnboardingRoute> {}
                composable<RegionsRoute> {}
                composable<UpdatesRoute> {}
                composable<RegionPreviewRoute> { preview = it.toRoute() }
                composable<RegionHubRoute> { hub = it.toRoute() }
            }
        }
    }

    private fun navigate(route: Any, builder: NavOptionsBuilder.() -> Unit = {}) {
        compose.runOnUiThread { navController.navigate(route, builder) }
        compose.waitForIdle()
    }

    @Test
    fun onboardingIsReplacedByTheRegionsList() {
        setContent(OnboardingRoute)
        assertTrue(navController.currentDestination?.hasRoute<OnboardingRoute>() == true)

        navigate(RegionsRoute) { popUpTo<OnboardingRoute> { inclusive = true } }

        assertTrue(navController.currentDestination?.hasRoute<RegionsRoute>() == true)
        assertTrue(navController.currentBackStack.value.none { it.destination.hasRoute<OnboardingRoute>() })
    }

    @Test
    fun openingUpdatesTwiceKeepsASingleCopy() {
        setContent(RegionsRoute)

        navigate(UpdatesRoute) { launchSingleTop = true }
        navigate(UpdatesRoute) { launchSingleTop = true }

        val updates = navController.currentBackStack.value.count { it.destination.hasRoute<UpdatesRoute>() }
        assertEquals(1, updates)

        compose.runOnUiThread { navController.popBackStack() }
        compose.waitForIdle()
        assertTrue(navController.currentDestination?.hasRoute<RegionsRoute>() == true)
    }

    @Test
    fun regionPreviewAndHubReceiveTheirArguments() {
        setContent(RegionsRoute)

        navigate(RegionPreviewRoute("san-marino", "San Marino"))
        assertEquals(RegionPreviewRoute("san-marino", "San Marino"), preview)

        navigate(RegionHubRoute("san-marino", tab = "map"))
        assertEquals(RegionHubRoute("san-marino", tab = "map"), hub)
    }
}
