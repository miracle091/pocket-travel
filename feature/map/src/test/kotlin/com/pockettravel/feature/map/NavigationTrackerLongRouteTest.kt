package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTrackerLongRouteTest {
    // Percorso dritto verso est di 50k punti (circa 300 km), un'indicazione di arrivo in fondo.
    private val points = (0 until POINTS).map { RoutePoint(45.0, 10.0 + it * STEP_DEGREES) }
    private val route = Route(
        points = points,
        distanceMeters = 0.0,
        durationSeconds = 0.0,
        instructions = listOf(TurnInstruction(TurnType.ARRIVE, 0.0, POINTS - 1)),
    )

    @Test
    fun `il costo per posizione non cresce con la lunghezza del percorso`() {
        val tracker = NavigationTracker(route)
        val total = NavigationTracker.distanceMeters(points.first(), points.last())
        val started = System.nanoTime()
        var last: NavigationProgress? = null
        repeat(CALLS) { call -> last = tracker.progress(points[call * 10]) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        val progress = requireNotNull(last)
        assertFalse(progress.offRoute)
        assertEquals(total - NavigationTracker.distanceMeters(points.first(), points[(CALLS - 1) * 10]), progress.remainingMeters, 1.0)
        // Con la scansione di tutto il percorso: 50M proiezioni, decine di secondi; con la finestra, meno di un secondo.
        assertTrue("$elapsedMillis ms", elapsedMillis < MAX_MILLIS)
    }

    @Test
    fun `una posizione oltre la finestra in avanti si trova comunque`() {
        val tracker = NavigationTracker(route)
        tracker.progress(points[0])
        val jump = points[POINTS / 2]

        val progress = tracker.progress(jump)

        assertFalse(progress.offRoute)
        assertEquals(NavigationTracker.distanceMeters(jump, points.last()), progress.remainingMeters, 1.0)
    }

    private companion object {
        const val POINTS = 50_000
        const val CALLS = 1_000
        const val STEP_DEGREES = 0.00008
        const val MAX_MILLIS = 3_000
    }
}
