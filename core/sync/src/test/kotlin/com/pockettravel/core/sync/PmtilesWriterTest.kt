package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Reader
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Verificato via round-trip con ch.poole.geo.pmtiles.Reader, la stessa libreria indipendente
// (BSD-3) gia' usata per leggere la sorgente remota in PmtilesExtractor — nessuna libreria
// scrive questo formato, quindi la compatibilita' col reader indipendente e' la garanzia di
// correttezza qui, non un device/emulatore reale (non sempre disponibile in ogni ambiente).
class PmtilesWriterTest {

    private fun tempFile(): File {
        val dir = createTempDirectory("pocket-travel-pmtiles-writer-test").toFile()
        return File(dir, "map.pmtiles")
    }

    private fun fakeTile(seed: Int): ByteArray = ByteArray(20) { (seed + it).toByte() }

    @Test
    fun `un archivio con poche tile e' leggibile dal reader indipendente (solo root directory)`() {
        val output = tempFile()
        val entries = listOf(
            PmtilesEntry(tileId = 0L, data = fakeTile(1)), // z0,x0,y0
            PmtilesEntry(tileId = 1L, data = fakeTile(2)), // z1,x0,y0
            PmtilesEntry(tileId = 3L, data = fakeTile(3)), // z1,x1,y1
        )

        PmtilesWriter.write(
            outputFile = output,
            entries = entries,
            metadataJson = """{"name":"test","vector_layers":[]}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0,
            maxZoom = 1,
            minLon = 10.0,
            minLat = 42.0,
            maxLon = 12.0,
            maxLat = 44.0,
        )

        Reader(output).use { reader ->
            assertArrayEquals(fakeTile(1), reader.getTile(0, 0, 0))
            assertArrayEquals(fakeTile(2), reader.getTile(1, 0, 0))
            assertArrayEquals(fakeTile(3), reader.getTile(1, 1, 1))
            assertNull(reader.getTile(1, 1, 0)) // tile non inserita: nessun dato
            assertEquals(0, reader.minZoom.toInt())
            assertEquals(1, reader.maxZoom.toInt())
            assertEquals(Constants.COMPRESSION_GZIP, reader.tileCompression)
            assertEquals(Constants.TYPE_MVT, reader.tileType)
            val bounds = reader.bounds
            assertEquals(10.0, bounds[0], 1e-6)
            assertEquals(42.0, bounds[1], 1e-6)
            assertEquals(12.0, bounds[2], 1e-6)
            assertEquals(44.0, bounds[3], 1e-6)
            assertEquals("""{"name":"test","vector_layers":[]}""", reader.metadata)
        }
    }

    @Test
    fun `un archivio con molte tile forza le leaf directory e resta leggibile`() {
        val output = tempFile()
        // zoom 8 ha 65536 tile possibili: ne scriviamo abbastanza da superare il limite della
        // root directory (16 KiB compressi) e forzare almeno una leaf directory.
        val zoom = 8
        val entries = (0 until 6000).map { index ->
            val x = index % 64
            val y = index / 64
            val tileId = zoomOffsetForTest(zoom) + ch.poole.geo.pmtiles.Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
            PmtilesEntry(tileId, fakeTile(index))
        }

        PmtilesWriter.write(
            outputFile = output,
            entries = entries,
            metadataJson = """{"name":"big-test"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = zoom,
            maxZoom = zoom,
            minLon = 0.0,
            minLat = 0.0,
            maxLon = 10.0,
            maxLat = 10.0,
        )

        Reader(output).use { reader ->
            // campiona un sottoinsieme (rileggere tutte le 6000 renderebbe il test lento)
            listOf(0, 1500, 3000, 4500, 5999).forEach { index ->
                val x = index % 64
                val y = index / 64
                assertArrayEquals("tile $index", fakeTile(index), reader.getTile(zoom, x, y))
            }
        }
    }

    private fun zoomOffsetForTest(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L
}
