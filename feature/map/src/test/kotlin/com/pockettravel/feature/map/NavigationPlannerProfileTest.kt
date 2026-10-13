package com.pockettravel.feature.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPlannerProfileTest : NavigationPlannerTestBase() {

    // Il cambio di mezzo puo' partire da dentro una coroutine sul thread principale: l'anteprima deve usare il mezzo
    // appena scelto, non quello di prima.
    @Test
    fun `l'anteprima usa il mezzo appena scelto anche da dentro una coroutine`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        advanceUntilIdle()

        withContext(Dispatchers.Main) { viewModel.setRouteProfile(RouteProfile.BIKE) }
        advanceUntilIdle()

        assertEquals(brouterProfile(RouteProfile.BIKE, false, false).profile, engines.requests.last().profile)
    }

    @Test
    fun `l'anteprima usa la scelta sulle scale appena cambiata anche da dentro una coroutine`() = runTest(main.dispatcher) {
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
}
