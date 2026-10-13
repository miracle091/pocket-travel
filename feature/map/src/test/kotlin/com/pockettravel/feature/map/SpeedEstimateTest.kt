package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimateTest {
    private val second = 1_000_000_000L

    @Test
    fun `la prima posizione non basta per una velocita'`() {
        val estimate = SpeedEstimate()

        estimate.update(1_000.0, 0)

        assertNull(estimate.speed)
    }

    @Test
    fun `una misura in meno di mezzo secondo si scarta`() {
        val estimate = SpeedEstimate()
        estimate.update(1_000.0, 0)

        estimate.update(995.0, second / 4)

        assertNull(estimate.speed)
    }

    @Test
    fun `una misura di mezzo secondo vale`() {
        val estimate = SpeedEstimate()
        estimate.update(1_000.0, 0)

        estimate.update(995.0, second / 2)

        assertEquals(10.0, requireNotNull(estimate.speed), 0.001)
    }

    @Test
    fun `una velocita' oltre i 70 metri al secondo e' un salto del GPS, non una misura`() {
        val estimate = SpeedEstimate()
        estimate.update(1_000.0, 0)

        estimate.update(900.0, second)

        assertNull(estimate.speed)
    }

    @Test
    fun `70 metri al secondo sono ancora plausibili`() {
        val estimate = SpeedEstimate()
        estimate.update(1_000.0, 0)

        estimate.update(930.0, second)

        assertEquals(70.0, requireNotNull(estimate.speed), 0.001)
    }

    @Test
    fun `dopo una misura scartata la successiva riparte dall'ultima posizione`() {
        val estimate = SpeedEstimate()
        estimate.update(1_000.0, 0)
        // Salto indietro di 200 m (ricalcolo): scartato, ma diventa il nuovo punto di partenza.
        estimate.update(1_200.0, second)

        estimate.update(1_190.0, 2 * second)

        assertEquals(10.0, requireNotNull(estimate.speed), 0.001)
    }

    @Test
    fun `un orologio che non avanza non fa misure`() {
        val estimate = SpeedEstimate()
        estimate.update(1_000.0, 5 * second)

        estimate.update(990.0, 5 * second)
        estimate.update(980.0, 4 * second)

        assertNull(estimate.speed)
    }
}
