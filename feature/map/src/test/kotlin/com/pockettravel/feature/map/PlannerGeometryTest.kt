package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerGeometryTest {
    @Test
    fun `all'equatore il rettangolo ha lo stesso lato in gradi nei due versi`() {
        val box = boundsAround(RoutePoint(0.0, 10.0), 1_113)
        assertEquals(-0.01, box.minLat, 1e-5)
        assertEquals(0.01, box.maxLat, 1e-5)
        assertEquals(9.99, box.minLon, 1e-5)
        assertEquals(10.01, box.maxLon, 1e-5)
    }

    @Test
    fun `a 60 gradi di latitudine il lato est-ovest in gradi e' il doppio`() {
        val box = boundsAround(RoutePoint(60.0, 0.0), 600)
        val dLat = box.maxLat - 60.0
        val dLon = box.maxLon
        assertEquals(600 / 111_320.0, dLat, 1e-9)
        assertEquals(2 * dLat, dLon, 1e-9)
        assertEquals(-dLon, box.minLon, 1e-9)
    }
}
