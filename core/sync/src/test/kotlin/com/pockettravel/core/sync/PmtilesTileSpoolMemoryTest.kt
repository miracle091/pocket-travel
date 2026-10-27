package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Reader
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.path.createTempDirectory

/**
 * Milioni di tile con contenuti tutti diversi (la terra di una regione grande): la memoria trattenuta dallo
 * spool deve restare fissa, perche' l'indice delle voci sta su disco e la tabella dei contenuti ha un tetto.
 */
class PmtilesTileSpoolMemoryTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("pocket-travel-pmtiles-spool-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `milioni di tile distinte si scrivono con memoria trattenuta fissa`() {
        val zoom = 11
        val firstId = ((1L shl (2 * zoom)) - 1L) / 3L
        PmtilesTileSpool(dir).use { tiles ->
            for (i in 0 until DISTINCT_TILES) tiles.add(firstId + i, content(i), contentKey = i.toLong())
            assertEquals(DISTINCT_TILES, tiles.tileCount)
            assertTrue("memoria trattenuta ${tiles.retainedBytes} byte", tiles.retainedBytes < MAX_RETAINED_BYTES)

            val output = File(dir, "map.pmtiles")
            PmtilesWriter.write(
                outputFile = output, tiles = tiles, metadataJson = "{}",
                tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
                minZoom = zoom, maxZoom = zoom, minLon = 0.0, minLat = 0.0, maxLon = 1.0, maxLat = 1.0,
            )
            assertTrue("memoria trattenuta ${tiles.retainedBytes} byte", tiles.retainedBytes < MAX_RETAINED_BYTES)

            Reader(output).use { reader ->
                for (probe in listOf(0, 1, 12_345, 1_000_000, DISTINCT_TILES - 1)) {
                    val (x, y) = xyOf(zoom, firstId + probe)
                    assertArrayEquals("tile $probe", content(probe), reader.getTile(zoom, x, y))
                }
            }
        }
        assertTrue("nessun file temporaneo residuo", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `tile fuori ordine si ordinano a blocchi e danno lo stesso archivio`() {
        val zoom = 9
        val firstId = ((1L shl (2 * zoom)) - 1L) / 3L
        val count = 20_000
        val ids = (0 until count).map { (it.toLong() * 7_919L) % count } // permutazione di 0 until count
        PmtilesTileSpool(dir, sortChunkRecords = 1_000).use { tiles ->
            ids.forEach { tiles.add(firstId + it, content((it % 50).toInt())) }
            val output = File(dir, "map.pmtiles")
            PmtilesWriter.write(
                outputFile = output, tiles = tiles, metadataJson = "{}",
                tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
                minZoom = zoom, maxZoom = zoom, minLon = 0.0, minLat = 0.0, maxLon = 1.0, maxLat = 1.0,
            )
            Reader(output).use { reader ->
                for (id in listOf(0L, 1L, 777L, 12_345L, count - 1L)) {
                    val (x, y) = xyOf(zoom, firstId + id)
                    assertArrayEquals("tile $id", content((id % 50).toInt()), reader.getTile(zoom, x, y))
                }
            }
        }
        assertTrue("nessun file temporaneo residuo", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `tileId duplicati fuori ordine sono rifiutati e non lasciano file temporanei`() {
        try {
            PmtilesTileSpool(dir, sortChunkRecords = 10).use { tiles ->
                for (id in 100L downTo 1L) tiles.add(id, content(1))
                tiles.add(50L, content(2))
                PmtilesWriter.write(
                    outputFile = File(dir, "map.pmtiles"), tiles = tiles, metadataJson = "{}",
                    tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
                    minZoom = 0, maxZoom = 5, minLon = 0.0, minLat = 0.0, maxLon = 1.0, maxLat = 1.0,
                )
            }
            fail("tileId duplicati devono essere rifiutati")
        } catch (expected: IllegalArgumentException) {
            // atteso
        }
        assertTrue("nessun file temporaneo residuo", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `una chiave riusata resta in tabella tra milioni di contenuti irripetibili`() {
        PmtilesTileSpool(dir).use { tiles ->
            val sea = content(-1)
            tiles.add(0L, sea, contentKey = SEA_KEY)
            // Una chiave riusata a ogni giro resta in tabella anche in mezzo a milioni di contenuti irripetibili.
            for (i in 1L..2_000_000L) {
                tiles.add(i * 2, content(i.toInt()), contentKey = i)
                if (i % 1_000L == 0L) tiles.addKnown(i * 2 + 1, SEA_KEY, sea.size)
            }
            assertTrue("memoria trattenuta ${tiles.retainedBytes} byte", tiles.retainedBytes < MAX_RETAINED_BYTES)
            assertTrue(tiles.addKnown(5_000_000L, SEA_KEY, sea.size))
        }
    }

    private fun content(i: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(i).array()

    private fun xyOf(zoom: Int, tileId: Long): Pair<Int, Int> {
        val index = tileId - ((1L shl (2 * zoom)) - 1L) / 3L
        val xy = indexToXY(zoom, index)
        return xy.first.toInt() to xy.second.toInt()
    }

    private companion object {
        const val DISTINCT_TILES = 3_500_000
        const val SEA_KEY = 1L shl 40
        const val MAX_RETAINED_BYTES = 6L * 1024 * 1024
    }
}
