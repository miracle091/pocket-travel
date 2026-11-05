package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLogLinesTest {
    @Test
    fun `un percorso lungo va su piu' righe sotto il limite di logcat, con tutti i punti in ordine`() {
        val points = (0 until 400).map { RoutePoint(-89.123456 + it * 1e-4, -179.123456 + it * 1e-4) }
        val lines = routeLogLines(Route(points, 12797.4, 1492.6))

        assertEquals(listOf("1/3", "2/3", "3/3"), lines.map { it.split(" ")[3] })
        assertTrue(lines.all { it.startsWith("route 12797 1492 ") && it.length < LOGCAT_LINE_CHARS })
        val logged = lines.flatMap { it.split(" ")[4].split(";") }
        assertEquals(points.map { "%.6f,%.6f".format(java.util.Locale.ROOT, it.longitude, it.latitude) }, logged)
    }

    private companion object {
        // Logcat tronca il messaggio di una riga oltre circa 4 KB.
        const val LOGCAT_LINE_CHARS = 4000
    }
}
