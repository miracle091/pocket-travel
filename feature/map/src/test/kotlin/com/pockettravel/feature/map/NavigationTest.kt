package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun `vicino all'inizio del percorso si sta andando verso la partenza, non fuori strada`() {
        // Circa 100 m a ovest della partenza, prima del primo tratto: come una partenza in zona pedonale.
        val progress = tracker.progress(RoutePoint(45.0, 9.9987))

        assertFalse(progress.offRoute)
        assertEquals(TurnType.LEFT, progress.nextInstruction.type)
    }

    @Test
    fun `partendo nella direzione sbagliata, oltre il margine dall'inizio, si e' fuori strada`() {
        // Circa 300 m a ovest della partenza: troppo per una zona pedonale, si ricalcola.
        assertTrue(tracker.progress(RoutePoint(45.0, 9.996)).offRoute)
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
        assertEquals(NavigationUiState.WaitingForFix, state(fix = fix(start, timeMillis = NOW - FIX_MAX_AGE_MILLIS - 1), result = null))
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
        assertEquals(NavigationUiState.Arrived, state(fix = null, arrived = true))
    }

    @Test
    fun `l'arrivo lo decide solo il flag, vicino alla meta senza flag si continua a navigare`() {
        // Con "Spegni il GPS all'arrivo" tolto il ViewModel non segna l'arrivo e la guida resta aperta.
        val atEnd = state(fix = fix(end)) as NavigationUiState.Navigating

        assertTrue(atEnd.progress.arrived)
        assertEquals(NavigationUiState.Arrived, state(fix = fix(end), arrived = true))
    }

    @Test
    fun `senza posizione recente, con il percorso trovato, si resta sull'ultima posizione con il segnale perso`() {
        val stale = fix(RoutePoint(45.0, 10.0025), timeMillis = NOW - FIX_MAX_AGE_MILLIS - 1)

        val lost = state(fix = stale) as NavigationUiState.Navigating

        assertTrue(lost.signalLost)
        assertEquals(RoutePoint(45.0, 10.0025), lost.position)
        assertFalse((state(fix = fix(RoutePoint(45.0, 10.0025))) as NavigationUiState.Navigating).signalLost)
        // Senza percorso (calcolo in corso o errore) non c'e' nulla da seguire: si aspetta il segnale.
        assertEquals(NavigationUiState.WaitingForFix, state(fix = stale, result = null))
    }

    @Test
    fun `il tracker del percorso lo fornisce chi chiama, per non ricostruirlo a ogni posizione`() {
        var requested = 0
        val shared = NavigationTracker(route)
        val result = navigationUiState(
            true, true, fix(start), NOW, false, RouteResult.Found(route), false, trackerFor = { requested++; shared },
        ) as NavigationUiState.Navigating

        assertEquals(1, requested)
        assertEquals(shared.progress(start), result.progress)
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
    fun `con la precisione del GPS peggiore della soglia non si ricalcola`() {
        val offRoute = tracker.progress(RoutePoint(44.999, 10.0025))
        val sinceLong = NOW - RECALCULATION_INTERVAL_MILLIS

        assertTrue(shouldRecalculate(offRoute, false, sinceLong, NOW, accuracyMeters = 30f))
        assertFalse(shouldRecalculate(offRoute, false, sinceLong, NOW, accuracyMeters = 80f))
    }

    @Test
    fun `dopo un'inversione a U la posizione resta sul tratto di ritorno, non su quello d'andata`() {
        // Strada senza uscita: si va a est per 400 m (punti ogni 8 m circa) e si torna sugli stessi punti.
        val out = (0..50).map { RoutePoint(45.0, 10.0 + it * 0.0001) }
        val back = (49 downTo 25).map { RoutePoint(45.0, 10.0 + it * 0.0001) }
        val uTurnRoute = Route(
            out + back, 0.0, 0.0,
            listOf(TurnInstruction(TurnType.U_TURN, 0.0, 50), TurnInstruction(TurnType.ARRIVE, 0.0, out.size + back.size - 1)),
        )
        val uTurnTracker = NavigationTracker(uTurnRoute)
        out.forEach { uTurnTracker.progress(it) }

        back.forEachIndexed { i, point ->
            // Il primo punto dopo la svolta coincide con l'andata entro il margine all'indietro: da li' in poi, il ritorno.
            if (i == 0) return@forEachIndexed
            // Rimane il tratto di ritorno fino all'ultimo punto, non quello dell'andata.
            val expected = NavigationTracker.distanceMeters(point, back.last())
            assertEquals("punto $i del ritorno", expected, uTurnTracker.progress(point).remainingMeters, 1.5)
        }
    }

    @Test
    fun `tornando indietro oltre il margine si cerca su tutto il percorso`() {
        val out = (0..50).map { RoutePoint(45.0, 10.0 + it * 0.0001) }
        val longTracker = NavigationTracker(Route(out, 0.0, 0.0, listOf(TurnInstruction(TurnType.ARRIVE, 0.0, out.lastIndex))))
        longTracker.progress(out[45])

        // Dieci punti (80 m) indietro: oltre il margine, vicino all'ultimo tratto non c'e' nulla; si cerca su tutto il percorso.
        val back = longTracker.progress(out[35])

        assertEquals(NavigationTracker.distanceMeters(out[35], out.last()), back.remainingMeters, 1.0)
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
    private val marino = RoutingRegion("san-marino", MapBounds(12.40, 43.89, 12.52, 43.99))
    private val italia = RoutingRegion("italia", MapBounds(6.6, 35.5, 18.5, 47.1))
    private val francia = RoutingRegion("francia", MapBounds(-5.2, 41.3, 9.6, 51.1))
    private val rimini = RoutePoint(44.059, 12.568)
    private val cittaDiSanMarino = RoutePoint(43.9356, 12.4473)

    @Test
    fun `da Rimini a San Marino le regioni della navigazione sono Italia e San Marino, quella di partenza per prima`() {
        assertEquals(listOf("san-marino", "italia"), navigationRegionIds("san-marino", listOf(italia, marino, francia), rimini, cittaDiSanMarino))
        assertEquals(listOf("italia", "san-marino"), navigationRegionIds("italia", listOf(marino, italia, francia), rimini, cittaDiSanMarino))
    }

    @Test
    fun `dentro una sola regione la navigazione ne usa una sola`() {
        val bologna = RoutePoint(44.4949, 11.3426)
        val firenze = RoutePoint(43.7696, 11.2558)

        assertEquals(listOf("italia"), navigationRegionIds("italia", listOf(marino, italia), bologna, firenze))
    }

    @Test
    fun `un riquadro vicino al confine, entro il margine, e' della navigazione`() {
        // A circa 3 km dal bordo est di San Marino (12.52): dentro il margine di 0,05 gradi.
        val vicino = RoutePoint(43.94, 12.55)
        val lontano = RoutePoint(43.94, 12.60)

        assertEquals(listOf("san-marino", "italia"), navigationRegionIds("san-marino", listOf(marino, italia), RoutePoint(43.94, 12.45), vicino))
        assertEquals(listOf("italia"), navigationRegionIds("italia", listOf(marino, italia), lontano, RoutePoint(44.0, 12.65)))
    }

    @Test
    fun `se nessun riquadro tocca il percorso resta la regione di partenza, come prima`() {
        val parigi = RoutePoint(48.8566, 2.3522)
        val ricerca = listOf(marino, RoutingRegion("senza-mappa", null))

        assertEquals(listOf("italia"), navigationRegionIds("italia", ricerca, parigi, RoutePoint(48.9, 2.4)))
    }

    @Test
    fun `la regione di partenza senza riquadro resta anche quando un'altra tocca il percorso`() {
        val senzaMappa = RoutingRegion("senza-mappa", null)

        assertEquals(
            listOf("senza-mappa", "san-marino"),
            navigationRegionIds("senza-mappa", listOf(marino, senzaMappa), RoutePoint(43.94, 12.45), RoutePoint(43.93, 12.44)),
        )
    }

    @Test
    fun `da San Marino a Riga senza i paesi in mezzo il percorso esce dalle regioni scaricate`() {
        val lettonia = RoutingRegion("lettonia", MapBounds(20.9, 55.6, 28.3, 58.1))
        val riga = RoutePoint(56.952, 24.1147)

        assertTrue(leavesRoutingRegions(listOf(marino, lettonia), cittaDiSanMarino, riga))
    }

    @Test
    fun `da Rimini a San Marino con Italia e San Marino il percorso resta nelle regioni scaricate`() {
        assertFalse(leavesRoutingRegions(listOf(marino, italia), rimini, cittaDiSanMarino))
        // Rimini e' fuori da San Marino di qualche km, oltre il margine: senza l'Italia esce.
        assertTrue(leavesRoutingRegions(listOf(marino), RoutePoint(44.059, 12.65), cittaDiSanMarino))
    }

    @Test
    fun `una regione senza riquadro non permette di giudicare`() {
        assertFalse(leavesRoutingRegions(listOf(marino, RoutingRegion("senza-mappa", null)), cittaDiSanMarino, RoutePoint(56.952, 24.1147)))
    }

    @Test
    fun `i punti della linea partono dalla partenza e arrivano all'arrivo, a passi entro il margine`() {
        val riga = RoutePoint(56.952, 24.1147)
        val points = straightLinePoints(cittaDiSanMarino, riga)

        assertEquals(cittaDiSanMarino, points.first())
        assertEquals(riga, points.last())
        points.zipWithNext().forEach { (a, b) ->
            assertTrue(maxOf(abs(a.latitude - b.latitude), abs(a.longitude - b.longitude)) <= 0.05 + 1e-9)
        }
    }
}
