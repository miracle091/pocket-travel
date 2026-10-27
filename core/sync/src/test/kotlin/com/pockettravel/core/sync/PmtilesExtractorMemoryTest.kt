package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Reader
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory

/**
 * Estrazione su un rettangolo enorme (tutto il pianeta da z0 a z12, circa 22,4 milioni di tile, piu' di
 * `russia-siberia` a z13): la memoria usata dipende dal numero di voci distinte della build, non dal
 * numero di tile del rettangolo. Il test JVM ha 512 MB di heap: con un indice di circa 40 byte per
 * tile (ids + posizioni remote + spool) l'estrazione esaurirebbe l'heap.
 */
class PmtilesExtractorMemoryTest {

    private val server = MockWebServer()
    private lateinit var sourceFile: File
    private lateinit var outputFile: File

    @Before
    fun setUp() {
        val dir = createTempDirectory("pocket-travel-pmtiles-memory-test").toFile()
        sourceFile = File(dir, "source.pmtiles")
        outputFile = File(dir, "map.pmtiles")
    }

    @After
    fun tearDown() {
        server.shutdown()
        sourceFile.parentFile?.deleteRecursively()
    }

    @Test
    fun `un rettangolo di 22 milioni di tile si estrae restando nell'heap`() {
        // Build finta: tutte le tile da z0 a z12 puntano allo stesso contenuto (una sola voce di directory con runLength).
        val content = byteArrayOf(1, 2, 3)
        val tileCount = (1L shl (2 * (MAX_ZOOM + 1))).minus(1L) / 3L
        writeSingleRunArchive(sourceFile, tileCount, content, MAX_ZOOM)
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()
        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/planet.pmtiles").toString(),
            minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0, minZoom = 0, maxZoom = MAX_ZOOM,
        )

        val stats = newExtractor().extract(mapSource, outputFile)

        assertEquals(1, stats.tilesDownloaded)
        assertEquals(MAX_ZOOM, stats.maxZoom)
        Reader(outputFile).use { reader ->
            assertArrayEquals(content, reader.getTile(MAX_ZOOM, 1234, 3210))
            assertArrayEquals(content, reader.getTile(0, 0, 0))
        }
    }

    @Test
    fun `la mappa leggera non tocca l'ultimo zoom`() {
        val content = byteArrayOf(1, 2, 3)
        writeSingleRunArchive(sourceFile, ((1L shl 12) - 1L) / 3L, content, 5) // tutte le tile di z0-z5
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()
        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/planet.pmtiles").toString(),
            minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0, minZoom = 0, maxZoom = 5,
        )

        val stats = newExtractor().extract(mapSource, outputFile, detail = MapDetail.LIGHT)

        assertEquals(4, stats.maxZoom)
        Reader(outputFile).use { reader ->
            assertArrayEquals(content, reader.getTile(4, 3, 3))
            org.junit.Assert.assertNull(reader.getTile(5, 3, 3))
        }
    }

    private companion object {
        const val MAX_ZOOM = 12
    }
}

/** Archivio PMTiles v3 minimo: una sola voce di directory (tileId 0, [runLength] tile) che punta a [content]. */
internal fun writeSingleRunArchive(file: File, runLength: Long, content: ByteArray, maxZoom: Int) {
    fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
    fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    val directory = ByteArrayOutputStream().also { out ->
        varint(out, 1) // una voce
        varint(out, 0) // tileId
        varint(out, runLength)
        varint(out, content.size.toLong())
        varint(out, 1) // offset 0, codificato +1
    }.toByteArray().let(::gzip)
    val metadata = gzip("""{"name":"fake"}""".toByteArray())
    val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
    header.put(byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73)).put(3)
    header.putLong(127).putLong(directory.size.toLong())
    header.putLong(127L + directory.size).putLong(metadata.size.toLong())
    header.putLong(0).putLong(0) // nessuna leaf
    header.putLong(127L + directory.size + metadata.size).putLong(content.size.toLong())
    header.putLong(runLength).putLong(1).putLong(1)
    header.put(1).put(2).put(2).put(1) // clustered, gzip interno, tile gzip, MVT
    header.put(0).put(maxZoom.toByte())
    header.putInt(-1_800_000_000).putInt(-850_000_000).putInt(1_800_000_000).putInt(850_000_000)
    header.put(0).putInt(0).putInt(0)
    file.writeBytes(header.array() + directory + metadata + content)
}
