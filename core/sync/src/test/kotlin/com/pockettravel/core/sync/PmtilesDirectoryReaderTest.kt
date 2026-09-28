package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Hilbert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * PmtilesDirectoryReader legge lo stesso formato scritto da PmtilesWriter camminando la directory
 * (root ed eventuali leaf), non provando le coordinate una per una: qui verificato per round-trip
 * con PmtilesWriter stesso, come PmtilesWriterTest verifica lo stesso file col reader indipendente
 * ch.poole.geo.pmtiles.Reader — quello pero' non enumera la directory (solo getTile(z,x,y)), da cui
 * la necessita' di questa piccola classe (vedi RegionAddressGridInstaller).
 */
class PmtilesDirectoryReaderTest {

    private fun tempFile(): File {
        val dir = createTempDirectory("pocket-travel-pmtiles-directory-reader-test").toFile()
        return File(dir, "addresses.pmtiles")
    }

    private fun zoomOffsetForTest(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L

    private fun tileIdForTest(zoom: Int, x: Int, y: Int): Long = zoomOffsetForTest(zoom) + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())

    private fun fakeTile(seed: Int): ByteArray = ByteArray(20) { (seed + it).toByte() }

    @Test
    fun `round-trip con poche tile, solo root directory`() {
        val output = tempFile()
        val entries = listOf(
            PmtilesEntry(tileIdForTest(2, 0, 0), fakeTile(1)),
            PmtilesEntry(tileIdForTest(2, 1, 1), fakeTile(2)),
            PmtilesEntry(tileIdForTest(2, 3, 2), fakeTile(3)),
        )
        PmtilesWriter.write(
            outputFile = output, entries = entries,
            metadataJson = """{"name":"test","vector_layers":[]}""",
            tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
            minZoom = 2, maxZoom = 2, minLon = 10.0, minLat = 42.0, maxLon = 12.0, maxLat = 44.0,
        )

        PmtilesDirectoryReader(output).use { reader ->
            assertEquals("""{"name":"test","vector_layers":[]}""", reader.metadata)
            assertEquals(Constants.COMPRESSION_GZIP, reader.tileCompression)
            assertEquals(Constants.TYPE_MVT, reader.tileType)
            assertEquals(2, reader.minZoom)
            assertEquals(2, reader.maxZoom)

            val found = mutableMapOf<Long, ByteArray>()
            reader.forEachTile { tileId, data -> found[tileId] = data }

            assertEquals(entries.map { it.tileId }.toSet(), found.keys)
            entries.forEach { assertArrayEquals(it.data, found.getValue(it.tileId)) }
        }
    }

    @Test
    fun `round-trip con molte tile forza le leaf directory`() {
        val output = tempFile()
        val zoom = 8
        val entries = (0 until 6000).map { index ->
            val x = index % 64
            val y = index / 64
            PmtilesEntry(tileIdForTest(zoom, x, y), fakeTile(index))
        }
        PmtilesWriter.write(
            outputFile = output, entries = entries,
            metadataJson = """{"name":"big-test"}""",
            tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
            minZoom = zoom, maxZoom = zoom, minLon = 0.0, minLat = 0.0, maxLon = 10.0, maxLat = 10.0,
        )

        PmtilesDirectoryReader(output).use { reader ->
            val found = mutableMapOf<Long, ByteArray>()
            reader.forEachTile { tileId, data -> found[tileId] = data }

            assertEquals("tutte le 6000 tile, non solo la root", 6000, found.size)
            entries.forEach { assertArrayEquals(it.data, found.getValue(it.tileId)) }
        }
    }

    @Test
    fun `le tile ripetute (runLength maggiore di 1) restituiscono lo stesso contenuto per ogni id`() {
        val output = tempFile()
        val zoom = 2
        val ocean = ByteArray(50) { 7 }
        val land = ByteArray(50) { it.toByte() }
        val entries = mutableListOf<PmtilesEntry>()
        for (x in 0 until 4) for (y in 0 until 4) {
            entries += PmtilesEntry(tileIdForTest(zoom, x, y), if (x == 1 && y == 2) land else ocean)
        }
        PmtilesWriter.write(
            outputFile = output, entries = entries,
            metadataJson = """{"name":"dedup"}""",
            tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
            minZoom = zoom, maxZoom = zoom, minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0,
        )

        PmtilesDirectoryReader(output).use { reader ->
            val found = mutableMapOf<Long, ByteArray>()
            reader.forEachTile { tileId, data -> found[tileId] = data }

            assertEquals(16, found.size)
            for (x in 0 until 4) for (y in 0 until 4) {
                val expected = if (x == 1 && y == 2) land else ocean
                assertArrayEquals("tile $x/$y", expected, found.getValue(tileIdForTest(zoom, x, y)))
            }
        }
    }

    @Test
    fun `indexToXY e' l'inversa di Hilbert zxyToIndex`() {
        for (z in listOf(0, 1, 2, 3, 4, 8, 14)) {
            val size = 1 shl z
            val step = maxOf(1, size / 17) // campiona invece di provare ogni coordinata a z14 (16384x16384)
            var x = 0
            while (x < size) {
                var y = 0
                while (y < size) {
                    val index = Hilbert.zxyToIndex(z, x.toLong(), y.toLong())
                    assertEquals("z=$z x=$x y=$y", x.toLong() to y.toLong(), indexToXY(z, index))
                    y += step
                }
                x += step
            }
        }
    }
}
