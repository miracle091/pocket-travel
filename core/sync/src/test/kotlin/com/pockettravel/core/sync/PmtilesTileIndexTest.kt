package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.io.path.createTempDirectory

/**
 * PmtilesTileIndex cammina la directory di un archivio scritto da PmtilesWriter (qui letto da file locale) e
 * restituisce posizione e lunghezza delle sole tile cercate. PmtilesExtractorTest lo esercita solo di riflesso:
 * qui si verificano direttamente le posizioni, le tile assenti, le leaf e le intestazioni non valide.
 */
class PmtilesTileIndexTest {

    private fun tempFile(): File =
        File(createTempDirectory("pocket-travel-pmtiles-tile-index-test").toFile(), "index.pmtiles")

    private fun write(output: File, tiles: Map<Long, ByteArray>) {
        PmtilesWriter.write(
            outputFile = output, entries = tiles.map { (id, data) -> PmtilesEntry(id, data) },
            metadataJson = """{"name":"test"}""",
            tileCompression = Constants.COMPRESSION_GZIP, tileType = Constants.TYPE_MVT,
            minZoom = 0, maxZoom = 12, minLon = 0.0, minLat = 0.0, maxLon = 10.0, maxLat = 10.0,
        )
    }

    /** Sorgente su file locale che conta le letture, come farebbe una sorgente HTTP range. */
    private class CountingSource(file: File) : PmtilesRangeSource, AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        var reads = 0
            private set

        override fun read(position: Long, length: Int): ByteArray {
            reads++
            val bytes = ByteArray(length)
            raf.seek(position)
            raf.readFully(bytes)
            return bytes
        }

        override fun close() = raf.close()
    }

    private fun slice(file: File, offset: Long, length: Int): ByteArray =
        RandomAccessFile(file, "r").use { raf -> ByteArray(length).also { raf.seek(offset); raf.readFully(it) } }

    @Test
    fun `le posizioni trovate puntano ai dati di ciascuna tile e le assenti hanno lunghezza -1`() {
        val file = tempFile()
        val tiles = mapOf(10L to byteArrayOf(1, 2, 3), 11L to byteArrayOf(4, 5), 20L to byteArrayOf(6, 7, 8, 9))
        write(file, tiles)

        CountingSource(file).use { source ->
            val index = PmtilesTileIndex(source)
            val wanted = longArrayOf(5L, 10L, 11L, 15L, 20L, 99L)
            val found = index.locate(wanted)
            assertEquals(wanted.size, found.offsets.size)
            assertEquals(listOf(-1, 3, 2, -1, 4, -1), found.lengths.toList())
            for (i in wanted.indices) {
                val expected = tiles[wanted[i]] ?: continue
                assertArrayEquals("tile ${wanted[i]}", expected, slice(file, found.offsets[i], found.lengths[i]))
            }
        }
    }

    @Test
    fun `tile con lo stesso contenuto e id consecutivi risultano nello stesso punto`() {
        val file = tempFile()
        val sea = ByteArray(30) { 9 }
        val land = ByteArray(30) { it.toByte() }
        write(file, mapOf(1L to sea, 2L to sea, 3L to sea, 4L to land, 8L to sea))

        CountingSource(file).use { source ->
            val found = PmtilesTileIndex(source).locate(longArrayOf(1L, 2L, 3L, 4L, 5L, 8L))
            assertEquals(listOf(30, 30, 30, 30, -1, 30), found.lengths.toList())
            assertEquals(found.offsets[0], found.offsets[1])
            assertEquals(found.offsets[0], found.offsets[2])
            assertEquals(found.offsets[0], found.offsets[5])
            assertArrayEquals(land, slice(file, found.offsets[3], 30))
        }
    }

    @Test
    fun `senza tile cercate si legge solo la root`() {
        val file = tempFile()
        write(file, mapOf(1L to byteArrayOf(1)))
        CountingSource(file).use { source ->
            val index = PmtilesTileIndex(source)
            val found = index.locate(LongArray(0))
            assertEquals(0, found.offsets.size)
            assertEquals(0, found.lengths.size)
            // Header + root directory.
            assertEquals(2, source.reads)
        }
    }

    @Test
    fun `il tipo e la compressione delle tile arrivano dall'header`() {
        val file = tempFile()
        write(file, mapOf(1L to byteArrayOf(1)))
        CountingSource(file).use { source ->
            val index = PmtilesTileIndex(source)
            assertEquals(Constants.COMPRESSION_GZIP, index.tileCompression)
            assertEquals(Constants.TYPE_MVT, index.tileType)
        }
    }

    @Test
    fun `con le leaf directory se ne leggono solo quelle che coprono le tile cercate`() {
        val file = tempFile()
        val random = Random(42)
        // Id sparsi e lunghezze casuali: le directory non si comprimono e la root supera il limite.
        val ids = sortedSetOf<Long>()
        while (ids.size < 20_000) ids += random.nextInt(30_000_000).toLong()
        val tiles = ids.associateWith { id -> ByteArray(1 + random.nextInt(60)) { (id + it).toByte() } }
        write(file, tiles)
        val leafDirLength = ByteBuffer.wrap(slice(file, 0, 127)).order(ByteOrder.LITTLE_ENDIAN).getLong(48)
        assertTrue("l'archivio di prova deve avere leaf directory", leafDirLength > 0)

        CountingSource(file).use { source ->
            val index = PmtilesTileIndex(source)
            val first = tiles.keys.first()
            val last = tiles.keys.last()

            val one = index.locate(longArrayOf(first))
            val readsForOne = source.reads
            assertArrayEquals(tiles.getValue(first), slice(file, one.offsets[0], one.lengths[0]))

            val both = index.locate(longArrayOf(first, last))
            val readsForBoth = source.reads - readsForOne
            assertArrayEquals(tiles.getValue(last), slice(file, both.offsets[1], both.lengths[1]))
            assertEquals(one.offsets[0], both.offsets[0])

            // Header + root + una leaf per una tile; la prima e l'ultima stanno in leaf diverse: root + 2 leaf.
            assertEquals(3, readsForOne)
            assertEquals(3, readsForBoth)
            // Un id oltre l'ultima tile resta assente.
            val beyond = index.locate(longArrayOf(last + 1))
            assertEquals(-1, beyond.lengths[0])
        }
    }

    @Test
    fun `ensureActive e' invocato prima di ogni directory e la sua eccezione interrompe la ricerca`() {
        val file = tempFile()
        write(file, mapOf(1L to byteArrayOf(1)))
        CountingSource(file).use { source ->
            val index = PmtilesTileIndex(source)
            var calls = 0
            index.locate(longArrayOf(1L)) { calls++ }
            assertEquals(1, calls)

            try {
                index.locate(longArrayOf(1L)) { throw IllegalStateException("annullato") }
                fail("attesa IllegalStateException")
            } catch (expected: IllegalStateException) {
                assertEquals("annullato", expected.message)
            }
        }
    }

    @Test
    fun `un file che non e' PMTiles viene rifiutato`() {
        val notPmtiles = PmtilesRangeSource { _, length -> ByteArray(length) { 'x'.code.toByte() } }
        try {
            PmtilesTileIndex(notPmtiles)
            fail("attesa IOException")
        } catch (expected: IOException) {
            // atteso
        }
    }

    @Test
    fun `una versione diversa dalla 3 o una compressione interna sconosciuta vengono rifiutate`() {
        val file = tempFile()
        write(file, mapOf(1L to byteArrayOf(1)))
        val header = slice(file, 0, 127)

        fun open(patch: (ByteArray) -> Unit) {
            val copy = header.copyOf().also(patch)
            PmtilesTileIndex { position, length ->
                if (position == 0L) copy.copyOf(length) else throw IOException("non dovrebbe leggere altro")
            }
        }

        for (patch in listOf<(ByteArray) -> Unit>({ it[7] = 2 }, { it[7] = 4 }, { it[97] = 0 }, { it[97] = 3 })) {
            try {
                open(patch)
                fail("attesa IOException")
            } catch (expected: IOException) {
                // atteso
            }
        }
    }

    @Test
    fun `un errore di lettura della sorgente si propaga come IOException`() {
        val broken = PmtilesRangeSource { _, _ -> throw IOException("rete assente") }
        try {
            PmtilesTileIndex(broken)
            fail("attesa IOException")
        } catch (expected: IOException) {
            assertEquals("rete assente", expected.message)
        }
    }
}
