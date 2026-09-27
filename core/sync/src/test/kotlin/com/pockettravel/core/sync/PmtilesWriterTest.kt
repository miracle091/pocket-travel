package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Reader
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun `senza tile ripetute il layout su disco resta quello del writer in memoria`() {
        val output = tempFile()
        val tiles = listOf(fakeTile(1), fakeTile(2), fakeTile(3))
        PmtilesWriter.write(
            outputFile = output,
            entries = listOf(PmtilesEntry(0L, tiles[0]), PmtilesEntry(3L, tiles[2]), PmtilesEntry(1L, tiles[1])),
            metadataJson = """{"name":"test"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0,
            maxZoom = 1,
            minLon = 10.0,
            minLat = 42.0,
            maxLon = 12.0,
            maxLat = 44.0,
        )

        val bytes = output.readBytes()
        val header = header(bytes)
        val rootDirOffset = header.getLong(8)
        val rootDirLength = header.getLong(16)
        val metadataOffset = header.getLong(24)
        val metadataLength = header.getLong(32)
        val leafDirOffset = header.getLong(40)
        val tileDataOffset = header.getLong(56)
        val tileDataLength = header.getLong(64)
        // Sezioni contigue nello stesso ordine di prima: header, root, metadata, leaf, dati.
        assertEquals(127L, rootDirOffset)
        assertEquals(rootDirOffset + rootDirLength, metadataOffset)
        assertEquals(metadataOffset + metadataLength, leafDirOffset)
        assertEquals(0L, header.getLong(48)) // nessuna leaf directory
        assertEquals(leafDirOffset, tileDataOffset)
        assertEquals(bytes.size.toLong(), tileDataOffset + tileDataLength)
        assertEquals(3L, header.getLong(72)) // addressed tiles
        assertEquals(3L, header.getLong(80)) // tile entries
        assertEquals(3L, header.getLong(88)) // tile contents
        assertEquals(1, header.get(96).toInt()) // clustered
        // Dati in ordine di tileId, uno dopo l'altro.
        assertArrayEquals(
            tiles[0] + tiles[1] + tiles[2],
            bytes.copyOfRange(tileDataOffset.toInt(), bytes.size),
        )
        val root = rootDirectory(bytes)
        assertArrayEquals(longArrayOf(0L, 1L, 3L), root.ids)
        assertArrayEquals(longArrayOf(1L, 1L, 1L), root.runLengths)
        assertArrayEquals(longArrayOf(20L, 20L, 20L), root.lengths)
        assertArrayEquals(longArrayOf(1L, 0L, 0L), root.encodedOffsets) // offset 0, poi contigui
    }

    @Test
    fun `le tile ripetute sono scritte una volta sola e le consecutive diventano un run`() {
        val output = tempFile()
        val zoom = 2
        val ocean = ByteArray(50) { 7 }
        val land = ByteArray(50) { it.toByte() }
        val entries = mutableListOf<PmtilesEntry>()
        for (x in 0 until 4) for (y in 0 until 4) {
            entries += PmtilesEntry(tileIdForTest(zoom, x, y), if (x == 1 && y == 2) land else ocean)
        }

        PmtilesWriter.write(
            outputFile = output,
            entries = entries,
            metadataJson = """{"name":"dedup"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = zoom,
            maxZoom = zoom,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )

        val bytes = output.readBytes()
        val header = header(bytes)
        assertEquals("solo i due contenuti distinti nella sezione dati", 100L, header.getLong(64))
        assertEquals(16L, header.getLong(72)) // addressed tiles
        assertEquals(2L, header.getLong(88)) // tile contents
        val root = rootDirectory(bytes)
        assertEquals(header.getLong(80), root.ids.size.toLong())
        assertTrue("i run di mare devono ridurre le entry", root.ids.size <= 3)
        assertEquals(16L, root.runLengths.sum())
        Reader(output).use { reader ->
            for (x in 0 until 4) for (y in 0 until 4) {
                val expected = if (x == 1 && y == 2) land else ocean
                assertArrayEquals("tile $x/$y", expected, reader.getTile(zoom, x, y))
            }
        }
    }

    @Test
    fun `tile aggiunte fuori ordine producono un archivio non clustered letto allo stesso modo`() {
        val dir = createTempDirectory("pocket-travel-pmtiles-writer-test").toFile()
        val zoom = 3
        val entries = (0 until 64).map { index ->
            val x = index % 8
            val y = index / 8
            PmtilesEntry(tileIdForTest(zoom, x, y), fakeTile(index % 10)) // contenuti ripetuti
        }
        val sortedOutput = File(dir, "sorted.pmtiles")
        val streamedOutput = File(dir, "streamed.pmtiles")
        PmtilesWriter.write(
            outputFile = sortedOutput, entries = entries, metadataJson = "{}",
            tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
            minZoom = zoom, maxZoom = zoom, minLon = 0.0, minLat = 0.0, maxLon = 1.0, maxLat = 1.0,
        )
        PmtilesTileSpool(dir).use { tiles ->
            // ordine per colonne x, come fa PmtilesExtractor: non e' l'ordine di Hilbert
            entries.forEach { tiles.add(it.tileId, it.data) }
            PmtilesWriter.write(
                outputFile = streamedOutput, tiles = tiles, metadataJson = "{}",
                tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
                minZoom = zoom, maxZoom = zoom, minLon = 0.0, minLat = 0.0, maxLon = 1.0, maxLat = 1.0,
            )
        }

        assertEquals(1, header(sortedOutput.readBytes()).get(96).toInt())
        assertEquals(0, header(streamedOutput.readBytes()).get(96).toInt())
        Reader(sortedOutput).use { sorted ->
            Reader(streamedOutput).use { streamed ->
                for (x in 0 until 8) for (y in 0 until 8) {
                    assertArrayEquals("tile $x/$y", sorted.getTile(zoom, x, y), streamed.getTile(zoom, x, y))
                }
            }
        }
        assertTrue("nessun file temporaneo residuo", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `il file temporaneo delle tile viene cancellato sia a buon fine sia su errore`() {
        val output = tempFile()
        val dir = output.parentFile!!
        fun write(entries: List<PmtilesEntry>) = PmtilesWriter.write(
            outputFile = output, entries = entries, metadataJson = "{}",
            tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
            minZoom = 0, maxZoom = 1, minLon = 0.0, minLat = 0.0, maxLon = 1.0, maxLat = 1.0,
        )

        write(listOf(PmtilesEntry(0L, fakeTile(1)), PmtilesEntry(1L, fakeTile(2))))
        assertEquals(listOf("map.pmtiles"), dir.listFiles()!!.map { it.name })

        try {
            write(listOf(PmtilesEntry(1L, fakeTile(1)), PmtilesEntry(1L, fakeTile(2))))
            fail("tileId duplicati devono essere rifiutati")
        } catch (expected: IllegalArgumentException) {
            // atteso
        }
        assertTrue("nessun file temporaneo residuo", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    private fun header(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes, 0, 127).order(ByteOrder.LITTLE_ENDIAN)

    private class DecodedDirectory(val ids: LongArray, val runLengths: LongArray, val lengths: LongArray, val encodedOffsets: LongArray)

    /** Decodifica indipendente della root directory secondo lo spec (offset lasciati codificati:
     *  0 = contiguo al precedente, altrimenti offset+1), per verificare il formato byte per byte. */
    private fun rootDirectory(bytes: ByteArray): DecodedDirectory {
        val header = header(bytes)
        val raw = GZIPInputStream(ByteArrayInputStream(bytes, header.getLong(8).toInt(), header.getLong(16).toInt())).readBytes()
        var position = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = raw[position++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val count = varint().toInt()
        var lastId = 0L
        val ids = LongArray(count) { lastId += varint(); lastId }
        val runLengths = LongArray(count) { varint() }
        val lengths = LongArray(count) { varint() }
        val encodedOffsets = LongArray(count) { varint() }
        assertEquals("directory letta per intero", raw.size, position)
        return DecodedDirectory(ids, runLengths, lengths, encodedOffsets)
    }

    private fun tileIdForTest(zoom: Int, x: Int, y: Int): Long =
        zoomOffsetForTest(zoom) + ch.poole.geo.pmtiles.Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())

    private fun zoomOffsetForTest(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L
}
