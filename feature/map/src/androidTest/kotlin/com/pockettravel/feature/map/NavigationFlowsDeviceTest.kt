package com.pockettravel.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.ui.PocketTravelTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Flussi del Navigatore e della navigazione provati su emulatore senza ViewModel, motore dei percorsi ne' mappa: stato e
 * azioni si passano a mano, la mappa del Navigatore e' uno slot vuoto e gli stati della navigazione senza percorso non ne
 * creano nessuna. Nessun file da copiare sul dispositivo; i testi si leggono a runtime, per qualunque lingua.
 */
class NavigationFlowsDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun string(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private val colosseo = NavigationPlace("Colosseo", 41.8902, 12.4922, "italia")
    private val torreEiffel = NavigationPlace("Torre Eiffel", 48.8584, 2.2945, "francia")
    private val route = Route(
        points = listOf(RoutePoint(41.8902, 12.4922), RoutePoint(41.9, 12.5)),
        distanceMeters = 2_000.0,
        durationSeconds = 600.0,
        instructions = listOf(TurnInstruction(TurnType.LEFT, 500.0, 1), TurnInstruction(TurnType.ARRIVE, 0.0, 1)),
    )

    private fun setPlanner(state: NavigationPlannerState, actions: NavigationPlannerActions = NavigationPlannerActions()) {
        composeRule.setContent {
            PocketTravelTheme(dynamicColor = false) {
                NavigationPlannerContent(state = state, actions = actions, map = NoMap)
            }
        }
    }

    // Planner: ricerca

    @Test
    fun withoutDestinationTheSearchPillOpensTheDestinationSearch() {
        var started: PlannerField? = null
        setPlanner(NavigationPlannerState(), NavigationPlannerActions(onStartSearch = { started = it }))

        composeRule.onNodeWithText(string(R.string.planner_where_to)).performClick()

        assertEquals(PlannerField.TO, started)
    }

    @Test
    fun typingInTheSearchFieldNotifiesTheText() {
        var typed: String? = null
        setPlanner(
            NavigationPlannerState(searching = PlannerField.TO),
            NavigationPlannerActions(onSetQuery = { typed = it }),
        )

        composeRule.onNode(hasSetTextAction()).performTextInput("col")

        assertEquals("col", typed)
    }

    @Test
    fun aSearchWithoutResultsSaysSo() {
        setPlanner(NavigationPlannerState(searching = PlannerField.TO, query = "zzz"))

        composeRule.onNodeWithText(string(R.string.planner_no_results)).assertExists()
    }

    @Test
    fun aShortQueryShowsNoEmptyMessage() {
        setPlanner(NavigationPlannerState(searching = PlannerField.TO, query = "z"))

        composeRule.onNodeWithText(string(R.string.planner_no_results)).assertDoesNotExist()
    }

    @Test
    fun choosingASearchResultNotifiesThePlace() {
        var chosen: NavigationPlace? = null
        setPlanner(
            NavigationPlannerState(
                searching = PlannerField.TO,
                query = "tor",
                results = listOf(
                    PlannerResult(colosseo, typeLabel = null, otherRegionName = null, distanceMeters = null),
                    PlannerResult(torreEiffel, typeLabel = null, otherRegionName = "Francia", distanceMeters = 1_200.0),
                ),
            ),
            NavigationPlannerActions(onChoose = { chosen = it }),
        )

        composeRule.onNodeWithText(torreEiffel.name).performClick()

        assertEquals(torreEiffel, chosen)
    }

    @Test
    fun theStartFieldOffersMyPositionAndTheDestinationFieldDoesNot() {
        var used = 0
        setPlanner(NavigationPlannerState(searching = PlannerField.FROM), NavigationPlannerActions(onUseMyPosition = { used++ }))

        composeRule.onNodeWithText(string(R.string.planner_my_position)).performClick()

        assertEquals(1, used)
    }

    @Test
    fun theDestinationSearchHasNoMyPositionRow() {
        setPlanner(NavigationPlannerState(searching = PlannerField.TO))

        composeRule.onNodeWithText(string(R.string.planner_my_position)).assertDoesNotExist()
    }

    // Planner: pannello del percorso

    @Test
    fun swapIsDisabledWhenStartingFromMyPosition() {
        setPlanner(NavigationPlannerState(to = torreEiffel, from = null))

        composeRule.onNodeWithContentDescription(string(R.string.planner_swap)).assertIsNotEnabled()
    }

    @Test
    fun swapNotifiesWhenTheStartIsAPlace() {
        var swapped = 0
        setPlanner(NavigationPlannerState(from = colosseo, to = torreEiffel), NavigationPlannerActions(onSwap = { swapped++ }))

        composeRule.onNodeWithContentDescription(string(R.string.planner_swap)).assertIsEnabled().performClick()

        assertEquals(1, swapped)
    }

    @Test
    fun choosingAnotherTransportModeNotifiesIt() {
        var mode: RouteProfile? = null
        setPlanner(
            NavigationPlannerState(to = torreEiffel, routeProfile = RouteProfile.WALK),
            NavigationPlannerActions(onRouteProfileChange = { mode = it }),
        )

        composeRule.onNodeWithContentDescription(string(R.string.usage_mode_car)).performClick()

        assertEquals(RouteProfile.CAR, mode)
    }

    @Test
    fun theSelectedTransportModeShowsItsNameInsteadOfAnIconLabel() {
        setPlanner(NavigationPlannerState(to = torreEiffel, routeProfile = RouteProfile.BIKE))

        composeRule.onNodeWithText(string(R.string.usage_mode_bike)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.usage_mode_bike)).assertDoesNotExist()
    }

    // Planner: anteprima

    @Test
    fun aReadyRouteFromMyPositionCanStart() {
        var started = 0
        setPlanner(
            NavigationPlannerState(to = torreEiffel, preview = PlannerPreview.Ready(route, listOf("francia"))),
            NavigationPlannerActions(onStart = { started++ }),
        )

        composeRule.onNodeWithContentDescription(string(R.string.planner_start)).performClick()

        assertEquals(1, started)
    }

    @Test
    fun aReadyRouteFromAChosenPlaceOnlyPreviews() {
        setPlanner(NavigationPlannerState(from = colosseo, to = torreEiffel, preview = PlannerPreview.Ready(route, listOf("francia"))))

        composeRule.onNodeWithContentDescription(string(R.string.planner_start)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.planner_preview_only)).assertExists()
    }

    @Test
    fun withoutPermissionThePreviewAsksForIt() {
        var requested = 0
        setPlanner(
            NavigationPlannerState(to = torreEiffel, preview = PlannerPreview.NeedsPermission),
            NavigationPlannerActions(onRequestPermission = { requested++ }),
        )

        composeRule.onNodeWithText(string(R.string.planner_permission)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_permission_grant)).performClick()

        assertEquals(1, requested)
    }

    @Test
    fun missingRoadNetworkOffersTheDownloadOnlyWhenNotInstalled() {
        setPlanner(
            NavigationPlannerState(
                to = torreEiffel,
                preview = PlannerPreview.Unavailable(RouteResult.NoRoutingData),
                routingInstalled = false,
            ),
        )

        composeRule.onNodeWithText(string(R.string.planner_no_routing_data)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_routing_download)).assertExists()
    }

    @Test
    fun installedRoadNetworkOutsideTheDownloadedZoneOffersNoDownload() {
        setPlanner(
            NavigationPlannerState(
                to = torreEiffel,
                preview = PlannerPreview.Unavailable(RouteResult.NoRoutingData),
                routingInstalled = true,
            ),
        )

        composeRule.onNodeWithText(string(R.string.navigation_outside_routing)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_routing_download)).assertDoesNotExist()
    }

    @Test
    fun anAnnouncedArrivalIsAcknowledgedOnce() {
        var announced = 0
        setPlanner(
            NavigationPlannerState(arrivedAt = colosseo.name),
            NavigationPlannerActions(onArrivalAnnounced = { announced++ }),
        )

        composeRule.waitForIdle()

        assertEquals(1, announced)
    }

    @Test
    fun withoutArrivalNothingIsAnnounced() {
        var announced = 0
        setPlanner(NavigationPlannerState(), NavigationPlannerActions(onArrivalAnnounced = { announced++ }))

        composeRule.waitForIdle()

        assertEquals(0, announced)
    }

    // Navigazione: stati senza percorso da seguire

    private fun setGuide(state: NavigationStatus, onRetry: () -> Unit = {}, onClose: () -> Unit = {}) {
        composeRule.setContent {
            PocketTravelTheme(dynamicColor = false) {
                NavigationInstructions(
                    state = state,
                    destinationName = torreEiffel.name,
                    routeProfile = RouteProfile.WALK,
                    arriveBy = null,
                    tileSource = NoTiles,
                    regionIds = emptyList(),
                    onPermissionResult = {},
                    onRetry = onRetry,
                    onClose = onClose,
                )
            }
        }
    }

    @Test
    fun guideShowsTheDestinationTitleAndCloses() {
        var closed = 0
        setGuide(NavigationStatus.WaitingForFix, onClose = { closed++ })

        composeRule.onNodeWithText(string(R.string.navigation_title, torreEiffel.name)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_close)).performClick()

        assertEquals(1, closed)
    }

    @Test
    fun guideWaitingForFixAnnouncesIt() {
        setGuide(NavigationStatus.WaitingForFix)

        composeRule.onNodeWithContentDescription(string(R.string.navigation_waiting_fix)).assertExists()
    }

    @Test
    fun guideWithoutPermissionOffersToGrantIt() {
        setGuide(NavigationStatus.NeedsPermission)

        composeRule.onNodeWithText(string(R.string.navigation_permission)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_permission_grant)).assertExists()
    }

    @Test
    fun guideWithGpsOffOffersTheSettings() {
        setGuide(NavigationStatus.GpsDisabled)

        composeRule.onNodeWithText(string(R.string.navigation_gps_disabled)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_gps_settings)).assertExists()
    }

    @Test
    fun guideCalculatingShowsProgressOnlyAfterTheFirstSecond() {
        setGuide(NavigationStatus.Calculating(elapsedSeconds = 0, progress = 0.5))

        composeRule.onNodeWithContentDescription(string(R.string.navigation_calculating)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_calculating_progress, 50)).assertDoesNotExist()
    }

    @Test
    fun guideCalculatingShowsThePercentageAndLaterTheLongCalculationNotice() {
        setGuide(NavigationStatus.Calculating(elapsedSeconds = 6, progress = 0.5))

        composeRule.onNodeWithText(string(R.string.navigation_calculating_progress, 50)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_calculating_long)).assertExists()
    }

    @Test
    fun guideCalculatingForAFewSecondsHasNoLongCalculationNotice() {
        setGuide(NavigationStatus.Calculating(elapsedSeconds = 2, progress = 0.1))

        composeRule.onNodeWithText(string(R.string.navigation_calculating_long)).assertDoesNotExist()
    }

    @Test
    fun guideUnavailableRouteShowsTheMessageAndRetryNotifiesIt() {
        var retried = 0
        setGuide(NavigationStatus.Unavailable(RouteResult.NotFound), onRetry = { retried++ })

        composeRule.onNodeWithText(string(R.string.navigation_not_found)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_retry)).performClick()

        assertEquals(1, retried)
    }

    @Test
    fun guideRoadNetworkOutsideTheZoneWithoutKnownRegionsHasNoDownload() {
        setGuide(NavigationStatus.Unavailable(RouteResult.NoRoutingData))

        composeRule.onNodeWithText(string(R.string.navigation_outside_routing)).assertExists()
        composeRule.onNodeWithText(string(R.string.navigation_routing_download)).assertDoesNotExist()
    }

    @Test
    fun guideArrivedHasOnlyTheCloseButton() {
        setGuide(NavigationStatus.Arrived)

        composeRule.onNodeWithText(string(R.string.navigation_close)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.navigation_retry)).assertDoesNotExist()
    }

    private companion object {
        // Nessuna mappa: con le regioni vuote non viene nemmeno chiamata, ma lo slot e' obbligatorio.
        val NoMap: @Composable (List<String>, Route?, Float, Float, Modifier) -> Unit = { _, _, _, _, _ -> }

        // La mappa della navigazione non si crea negli stati senza percorso: lo stile non si legge mai.
        val NoTiles = object : OfflineTileSource {
            override fun styleJson(regionId: String, dark: Boolean, language: String, worldFallback: Boolean) = "{}"
            override fun navigationStyleJson(regionIds: List<String>, dark: Boolean, language: String) = "{}"
            override fun sourceKind(regionId: String) = MapSourceKind.NONE
            override fun regionBounds(regionId: String): MapBounds? = null
        }
    }
}
