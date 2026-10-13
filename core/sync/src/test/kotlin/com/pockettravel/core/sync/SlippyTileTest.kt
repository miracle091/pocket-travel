package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlippyTileTest {

    @Test
    fun `a zoom 0 tutto il mondo e' una tile`() {
        assertEquals(TileRange(0, 0, 0, 0), tileRangeFor(-180.0, -85.0, 180.0, 85.0, 0))
    }

    @Test
    fun `a zoom 1 il mondo sono quattro tile`() {
        assertEquals(TileRange(0, 1, 0, 1), tileRangeFor(-180.0, -85.0, 180.0, 85.0, 1))
    }

    @Test
    fun `un punto cade nella tile della convenzione OSM`() {
        // Berlino, zoom 10: tile 550/335.
        assertEquals(TileRange(550, 550, 335, 335), tileRangeFor(13.4, 52.5, 13.4, 52.5, 10))
    }

    @Test
    fun `la latitudine massima da' la Y minore`() {
        val range = tileRangeFor(10.0, 40.0, 12.0, 50.0, 6)
        assertTrue(range.minY < range.maxY)
        assertTrue(range.minX < range.maxX)
    }

    @Test
    fun `sul bordo orientale e sui poli i valori restano dentro la griglia`() {
        val zoom = 4
        val last = (1 shl zoom) - 1
        assertEquals(TileRange(last, last, 0, last), tileRangeFor(180.0, -90.0, 180.0, 90.0, zoom))
        assertEquals(TileRange(0, 0, 0, last), tileRangeFor(-180.0, -90.0, -180.0, 90.0, zoom))
    }

    @Test
    fun `longitudine e latitudine fuori scala sono limitate`() {
        val zoom = 3
        val last = (1 shl zoom) - 1
        assertEquals(TileRange(0, last, 0, last), tileRangeFor(-500.0, -200.0, 500.0, 200.0, zoom))
    }

    @Test
    fun `l'equatore e il meridiano zero cadono sulla tile di destra e di sotto`() {
        assertEquals(TileRange(1, 1, 1, 1), tileRangeFor(0.0, 0.0, 0.0, 0.0, 1))
    }
}
