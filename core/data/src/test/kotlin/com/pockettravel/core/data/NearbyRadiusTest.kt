package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyRadiusTest {

    @Test
    fun `in citta' bastano 150 metri`() {
        assertEquals(150, nearbyRadius(listOf(20.0, 50.0, 80.0, 120.0, 149.0, 400.0)))
    }

    @Test
    fun `con meno di 5 POI vicini il raggio si allarga a 300 e poi a 600 metri`() {
        assertEquals(300, nearbyRadius(listOf(100.0, 200.0, 250.0, 290.0, 300.0)))
        assertEquals(600, nearbyRadius(listOf(100.0, 500.0)))
        assertEquals(600, nearbyRadius(emptyList()))
    }
}
