package com.pockettravel.feature.map

import com.pockettravel.core.data.MapBounds
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.feature.map.testing.FakeGpsLocationSource
import com.pockettravel.feature.map.testing.FakeGuidanceServiceStarter
import com.pockettravel.feature.map.testing.FakeNavigationVibration
import com.pockettravel.feature.map.testing.FakeNavigationVoice
import com.pockettravel.feature.map.testing.FakeOfflineTileSource
import com.pockettravel.feature.map.testing.FakeRouteSimulationLog
import com.pockettravel.feature.map.testing.ScriptedRouteEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/** Le tappe durante la guida: arrivo a una tappa, «Salta la tappa», ricalcoli verso le tappe rimaste. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationWaypointsTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val clock = SchedulerClock(main.dispatcher.scheduler)
    private val files = InMemoryPreferencesFactory()
    private val engines = ScriptedRouteEngineFactory()
    private val gps = FakeGpsLocationSource().apply { elapsedRealtime = { clock.millis() } }
    private val variants = RoutingVariantPreferences(files)
    private val preferences = NavigationPreferences(files)
    private val voice = FakeNavigationVoice()
    private val scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    private val controller = NavigationController(
        engines, FakeRegionRepository(listOf(installedRegion("r1", countryCode = "IT"))), gps,
        FakeOfflineTileSource().apply { bounds["r1"] = MapBounds(9.0, 44.0, 11.0, 46.0) },
        UsageModePreferences(files, MapFilterPreferences(files), variants), NavigationSession(), preferences, variants,
        FakeGuidanceServiceStarter(), FakeRouteSimulationLog(),
        NavigationAnnouncer(voice, FakeNavigationVibration(), XmlVoiceTexts("it"), preferences), main.dispatcher, clock, scope,
    )

    // Percorso a L come in NavigationViewModelTest: verso est fino all'angolo, poi verso nord.
    private val origin = RoutePoint(45.0, 10.0)
    private val corner = RoutePoint(45.0, 10.005)
    private val end = RoutePoint(45.004, 10.005)
    private val route = Route(
        points = listOf(origin, corner, end),
        distanceMeters = 838.0,
        durationSeconds = 600.0,
        instructions = listOf(TurnInstruction(TurnType.LEFT, 445.0, 1), TurnInstruction(TurnType.ARRIVE, 0.0, 2)),
    )
    private val destination = NavigationPlace("Destinazione", end.latitude, end.longitude, "r1")
    private val arrival = LocalDateTime.of(2026, 10, 12, 18, 0)

    @After
    fun close() = scope.cancel()

    private fun fix(point: RoutePoint) = GpsFix(point.latitude, point.longitude, 5f, clock.millis())

    @Test
    fun `con una tappa il percorso passa da lei, all'arrivo la voce la dice e la guida prosegue verso la destinazione`() = runTest {
        val stop = NavigationPlace("Angolo", corner.latitude, corner.longitude, "r1")
        engines.defaultResult = RouteResult.Found(route.copy(viaPointIndexes = listOf(1)))
        controller.start("r1", destination, RouteProfile.CAR, arrival, null, waypoints = listOf(stop))
        gps.emit(fix(origin))
        runCurrent()

        assertEquals(listOf(corner), engines.requests.single().via)
        assertEquals(listOf(stop), controller.waypoints.value)
        assertFalse(voice.said.contains("Tappa raggiunta: Angolo"))

        gps.emit(fix(corner))
        runCurrent()

        assertTrue(voice.said.contains("Tappa raggiunta: Angolo"))
        assertEquals(emptyList<NavigationPlace>(), controller.waypoints.value)
        assertEquals("la navigazione salvata non ha piu' la tappa", emptyList<NavigationPlace>(), preferences.activeNavigation(clock.millis())?.waypoints)
        assertEquals("nessun ricalcolo", 1, engines.requests.size)

        gps.emit(fix(end))
        runCurrent()
        assertEquals("Destinazione raggiunta", voice.said.last())
    }

    @Test
    fun `Salta la tappa la toglie e ricalcola dall'ultima posizione verso le altre`() = runTest {
        val first = NavigationPlace("Prima", 45.001, 10.002, "r1")
        val second = NavigationPlace("Seconda", 45.002, 10.003, "r1")
        engines.defaultResult = RouteResult.Found(route)
        controller.start("r1", destination, RouteProfile.CAR, arrival, null, waypoints = listOf(first, second))
        gps.emit(fix(origin))
        runCurrent()

        controller.remainingWaypoints.skip()
        runCurrent()

        assertEquals(listOf(second), controller.waypoints.value)
        assertEquals(listOf(second.point), engines.requests.last().via)
        assertEquals(origin, engines.requests.last().from)
        assertEquals(2, engines.requests.size)
        assertFalse("saltata non si annuncia", voice.said.any { it.startsWith("Tappa raggiunta") })
    }
}
