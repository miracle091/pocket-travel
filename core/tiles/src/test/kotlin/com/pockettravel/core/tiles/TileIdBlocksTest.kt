package com.pockettravel.core.tiles

import ch.poole.geo.pmtiles.Hilbert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileIdBlocksTest {

    /** L'elenco intero, come lo costruiva l'estrattore prima dei blocchi: tutti gli id del rettangolo, ordinati. */
    private fun allIds(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, minZoom: Int, maxZoom: Int): LongArray {
        val ids = ArrayList<Long>()
        for (zoom in minZoom..maxZoom) {
            val range = tileRangeFor(minLon, minLat, maxLon, maxLat, zoom)
            for (x in range.minX..range.maxX) for (y in range.minY..range.maxY) ids += zoomOffset(zoom) + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
        }
        return ids.toLongArray().also { it.sort() }
    }

    private fun blocks(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, minZoom: Int, maxZoom: Int): List<LongArray> {
        val result = mutableListOf<LongArray>()
        tileIdBlocks(minLon, minLat, maxLon, maxLat, minZoom, maxZoom).forEach { result += it }
        return result
    }

    @Test
    fun `la curva di Hilbert coincide con quella della libreria fino a z15`() {
        for (zoom in 0..15) {
            val last = (1L shl zoom) - 1
            val step = maxOf(1L, last / 37)
            for (x in 0..last step step) for (y in listOf(0L, last / 3, last / 2, last)) {
                assertEquals("tile $zoom/$x/$y", Hilbert.zxyToIndex(zoom, x, y), hilbertIndex(zoom, x, y))
            }
        }
    }

    @Test
    fun `da z16 gli indici superano i 32 bit`() {
        // Ultima tile della curva: 4^16 - 1, che la libreria tronca a 32 bit.
        val last = (1L shl 16) - 1
        assertEquals((1L shl 32) - 1, maxOf(hilbertIndex(16, last, 0), hilbertIndex(16, 0, 0), hilbertIndex(16, last, last), hilbertIndex(16, 0, last)))
    }

    @Test
    fun `i discendenti di una tile hanno id contigui nella curva di Hilbert`() {
        // Ogni tile di z6 ricade nel blocco di 64 id della sua antenata a z3: e' la proprieta' su cui si dividono i blocchi.
        val levels = 3
        for (x in 0 until 64) for (y in 0 until 64) {
            val ancestor = Hilbert.zxyToIndex(6 - levels, (x shr levels).toLong(), (y shr levels).toLong())
            val index = Hilbert.zxyToIndex(6, x.toLong(), y.toLong())
            assertTrue("tile 6/$x/$y", index in (ancestor shl (2 * levels)) until ((ancestor + 1) shl (2 * levels)))
        }
    }

    @Test
    fun `i blocchi messi in fila sono gli stessi id dell'elenco intero, in ordine crescente`() {
        // Rettangolo della Siberia fino a z12, e uno a cavallo di piu' sottoalberi con bordi non allineati.
        val rectangles = listOf(
            doubleArrayOf(60.0, 45.0, 120.0, 82.0),
            doubleArrayOf(-3.7, 40.1, 9.3, 47.2),
            doubleArrayOf(12.44, 43.92, 12.46, 43.94),
            doubleArrayOf(-180.0, -85.0, 180.0, 85.0),
        )
        for (rectangle in rectangles) {
            val maxZoom = if (rectangle[2] - rectangle[0] > 300) 9 else 12
            val joined = blocks(rectangle[0], rectangle[1], rectangle[2], rectangle[3], 0, maxZoom).fold(LongArray(0)) { acc, block -> acc + block }
            assertArrayEquals(rectangle.joinToString(), allIds(rectangle[0], rectangle[1], rectangle[2], rectangle[3], 0, maxZoom), joined)
        }
    }

    @Test
    fun `ogni blocco ha una dimensione limitata e un rettangolo grande ne produce molti`() {
        val blocks = blocks(60.0, 45.0, 120.0, 82.0, 0, 12)
        val maxBlock = blocks.maxOf { it.size }
        assertTrue("blocco di $maxBlock id", maxBlock <= TILE_BLOCK_TARGET + (1 shl 16))
        assertTrue("${blocks.size} blocchi", blocks.size > 10)
        assertEquals(allIds(60.0, 45.0, 120.0, 82.0, 0, 12).size, blocks.sumOf { it.size })
    }

    @Test
    fun `senza zoom nell'intervallo non ci sono blocchi`() {
        assertTrue(blocks(0.0, 0.0, 10.0, 10.0, 5, 4).isEmpty())
    }
}
