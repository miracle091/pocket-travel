package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import com.pockettravel.core.data.AddressResult
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.FakeAddressSearchRepository
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationPlannerViewModelTest : NavigationPlannerTestBase() {
    // --- Scelta di partenza e arrivo ---

    @Test
    fun `scegliere la meta la mette tra i recenti e calcola l'anteprima`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        runCurrent()
        viewModel.startSearch(PlannerField.TO)

        viewModel.choose(colosseo)
        runCurrent()

        assertEquals(colosseo, settled(viewModel.uiState).to)
        assertNull(settled(viewModel.uiState).searching)
        assertEquals(listOf(colosseo), recents.places.value)
        assertEquals(PlannerPreview.Calculating(0.0), settled(viewModel.uiState).preview)
    }

    @Test
    fun `senza una scelta del campo, choose sceglie l'arrivo`() = runTest {
        val viewModel = viewModel()

        viewModel.choose(colosseo)

        assertEquals(colosseo, settled(viewModel.uiState).to)
        assertNull(settled(viewModel.uiState).from)
    }

    @Test
    fun `scegliere la partenza non tocca i recenti`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)
        recents.clear()
        viewModel.startSearch(PlannerField.FROM)

        viewModel.choose(duomo)
        runCurrent()

        assertEquals(duomo, settled(viewModel.uiState).from)
        assertEquals(emptyList<NavigationPlace>(), recents.places.value)
        assertEquals(PlannerPreview.Ready(route, listOf("r1")), settled(viewModel.uiState).preview)
    }

    @Test
    fun `Indicazioni dalla mappa imposta la meta dalla propria posizione e chiude la ricerca`() = runTest {
        val viewModel = viewModel()
        viewModel.startSearch(PlannerField.FROM)
        viewModel.choose(duomo)

        viewModel.setDestination(colosseo)

        assertEquals(colosseo, settled(viewModel.uiState).to)
        assertNull(settled(viewModel.uiState).from)
        assertNull(settled(viewModel.uiState).searching)
        assertEquals(colosseo, recents.places.value.first())
    }

    @Test
    fun `la propria posizione come partenza toglie quella scelta`() = runTest {
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)
        viewModel.startSearch(PlannerField.FROM)
        viewModel.choose(duomo)

        viewModel.useMyPosition()

        assertNull(settled(viewModel.uiState).from)
        assertNull(settled(viewModel.uiState).searching)
    }

    @Test
    fun `scambiare partenza e arrivo li inverte, con la propria posizione non fa nulla`() = runTest {
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)
        viewModel.swap()
        assertNull(settled(viewModel.uiState).from)
        assertEquals(colosseo, settled(viewModel.uiState).to)

        viewModel.startSearch(PlannerField.FROM)
        viewModel.choose(duomo)
        viewModel.swap()

        assertEquals(colosseo, settled(viewModel.uiState).from)
        assertEquals(duomo, settled(viewModel.uiState).to)
    }

    @Test
    fun `i recenti sono quelli delle regioni installate e si tolgono uno alla volta o tutti`() = runTest {
        recents.add(colosseo)
        recents.add(NavigationPlace("Lontano", 1.0, 1.0, "r9"))
        recents.add(louvre)
        val viewModel = viewModel()
        runCurrent()
        assertEquals(listOf(louvre, colosseo), settled(viewModel.uiState).recents)

        viewModel.removeRecent(louvre)
        runCurrent()
        assertEquals(listOf(colosseo), settled(viewModel.uiState).recents)

        viewModel.clearRecents()
        runCurrent()
        assertEquals(emptyList<NavigationPlace>(), settled(viewModel.uiState).recents)
    }

    // --- Anteprima ---

    @Test
    fun `senza meta l'anteprima e' inattiva`() = runTest {
        val viewModel = viewModel()
        runCurrent()

        assertEquals(PlannerPreview.Idle, settled(viewModel.uiState).preview)
        assertTrue(engines.requests.isEmpty())
    }

    @Test
    fun `dalla propria posizione senza permesso l'anteprima chiede il permesso`() = runTest {
        gps.permission = false
        val viewModel = viewModel()

        viewModel.setDestination(colosseo)
        runCurrent()

        assertEquals(PlannerPreview.NeedsPermission, settled(viewModel.uiState).preview)
        assertTrue(engines.requests.isEmpty())
    }

    @Test
    fun `concesso il permesso l'anteprima riparte, negato resta la richiesta`() = runTest {
        gps.permission = false
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)
        runCurrent()

        viewModel.onPermissionResult(false)
        runCurrent()
        assertEquals(PlannerPreview.NeedsPermission, settled(viewModel.uiState).preview)

        gps.permission = true
        viewModel.onPermissionResult(true)
        runCurrent()
        assertEquals(PlannerPreview.Calculating(0.0), settled(viewModel.uiState).preview)
        assertEquals(1, gps.listeners)
    }

    @Test
    fun `senza posizione entro venti secondi l'anteprima dice che manca e Riprova la rilegge`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)
        runCurrent()
        assertEquals(PlannerPreview.Calculating(0.0), settled(viewModel.uiState).preview)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(PlannerPreview.NoLocation, settled(viewModel.uiState).preview)

        viewModel.refreshPreview()
        runCurrent()
        gps.emit(fix())
        runCurrent()
        assertEquals(PlannerPreview.Ready(route, listOf("r1")), settled(viewModel.uiState).preview)
    }

    @Test
    fun `dalla posizione GPS il percorso si calcola e l'anteprima e' pronta con le regioni usate`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.load("r1")
        viewModel.setDestination(colosseo)
        runCurrent()

        gps.emit(fix())
        runCurrent()

        assertEquals(PlannerPreview.Ready(route, listOf("r1")), settled(viewModel.uiState).preview)
        val request = engines.requests.single()
        assertEquals(RoutePoint(duomo.latitude, duomo.longitude), request.from)
        assertEquals(colosseo.point, request.to)
        assertEquals("shortest", request.profile)
        assertEquals(listOf(listOf("r1")), engines.created)
        // La posizione trovata serve poi come partenza e per ordinare i risultati.
        assertEquals(RoutePoint(duomo.latitude, duomo.longitude), viewModel.startPoint())
    }

    @Test
    fun `da un posto scelto non serve il GPS e l'avanzamento del motore si vede`() = runTest {
        gps.permission = false
        engines.defaultResult = RouteResult.Found(route)
        engines.progressSteps = listOf(0.4)
        engines.gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)
        viewModel.startSearch(PlannerField.FROM)
        viewModel.choose(duomo)
        runCurrent()

        assertEquals(PlannerPreview.Calculating(0.4), settled(viewModel.uiState).preview)
        engines.gate?.complete(Unit)
        runCurrent()
        assertTrue(settled(viewModel.uiState).preview is PlannerPreview.Ready)
        assertEquals(0, gps.listeners)
    }

    @Test
    fun `gli errori del motore diventano un'anteprima non disponibile con il motivo`() = runTest {
        val viewModel = viewModel()
        viewModel.plan(colosseo)

        for (failure in listOf(RouteResult.NotFound, RouteResult.TimedOut, RouteResult.Failed("boom"), RouteResult.NoRoutingData)) {
            engines.defaultResult = failure
            viewModel.refreshPreview()
            runCurrent()
            assertEquals(PlannerPreview.Unavailable(failure), settled(viewModel.uiState).preview)
        }
    }

    @Test
    fun `fuori dalle regioni con la rete stradale manca la rete senza calcolare`() = runTest {
        regions.installedRegions.value = listOf(installedRegion("r1", "Italia"), installedRegion("r2", "Francia", routing = false))
        val viewModel = viewModel()

        viewModel.plan(louvre)
        runCurrent()

        assertEquals(PlannerPreview.Unavailable(RouteResult.NoRoutingData), settled(viewModel.uiState).preview)
        assertTrue(engines.requests.isEmpty())
    }

    @Test
    fun `installata la rete stradale mancante l'anteprima si ricalcola da sola`() = runTest {
        regions.installedRegions.value = listOf(installedRegion("r1", "Italia", routing = false))
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        runCurrent()
        assertEquals(PlannerPreview.Unavailable(RouteResult.NoRoutingData), settled(viewModel.uiState).preview)

        regions.installedRegions.value = listOf(installedRegion("r1", "Italia", routing = true))
        runCurrent()

        assertEquals(PlannerPreview.Ready(route, listOf("r1")), settled(viewModel.uiState).preview)
    }

    @Test
    fun `cancellare la meta toglie l'anteprima e riparte dalla propria posizione`() = runTest {
        val viewModel = viewModel()
        viewModel.startSearch(PlannerField.FROM)
        viewModel.choose(duomo)
        viewModel.setDestination(colosseo)
        viewModel.setArriveBy(departure)

        viewModel.clearDestination()
        runCurrent()

        assertNull(settled(viewModel.uiState).to)
        assertNull(settled(viewModel.uiState).from)
        assertNull(settled(viewModel.uiState).arriveBy)
        assertEquals(PlannerPreview.Idle, settled(viewModel.uiState).preview)
    }

    // --- Mezzo e accessibilita' ---

    @Test
    fun `il mezzo iniziale segue i modi scelti e cambiarlo ricalcola`() = runTest {
        usageModes.setModes(setOf(UsageMode.AUTO))
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        assertEquals(RouteProfile.CAR, settled(viewModel.uiState).routeProfile)
        viewModel.plan(colosseo)
        runCurrent()
        assertEquals("car-vario", engines.requests.last().profile)

        viewModel.setRouteProfile(RouteProfile.BIKE)
        runCurrent()

        assertEquals(RouteProfile.BIKE, settled(viewModel.uiState).routeProfile)
        assertEquals("trekking", engines.requests.last().profile)
        val calls = engines.requests.size
        viewModel.setRouteProfile(RouteProfile.BIKE)
        runCurrent()
        assertEquals(calls, engines.requests.size)
    }

    @Test
    fun `a piedi in sedia a rotelle si usa il profilo apposta, con i gradini se accettati`() = runTest {
        usageModes.setAccessible(true)
        engines.defaultResult = RouteResult.Found(route)
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        runCurrent()
        assertEquals(BRouterProfile("wheelchair"), settled(viewModel.uiState).routing)
        assertEquals("wheelchair", engines.requests.last().profile)
        assertEquals(emptyMap<String, String>(), engines.requests.last().params)

        viewModel.setAllowSteps(true)
        runCurrent()

        assertTrue(settled(viewModel.uiState).allowSteps)
        assertEquals(mapOf("allow_steps" to "1"), engines.requests.last().params)
    }

    // --- Avviso di partenza ---

    @Test
    fun `l'avviso si programma per la meta con il suo nome e si ricorda`() = runTest {
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)

        viewModel.setReminder(departure)
        runCurrent()

        assertEquals(listOf(departure to "Colosseo"), reminders.scheduled)
        assertEquals(departure, settled(viewModel.uiState).reminder)
        assertEquals(minutes(departure), handle.get<Long>("reminder"))
    }

    @Test
    fun `togliere l'avviso lo cancella una volta sola e senza avviso non fa nulla`() = runTest {
        val viewModel = viewModel()
        viewModel.cancelReminder()
        assertEquals(0, reminders.cancellations)
        viewModel.setDestination(colosseo)
        viewModel.setReminder(departure)

        viewModel.cancelReminder()
        viewModel.cancelReminder()

        assertEquals(1, reminders.cancellations)
        assertNull(settled(viewModel.uiState).reminder)
    }

    @Test
    fun `l'avviso vale per un percorso preciso e cambiandolo si toglie`() = runTest {
        val viewModel = viewModel()
        viewModel.setDestination(colosseo)

        // Stessa destinazione, stessa ora: l'avviso resta.
        viewModel.setReminder(departure)
        viewModel.setDestination(colosseo)
        viewModel.setArriveBy(null)
        viewModel.setRouteProfile(settled(viewModel.uiState).routeProfile)
        assertEquals(departure, settled(viewModel.uiState).reminder)

        // Nuova destinazione, nuova partenza, mezzo, ora di arrivo o gradini: l'avviso non vale piu'.
        val changes: List<() -> Unit> = listOf(
            { viewModel.setDestination(duomo) },
            { viewModel.startSearch(PlannerField.FROM); viewModel.choose(colosseo) },
            { viewModel.setRouteProfile(RouteProfile.BIKE) },
            { viewModel.setArriveBy(departure.plusHours(2)) },
            { viewModel.setAllowSteps(true) },
            { viewModel.clearDestination() },
        )
        changes.forEachIndexed { index, change ->
            viewModel.setReminder(departure)
            change()
            assertNull("cambio $index", settled(viewModel.uiState).reminder)
            assertEquals("cambio $index", index + 1, reminders.cancellations)
        }
    }

    // --- Stato salvato ---

    @Test
    fun `partenza, arrivo, mezzo, ora e avviso si salvano per quando il sistema chiude l'app`() = runTest {
        val viewModel = viewModel()
        viewModel.plan(colosseo)
        viewModel.setRouteProfile(RouteProfile.BIKE)
        viewModel.setArriveBy(departure)
        viewModel.setReminder(departure.minusHours(1))
        runCurrent()

        assertEquals(colosseo, handle.get<String>("to")?.toPlace())
        assertEquals(duomo, handle.get<String>("from")?.toPlace())
        assertEquals("BIKE", handle.get<String>("mode"))
        assertEquals(minutes(departure), handle.get<Long>("arriveBy"))
        assertEquals(minutes(departure.minusHours(1)), handle.get<Long>("reminder"))
    }

    @Test
    fun `con lo stato salvato precompilato il primo stato ha partenza, arrivo e mezzo`() = runTest {
        handle = SavedStateHandle(
            mapOf("from" to duomo.toSavedState(), "to" to colosseo.toSavedState(), "mode" to "BIKE", "arriveBy" to minutes(departure)),
        )

        val state = viewModel().uiState.value

        assertEquals(duomo, state.from)
        assertEquals(colosseo, state.to)
        assertEquals(RouteProfile.BIKE, state.routeProfile)
        assertEquals(departure, state.arriveBy)
    }

    @Test
    fun `ripreso dopo la chiusura il Navigatore ritrova tutto e ricalcola l'anteprima`() = runTest {
        engines.defaultResult = RouteResult.Found(route)
        handle = SavedStateHandle(
            mapOf(
                "from" to duomo.toSavedState(), "to" to colosseo.toSavedState(), "mode" to "CAR",
                "arriveBy" to minutes(departure), "reminder" to minutes(departure.minusHours(1)),
            ),
        )

        val viewModel = viewModel()
        runCurrent()

        assertEquals(duomo, settled(viewModel.uiState).from)
        assertEquals(colosseo, settled(viewModel.uiState).to)
        assertEquals(RouteProfile.CAR, settled(viewModel.uiState).routeProfile)
        assertEquals(departure, settled(viewModel.uiState).arriveBy)
        assertEquals(departure.minusHours(1), settled(viewModel.uiState).reminder)
        assertEquals(PlannerPreview.Ready(route, listOf("r1")), settled(viewModel.uiState).preview)
        assertEquals("car-vario", engines.requests.single().profile)
    }

    // --- Regioni e mappa ---

    @Test
    fun `la mappa mostra la regione aperta, poi quelle di partenza e arrivo`() = runTest {
        val viewModel = viewModel()
        runCurrent()
        // Nessuna regione e nessuna destinazione: tutte quelle installate.
        assertEquals(listOf("r1", "r2"), settled(viewModel.uiState).mapRegionIds)

        viewModel.load("r2")
        viewModel.setDestination(colosseo)
        runCurrent()
        assertEquals(listOf("r2", "r1"), settled(viewModel.uiState).mapRegionIds)

        viewModel.load(null)
        runCurrent()
        assertEquals(listOf("r1"), settled(viewModel.uiState).mapRegionIds)
    }

    @Test
    fun `una regione non installata non entra nella mappa`() = runTest {
        val viewModel = viewModel()

        viewModel.load("r9")
        runCurrent()

        assertEquals(listOf("r1", "r2"), settled(viewModel.uiState).mapRegionIds)
    }

    @Test
    fun `la rete stradale della regione aperta e quella utile al mezzo scelto`() = runTest {
        regions.installedRegions.value = listOf(
            installedRegion("r1", "Italia"), installedRegion("r2", "Francia"), installedRegion("r3", "Spagna", routing = false),
        )
        variants.setInstalledCarOnly("r2", true)
        val viewModel = viewModel()
        runCurrent()
        // A piedi la rete solo auto di r2 non serve.
        assertEquals(setOf("r1"), settled(viewModel.uiState).routingRegionIds)
        assertTrue(settled(viewModel.uiState).routingInstalled)

        viewModel.load("r3")
        runCurrent()
        assertFalse(settled(viewModel.uiState).routingInstalled)
        viewModel.load("r2")
        runCurrent()
        assertFalse(settled(viewModel.uiState).routingInstalled)

        viewModel.setRouteProfile(RouteProfile.CAR)
        runCurrent()
        assertEquals(setOf("r1", "r2"), settled(viewModel.uiState).routingRegionIds)
        assertTrue(settled(viewModel.uiState).routingInstalled)
    }

    // --- Intorno alla posizione ---

    @Test
    fun `senza meta, con GPS e permesso, la posizione e i POI vicini si aggiornano`() = runTest {
        dao.nearestRows += poiRow(1, "Bar Duomo", lat = duomo.latitude, lon = duomo.longitude)
        val viewModel = viewModel()
        runCurrent()
        assertNull(settled(viewModel.uiState).idlePosition)

        gps.emit(fix())
        runCurrent()

        assertEquals(RoutePoint(duomo.latitude, duomo.longitude), settled(viewModel.uiState).idlePosition)
        assertEquals(listOf("Bar Duomo"), settled(viewModel.uiState).nearbyPois?.pois?.map { it.name })
        assertEquals(600, settled(viewModel.uiState).nearbyPois?.radiusMeters)
    }

    @Test
    fun `con una meta o senza GPS la posizione non si ascolta`() = runTest {
        val viewModel = viewModel()
        runCurrent()
        assertEquals(1, gps.listeners)

        viewModel.setDestination(colosseo)
        runCurrent()
        // Resta solo l'ascolto della posizione per l'anteprima.
        assertNull(settled(viewModel.uiState).idlePosition)

        viewModel.clearDestination()
        gps.enabled.value = false
        runCurrent()
        assertNull(settled(viewModel.uiState).idlePosition)
    }
}
