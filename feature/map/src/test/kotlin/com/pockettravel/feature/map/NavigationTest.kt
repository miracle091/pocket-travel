package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test
    fun `a piedi con disabilita' il profilo in sedia a rotelle, gradini solo se accettati`() {
        assertEquals(RoutingChoice("wheelchair"), routingChoice(TravelMode.WALK, accessible = true, allowSteps = false))
        assertEquals(RoutingChoice("wheelchair", mapOf("allow_steps" to "1")), routingChoice(TravelMode.WALK, accessible = true, allowSteps = true))
        assertTrue(routingChoice(TravelMode.WALK, accessible = true, allowSteps = false).wheelchair)
        assertEquals(RoutingChoice("shortest"), routingChoice(TravelMode.WALK, accessible = false, allowSteps = true))
        // Bici e auto restano quelle del mezzo anche con la casella.
        assertEquals(RoutingChoice("trekking"), routingChoice(TravelMode.BIKE, accessible = true, allowSteps = true))
        assertEquals(RoutingChoice("car-vario"), routingChoice(TravelMode.CAR, accessible = true, allowSteps = false))
    }

    // Percorso a L: verso est lungo il parallelo 45 (circa 393 m), poi a sinistra verso nord (circa 445 m).
    private val start = RoutePoint(45.0, 10.0)
    private val corner = RoutePoint(45.0, 10.005)
    private val end = RoutePoint(45.004, 10.005)
    private val route = Route(
        points = listOf(start, corner, end),
        distanceMeters = 838.0,
        durationSeconds = 600.0,
        instructions = listOf(
            TurnInstruction(TurnType.LEFT, 445.0, 1),
            TurnInstruction(TurnType.ARRIVE, 0.0, 2),
        ),
    )
    private val tracker = NavigationTracker(route)
    private val firstLeg = NavigationTracker.distanceMeters(start, corner)
    private val secondLeg = NavigationTracker.distanceMeters(corner, end)

    private fun fix(point: RoutePoint, timeMillis: Long = NOW) = GpsFix(point.latitude, point.longitude, 5f, timeMillis)

    private fun state(
        permission: Boolean = true,
        gpsEnabled: Boolean = true,
        fix: GpsFix? = fix(start),
        calculating: Boolean = false,
        result: RouteResult? = RouteResult.Found(route),
        arrived: Boolean = false,
    ) = navigationUiState(permission, gpsEnabled, fix, NOW, calculating, result, arrived)

    @Test
    fun `alla partenza la prossima indicazione e' la svolta a sinistra`() {
        val progress = tracker.progress(start)

        assertEquals(TurnType.LEFT, progress.nextInstruction.type)
        assertEquals(firstLeg, progress.distanceToNextMeters, 1.0)
        assertEquals(firstLeg + secondLeg, progress.remainingMeters, 1.0)
        assertFalse(progress.offRoute)
        assertFalse(progress.arrived)
    }

    @Test
    fun `a meta' del primo tratto mancano meta' metri alla svolta`() {
        val progress = tracker.progress(RoutePoint(45.0, 10.0025))

        assertEquals(TurnType.LEFT, progress.nextInstruction.type)
        assertEquals(firstLeg / 2, progress.distanceToNextMeters, 1.0)
    }

    @Test
    fun `dopo la svolta resta solo l'arrivo`() {
        val progress = tracker.progress(RoutePoint(45.002, 10.005))

        assertEquals(TurnType.ARRIVE, progress.nextInstruction.type)
        assertEquals(1, progress.nextInstructionIndex)
        assertEquals(secondLeg / 2, progress.remainingMeters, 1.0)
    }

    @Test
    fun `lontano dal percorso si e' fuori strada`() {
        // Circa 111 m a sud del primo tratto.
        val progress = tracker.progress(RoutePoint(44.999, 10.0025))

        assertTrue(progress.offRoute)
        assertFalse(progress.arrived)
    }

    @Test
    fun `lontano dall'inizio del percorso si sta andando verso la partenza, non fuori strada`() {
        // 300 m a ovest della partenza, prima del primo tratto: come una partenza in zona pedonale.
        val progress = tracker.progress(RoutePoint(45.0, 9.996))

        assertFalse(progress.offRoute)
        assertEquals(TurnType.LEFT, progress.nextInstruction.type)
    }

    @Test
    fun `vicino alla fine si e' arrivati`() {
        assertTrue(tracker.progress(RoutePoint(45.00395, 10.005)).arrived)
    }

    @Test
    fun `senza permesso non si naviga`() {
        assertEquals(NavigationUiState.NeedsPermission, state(permission = false))
    }

    @Test
    fun `con il GPS spento non si naviga, anche con il percorso gia' calcolato`() {
        assertEquals(NavigationUiState.GpsDisabled, state(gpsEnabled = false))
        assertEquals(NavigationUiState.GpsDisabled, state(gpsEnabled = false, arrived = true))
    }

    @Test
    fun `senza una posizione GPS recente si aspetta il segnale`() {
        assertEquals(NavigationUiState.WaitingForFix, state(fix = null))
        assertEquals(NavigationUiState.WaitingForFix, state(fix = fix(start, timeMillis = NOW - FIX_MAX_AGE_MILLIS - 1)))
    }

    @Test
    fun `con GPS e posizione si naviga`() {
        val navigating = state(fix = fix(RoutePoint(45.0, 10.0025))) as NavigationUiState.Navigating

        assertEquals(TurnType.LEFT, navigating.progress.nextInstruction.type)
        assertEquals(route, navigating.route)
    }

    @Test
    fun `calcolo in corso, percorso non trovato e arrivo`() {
        assertEquals(NavigationUiState.Calculating(0), state(calculating = true, result = null))
        assertEquals(NavigationUiState.Calculating(0), state(result = null))
        assertEquals(NavigationUiState.Unavailable(RouteResult.NoRoutingData), state(result = RouteResult.NoRoutingData))
        assertEquals(NavigationUiState.Arrived, state(fix = fix(end)))
        assertEquals(NavigationUiState.Arrived, state(fix = null, arrived = true))
    }

    @Test
    fun `si ricalcola fuori strada, non piu' di una volta ogni intervallo`() {
        val offRoute = tracker.progress(RoutePoint(44.999, 10.0025))
        val onRoute = tracker.progress(start)

        assertTrue(shouldRecalculate(offRoute, calculating = false, lastCalculationMillis = NOW - RECALCULATION_INTERVAL_MILLIS, nowMillis = NOW))
        assertFalse(shouldRecalculate(offRoute, calculating = false, lastCalculationMillis = NOW - 1_000, nowMillis = NOW))
        assertFalse(shouldRecalculate(offRoute, calculating = true, lastCalculationMillis = 0, nowMillis = NOW))
        assertFalse(shouldRecalculate(onRoute, calculating = false, lastCalculationMillis = 0, nowMillis = NOW))
    }

    @Test
    fun `il tratto evidenziato va dalla posizione alla prossima svolta`() {
        val midFirstLeg = tracker.progress(RoutePoint(45.0001, 10.0025))

        assertEquals(RoutePoint(45.0, 10.0025).latitude, midFirstLeg.positionOnRoute.latitude, 1e-9)
        assertEquals(listOf(corner), midFirstLeg.pathToNext.drop(1))

        val afterTurn = tracker.progress(RoutePoint(45.002, 10.005))
        assertEquals(end, afterTurn.pathToNext.last())
    }

    @Test
    fun `durante il calcolo si contano i secondi e la stima dell'avanzamento`() {
        assertEquals(
            NavigationUiState.Calculating(12, 0.4),
            navigationUiState(
                true, true, fix(start), NOW, calculating = true, routeResult = null, arrived = false,
                calculationStartedMillis = NOW - 12_500, calculationProgress = 0.4,
            ),
        )
    }

    @Test
    fun `tempo scaduto e mezzo iniziale dalla modalita' d'uso`() {
        assertEquals(NavigationUiState.Unavailable(RouteResult.TimedOut), state(result = RouteResult.TimedOut))
        assertEquals(TravelMode.CAR, TravelMode.from(UsageMode.CAMPER))
        assertEquals(TravelMode.BIKE, TravelMode.from(UsageMode.BICI))
        assertEquals(TravelMode.WALK, TravelMode.from(UsageMode.ESCURSIONISMO))
        assertEquals(TravelMode.WALK, TravelMode.from(null))
    }

    @Test
    fun `il nome della strada e' quello in cui si entra dopo la svolta`() {
        // Due strade della mappa: quella del primo tratto (verso est) e quella del secondo (verso nord).
        val roads = listOf(
            NamedRoad("Via Est", listOf(RoutePoint(45.0, 9.99), RoutePoint(45.0, 10.006))),
            NamedRoad("Via Nord", listOf(RoutePoint(44.999, 10.005), RoutePoint(45.005, 10.005))),
        )
        val leftTurn = route.instructions.first()

        val probe = NavigationTracker.streetProbe(route, leftTurn)!!

        assertEquals("Via Nord", NavigationTracker.nearestRoadName(probe, roads))
        assertEquals(null, NavigationTracker.streetProbe(route, route.instructions.last()))
    }

    @Test
    fun `distanza lungo il percorso tra due svolte`() {
        assertEquals(secondLeg, tracker.distanceBetween(1, 2), 1.0)
        assertEquals(firstLeg + secondLeg, tracker.distanceBetween(0, 2), 1.0)
        assertEquals(0.0, tracker.distanceBetween(2, 0), 0.0)
    }

    @Test
    fun `nessun nome se la strada piu' vicina e' troppo lontana`() {
        val far = listOf(NamedRoad("Via Lontana", listOf(RoutePoint(45.01, 10.0), RoutePoint(45.01, 10.01))))

        assertEquals(null, NavigationTracker.nearestRoadName(RoutePoint(45.0, 10.0025), far))
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
