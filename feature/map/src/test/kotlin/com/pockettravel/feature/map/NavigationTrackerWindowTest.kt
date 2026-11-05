package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos

class NavigationTrackerWindowTest {
    // Punto a [x] metri verso est e [y] verso nord da (45, 10), con la stessa proiezione piana del tracker.
    private fun at(x: Double, y: Double) = RoutePoint(45.0 + y / METERS_PER_DEGREE, 10.0 + x / (METERS_PER_DEGREE * cos(Math.toRadians(45.0))))

    private fun route(vararg corners: Pair<Double, Double>): Route {
        val points = corners.map { (x, y) -> at(x, y) }
        val turns = (1 until points.lastIndex).map { TurnInstruction(TurnType.LEFT, 0.0, it) }
        return Route(points, 0.0, 0.0, turns + TurnInstruction(TurnType.ARRIVE, 0.0, points.lastIndex))
    }

    private fun total(route: Route) = route.points.zipWithNext { a, b -> NavigationTracker.distanceMeters(a, b) }.sum()

    // A forbice: verso est per 200 m, un giro in senso antiorario, poi verso sud taglia il primo tratto a 100 m dalla partenza.
    private val scissors = route(0.0 to 0.0, 200.0 to 0.0, 200.0 to 100.0, 100.0 to 100.0, 100.0 to -100.0, 300.0 to -100.0)

    // Tornante: 300 m verso est, poi indietro su un tratto parallelo a 25 m.
    private val hairpin = route(0.0 to 0.0, 300.0 to 0.0, 300.0 to 25.0, 0.0 to 25.0)

    // Una posizione al secondo ogni [step] metri lungo il primo tratto (verso est), fino a [untilX] escluso.
    private fun NavigationTracker.driveEast(untilX: Double, step: Double): Long {
        var time = 0L
        var x = 0.0
        while (x < untilX) {
            progress(at(x, 0.0), time)
            x += step
            time += 1_000
        }
        return time
    }

    @Test
    fun `all'incrocio di un percorso a forbice la posizione resta sul primo passaggio`() {
        val tracker = NavigationTracker(scissors, RouteProfile.CAR)
        val time = tracker.driveEast(untilX = 100.0, step = 10.0)

        // All'incrocio il GPS sposta la posizione di 3 m verso sud: sta sul tratto del secondo passaggio, 400 m piu' avanti.
        val crossing = tracker.progress(at(100.0, -3.0), time)

        assertEquals(total(scissors) - 100.0, crossing.remainingMeters, 2.0)
        assertEquals(0, crossing.nextInstructionIndex)
        assertEquals(total(scissors) - 110.0, tracker.progress(at(110.0, 0.0), time + 1_000).remainingMeters, 2.0)
    }

    @Test
    fun `sul tornante la posizione spostata verso il tratto di ritorno resta sull'andata`() {
        val tracker = NavigationTracker(hairpin, RouteProfile.CAR)
        val time = tracker.driveEast(untilX = 150.0, step = 15.0)

        // 15 m dal tratto d'andata, 10 m da quello di ritorno: senza la finestra vincerebbe il ritorno, 325 m piu' avanti.
        val noisy = tracker.progress(at(150.0, 15.0), time)

        assertEquals(total(hairpin) - 150.0, noisy.remainingMeters, 2.0)
        assertEquals(0, noisy.nextInstructionIndex)
    }

    @Test
    fun `a piedi sul tornante di un sentiero la posizione resta sull'andata`() {
        val trail = route(0.0 to 0.0, 100.0 to 0.0, 100.0 to 10.0, 0.0 to 10.0)
        val tracker = NavigationTracker(trail, RouteProfile.WALK)
        val time = tracker.driveEast(untilX = 50.0, step = 1.5)

        val noisy = tracker.progress(at(50.0, 6.0), time)

        assertEquals(total(trail) - 50.0, noisy.remainingMeters, 2.0)
    }

    @Test
    fun `dopo una galleria la finestra si allarga con il tempo passato`() {
        val tracker = NavigationTracker(hairpin, RouteProfile.CAR)
        tracker.progress(at(0.0, 0.0), 0)

        // 30 s senza posizione, poi 250 m piu' avanti: la finestra in avanti si e' allargata fino a li'.
        val after = tracker.progress(at(250.0, 0.0), 30_000)

        assertFalse(after.offRoute)
        assertEquals(total(hairpin) - 250.0, after.remainingMeters, 2.0)
    }

    @Test
    fun `la soglia di fuori percorso dipende dal mezzo`() {
        val straight = route(0.0 to 0.0, 500.0 to 0.0)
        val far = at(100.0, 35.0)

        assertTrue(NavigationTracker(straight, RouteProfile.WALK).progress(far, 0).offRoute)
        assertFalse(NavigationTracker(straight, RouteProfile.CAR).progress(far, 0).offRoute)
    }

    @Test
    fun `il fuori percorso conta le posizioni di fila e si azzera sul percorso`() {
        val straight = route(0.0 to 0.0, 500.0 to 0.0)
        val tracker = NavigationTracker(straight, RouteProfile.CAR)
        tracker.progress(at(0.0, 0.0), 0)

        assertEquals(1, tracker.progress(at(100.0, 60.0), 1_000).offRouteFixes)
        // La stessa posizione con la stessa ora e' la stessa: non conta due volte.
        assertEquals(1, tracker.progress(at(100.0, 60.0), 1_000).offRouteFixes)
        assertEquals(1, tracker.progress(at(100.0, 60.0)).offRouteFixes)
        assertEquals(2, tracker.progress(at(100.0, 60.0), 2_000).offRouteFixes)
        assertEquals(0, tracker.progress(at(110.0, 0.0), 3_000).offRouteFixes)
    }

    @Test
    fun `una guida simulata con il rumore del GPS non esce dal percorso e non salta le svolte`() {
        for ((profile, speed) in listOf(RouteProfile.CAR to 14.0, RouteProfile.BIKE to 5.0, RouteProfile.WALK to 1.4)) {
            for (route in listOf(scissors, hairpin)) for (seed in 1L..SEEDS) simulate(route, profile, speed, seed)
        }
    }

    // Una posizione al secondo lungo [route] a [speed] m/s, con il rumore del GPS dal seme [seed]: nessun ricalcolo,
    // l'indicazione non torna indietro e una svolta conta come superata solo quando la si e' raggiunta davvero.
    private fun simulate(route: Route, profile: RouteProfile, speed: Double, seed: Long) {
        val tracker = NavigationTracker(route, profile)
        val random = Random(seed)
        var lastIndex = 0
        var recalculations = 0
        val length = total(route)
        var travelled = 0.0
        var time = 0L
        while (travelled < length) {
            val exact = pointAlong(route, travelled)
            val noisy = RoutePoint(
                exact.latitude + random.nextGaussian() * NOISE_METERS / METERS_PER_DEGREE,
                exact.longitude + random.nextGaussian() * NOISE_METERS / (METERS_PER_DEGREE * cos(Math.toRadians(45.0))),
            )
            val progress = tracker.progress(noisy, time)
            if (shouldRecalculate(progress, false, lastCalculationMillis = Long.MIN_VALUE / 2, nowMillis = time)) recalculations++
            val where = "$profile, seme $seed, ${route.points.size} punti, a $travelled m (resta ${progress.remainingMeters} m)"
            val index = progress.nextInstructionIndex
            assertTrue("$where: indicazione $index dopo $lastIndex", index >= lastIndex)
            val passedTurn = route.instructions.getOrNull(index - 1)?.let { alongTo(route, it.pointIndex) } ?: 0.0
            assertTrue("$where: svolta a $passedTurn m gia' superata", passedTurn <= travelled + PASSED_TOLERANCE_METERS)
            lastIndex = index
            travelled += speed
            time += 1_000
        }
        assertEquals("$profile, seme $seed: ricalcoli", 0, recalculations)
    }

    // Metri dalla partenza al punto [pointIndex] del percorso.
    private fun alongTo(route: Route, pointIndex: Int) =
        route.points.take(pointIndex + 1).zipWithNext { a, b -> NavigationTracker.distanceMeters(a, b) }.sum()

    // Il punto a [meters] metri dalla partenza lungo il percorso.
    private fun pointAlong(route: Route, meters: Double): RoutePoint {
        var left = meters
        for ((a, b) in route.points.zipWithNext()) {
            val length = NavigationTracker.distanceMeters(a, b)
            if (left <= length) {
                val f = left / length
                return RoutePoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            }
            left -= length
        }
        return route.points.last()
    }

    private companion object {
        const val METERS_PER_DEGREE = 6_371_000.0 * Math.PI / 180
        const val NOISE_METERS = 5.0
        const val SEEDS = 10L
        const val PASSED_TOLERANCE_METERS = 20.0
    }
}
