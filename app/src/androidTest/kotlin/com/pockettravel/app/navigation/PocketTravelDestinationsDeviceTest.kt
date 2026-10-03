package com.pockettravel.app.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// Le rotte type-safe arrivano intatte alla destinazione: prima url e titolo del browser erano
// codificati a mano (contengono ':' '/' '?' '&', che spezzerebbero il parsing della rotta).
class PocketTravelDestinationsDeviceTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun routesKeepTheirArguments() {
        lateinit var navController: TestNavHostController
        var browser: InAppBrowserRoute? = null
        var hub: RegionHubRoute? = null
        compose.setContent {
            navController = TestNavHostController(LocalContext.current).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            NavHost(navController, startDestination = RegionsRoute) {
                composable<RegionsRoute> {}
                composable<InAppBrowserRoute> { browser = it.toRoute() }
                composable<RegionHubRoute> { hub = it.toRoute() }
            }
        }

        val browserRoute = InAppBrowserRoute("https://example.org/a b?x=1&y=:z#frag", "Ambasciata & consolato / Roma")
        compose.runOnUiThread { navController.navigate(browserRoute) }
        compose.waitForIdle()
        assertEquals(browserRoute, browser)

        compose.runOnUiThread { navController.navigate(RegionHubRoute("italia")) }
        compose.waitForIdle()
        assertEquals(RegionHubRoute("italia", tab = "guide"), hub)
    }
}
