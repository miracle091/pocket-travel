package com.pockettravel.feature.map

import com.pockettravel.core.data.JourneyLeg
import com.pockettravel.core.data.TransitJourney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPlannerProfileTest : NavigationPlannerTestBase() {

    // Il cambio di modo puo' partire da dentro una coroutine sul thread principale: l'anteprima del percorso deve usare il modo
    // appena scelto, non quello di prima.
    @Test
    fun `l'anteprima del percorso usa il modo appena scelto anche da dentro una coroutine`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        advanceUntilIdle()

        withContext(Dispatchers.Main) { viewModel.setRouteProfile(RouteProfile.BIKE) }
        advanceUntilIdle()

        assertEquals(brouterProfile(RouteProfile.BIKE, false, false).profile, engines.requests.last().profile)
    }

    @Test
    fun `l'anteprima del percorso usa la scelta sulle scale appena cambiata anche da dentro una coroutine`() = runTest(main.dispatcher) {
        // Le scale contano solo a piedi in sedia a rotelle.
        usageModes.setAccessible(true)
        usageModes.setAllowSteps(false)
        val viewModel = viewModel()
        viewModel.setRouteProfile(RouteProfile.WALK)
        viewModel.plan(colosseo)
        advanceUntilIdle()

        withContext(Dispatchers.Main) { viewModel.setAllowSteps(true) }
        advanceUntilIdle()

        assertEquals(mapOf("allow_steps" to "1"), engines.requests.last().params)
    }

    @Test
    fun `l'anteprima parte dalle opzioni delle Impostazioni e le cambia solo per questo percorso`() = runTest(main.dispatcher) {
        navigationPreferences.setAvoid(setOf(RouteAvoid.TOLLS))
        val viewModel = viewModel()
        viewModel.setRouteProfile(RouteProfile.CAR)
        viewModel.plan(colosseo)
        advanceUntilIdle()
        assertEquals(mapOf("avoid_toll" to "1"), engines.requests.last().params)

        viewModel.setAvoid(RouteAvoid.MOTORWAYS, true)
        advanceUntilIdle()

        assertEquals(mapOf("avoid_toll" to "1", "avoid_motorways" to "1"), engines.requests.last().params)
        assertEquals(setOf(RouteAvoid.TOLLS, RouteAvoid.MOTORWAYS), viewModel.uiState.value.avoid)
        assertEquals("le Impostazioni non cambiano", setOf(RouteAvoid.TOLLS), navigationPreferences.avoid.value)
    }

    @Test
    fun `con un'altra destinazione le opzioni tornano quelle delle Impostazioni`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.setRouteProfile(RouteProfile.CAR)
        viewModel.plan(colosseo)
        viewModel.setAvoid(RouteAvoid.UNPAVED, true)
        advanceUntilIdle()

        // Un'altra destinazione nella stessa regione, per avere di nuovo un calcolo.
        viewModel.plan(NavigationPlace("Pantheon", 41.9, 12.48, "r1"))
        advanceUntilIdle()

        assertEquals(emptySet<RouteAvoid>(), viewModel.uiState.value.avoid)
        assertEquals(emptyMap<String, String>(), engines.requests.last().params)
    }

    @Test
    fun `l'anteprima avvisa se la rete stradale del percorso ha piu' di tre mesi`() = runTest(main.dispatcher) {
        engines.defaultResult = RouteResult.Found(route)
        val old = LocalDate.now().minusMonths(4)
        regions.installedRegions.value = listOf(installedRegion("r1").copy(routingVersion = "%d.%02d.%02d.1.1".format(old.year, old.monthValue, old.dayOfMonth)))
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        advanceUntilIdle()
        assertEquals(old, viewModel.uiState.value.oldRoutingDate)

        val recent = LocalDate.now().minusMonths(1)
        regions.installedRegions.value = listOf(installedRegion("r1").copy(routingVersion = "%d.%02d.%02d.1.1".format(recent.year, recent.monthValue, recent.dayOfMonth)))
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.oldRoutingDate)
    }

    @Test
    fun `«Altri percorsi» calcola due alternative e la scelta diventa il percorso dell'anteprima`() = runTest(main.dispatcher) {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        advanceUntilIdle()
        val ready = viewModel.uiState.value.preview as PlannerPreview.Ready
        assertEquals(OtherRoutes.NotAsked, ready.others)

        val other = route.copy(points = listOf(RoutePoint(45.46, 9.19), RoutePoint(43.0, 11.0), RoutePoint(41.89, 12.49)))
        // Il motore ridà anche il principale tra le alternative: non compare due volte.
        engines.defaultResult = RouteResult.Found(route, listOf(route, other))
        viewModel.findAlternatives()
        advanceUntilIdle()
        assertEquals(2, engines.requests.last().alternatives)
        assertEquals(engines.requests.first().from, engines.requests.last().from)
        val found = viewModel.uiState.value.preview as PlannerPreview.Ready
        assertEquals(OtherRoutes.Found(listOf(route, other)), found.others)

        viewModel.selectRoute(other)
        assertEquals(other, (viewModel.uiState.value.preview as PlannerPreview.Ready).route)

        // Un altro modo ricalcola l'anteprima da capo, senza le alternative di prima.
        viewModel.setRouteProfile(RouteProfile.BIKE)
        advanceUntilIdle()
        assertEquals(PlannerPreview.Ready(route, listOf("r1")), viewModel.uiState.value.preview)
    }

    @Test
    fun `senza un altro percorso resta solo il principale`() = runTest(main.dispatcher) {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        advanceUntilIdle()
        viewModel.findAlternatives()
        advanceUntilIdle()
        assertEquals(OtherRoutes.Found(listOf(route)), (viewModel.uiState.value.preview as PlannerPreview.Ready).others)
    }

    @Test
    fun `il modo Mezzi mostra gli itinerari dei mezzi pubblici e un modo normale lo spegne`() = runTest(main.dispatcher) {
        val leg = JourneyLeg.Walk(null, null, 45.46, 9.19, 41.89, 12.49, Instant.EPOCH, Instant.EPOCH.plusSeconds(600), 700)
        transit.journeys = listOf(TransitJourney(listOf(leg), "mdb-1"))
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        advanceUntilIdle()
        val requestsBefore = engines.requests.size

        viewModel.setTransit()
        advanceUntilIdle()

        assertEquals(PlannerPreview.Transit(transit.journeys), viewModel.uiState.value.preview)
        assertTrue(viewModel.uiState.value.transit)
        assertEquals(true, handle.get<Boolean>("transit"))
        assertEquals("con i mezzi BRouter non calcola", requestsBefore, engines.requests.size)

        viewModel.setRouteProfile(viewModel.uiState.value.routeProfile)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.transit)
        assertTrue(viewModel.uiState.value.preview !is PlannerPreview.Transit)
    }
}
