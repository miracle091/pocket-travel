package com.pockettravel.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.ui.PocketTravelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Prova su emulatore la scheda del Navigatore senza ViewModel ne' motore dei percorsi: stato e azioni si passano a
 * mano e la mappa e' uno slot vuoto, cosi' non si crea nessuna vista MapLibre (l'emulatore ha il GL software).
 * I testi si leggono a runtime, per qualunque lingua del dispositivo.
 */
class NavigationPlannerContentDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private val colosseo = NavigationPlace("Colosseo", 41.8902, 12.4922, "italia")
    private val destination = NavigationPlace("Torre Eiffel", 48.8584, 2.2945, "francia")

    private fun setContent(state: NavigationPlannerState, actions: NavigationPlannerActions = NavigationPlannerActions()) {
        composeRule.setContent {
            PocketTravelTheme(dynamicColor = false) {
                NavigationPlannerContent(state = state, actions = actions, map = NoMap)
            }
        }
    }

    @Test
    fun searchListsRecentsAndChoosingOneNotifiesIt() {
        var chosen: NavigationPlace? = null
        setContent(
            NavigationPlannerState(searching = PlannerField.TO, recents = listOf(colosseo, destination)),
            NavigationPlannerActions(onChoose = { chosen = it }),
        )

        composeRule.onNodeWithText(colosseo.name).assertExists()
        composeRule.onNodeWithText(destination.name).performClick()

        assertEquals(destination, chosen)
    }

    @Test
    fun calculatingShowsItsTitle() {
        setContent(NavigationPlannerState(to = destination, preview = PlannerPreview.Calculating(0.0)))

        composeRule.onNodeWithText(string(R.string.planner_calculating)).assertExists()
    }

    @Test
    fun unavailableRouteShowsTheMessageAndRetryNotifiesIt() {
        var retried = 0
        setContent(
            NavigationPlannerState(to = destination, preview = PlannerPreview.Unavailable(RouteResult.NotFound)),
            NavigationPlannerActions(onRetry = { retried++ }),
        )

        composeRule.onNodeWithText(string(R.string.navigation_not_found)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_retry)).performClick()

        assertEquals(1, retried)
    }

    private companion object {
        // Nessuna mappa: con le regioni vuote non viene nemmeno chiamata, ma lo slot e' obbligatorio.
        val NoMap: @Composable (List<String>, Route?, Float, Float, Modifier) -> Unit = { _, _, _, _, _ -> }
    }
}
