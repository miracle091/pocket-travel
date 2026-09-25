package com.pockettravel.pipeline

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFingerprintTest {

    // Fixture reale: le tile z15 del centro di San Marino (build Protomaps).
    private val fixture = File("testdata/san-marino-center-z15.pmtiles")

    @Test
    fun `l'impronta conta le tile del riquadro ed e' stabile`() {
        val first = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)
        val second = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)

        assertTrue(first.tiles > 0)
        assertEquals(first, second)
        assertEquals(64, first.sha256.length)
    }

    @Test
    fun `un riquadro senza tile da' un'impronta diversa`() {
        val sanMarino = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)
        val elsewhere = mapFingerprint(fileRangeReader(fixture), 0.0, 0.0, 0.01, 0.01, 15, 15)

        assertEquals(0, elsewhere.tiles)
        assertNotEquals(sanMarino.sha256, elsewhere.sha256)
    }

    @Test
    fun `un id per ogni tile del riquadro, ordinati`() {
        // A z0 e z1 un riquadro piccolo cade in una sola tile per livello.
        val ids = tileIdsIn(12.44, 43.92, 12.46, 43.94, 0, 1)

        assertEquals(2, ids.size)
        assertEquals(ids.sorted(), ids.toList())
    }
}
