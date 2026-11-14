package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Quello che serve alla mappa della guida: direzione di marcia, tratto raggiunto, arrivo secondo il mezzo. */
class NavigationTrackerGuidanceTest {
    // Punto a [x] metri verso est e [y] verso nord da (45, 10), con la stessa proiezione piana del tracker.
    private fun at(x: Double, y: Double) = RoutePoint(45.0 + y / METERS_PER_DEGREE, 10.0 + x / (METERS_PER_DEGREE * cos(Math.toRadians(45.0))))

    // 100 m verso est, poi 100 m verso nord.
    private val corner = Route(listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0)), 200.0, 100.0)

    @Test
    fun `la direzione di marcia segue il percorso davanti alla posizione`() {
        assertEquals(90.0, NavigationTracker(corner).progress(at(20.0, 0.0)).bearingDegrees, 1.0)
        assertEquals(0.0, NavigationTracker(corner).progress(at(100.0, 50.0)).bearingDegrees, 1.0)
    }

    @Test
    fun `poco prima dell'angolo la direzione guarda gia' oltre la svolta`() {
        // A 10 m dall'angolo il punto 30 m avanti sta 20 m dopo la svolta, verso nord: tra est (90) e nord (0).
        val bearing = NavigationTracker(corner).progress(at(90.0, 0.0)).bearingDegrees

        assertTrue("$bearing", bearing > 10.0 && bearing < 80.0)
    }

    @Test
    fun `il tratto raggiunto e' quello della posizione`() {
        assertEquals(0, NavigationTracker(corner).progress(at(50.0, 0.0)).segmentIndex)
        assertEquals(1, NavigationTracker(corner).progress(at(100.0, 50.0)).segmentIndex)
    }

    @Test
    fun `a piedi si arriva piu' vicino alla meta che in auto`() {
        val position = at(100.0, 75.0) // 25 m dalla fine

        assertTrue(NavigationTracker(corner, RouteProfile.CAR).progress(position).arrived)
        assertFalse(NavigationTracker(corner, RouteProfile.WALK).progress(position).arrived)
        assertTrue(NavigationTracker(corner, RouteProfile.WALK).progress(at(100.0, 90.0)).arrived)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_194.9
    }
}
