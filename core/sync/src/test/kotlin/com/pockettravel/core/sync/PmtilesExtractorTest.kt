package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.Reader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.io.path.createTempDirectory
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Serve un file locale via HTTP range request, come farebbe build.protomaps.com — porta in
 * Kotlin lo stesso approccio del dispatcher di test del progetto pmtiles-reader stesso
 * (PMTilesDispatcher.java): risponde 200 con solo i byte richiesti, non 206 (HttpUrlConnectionChannel
 * non controlla il codice di stato, legge solo il corpo).
 */
private class RangeFileDispatcher(private val file: File) : Dispatcher() {
    private val rangePattern = Regex("^bytes=([0-9]+)-([0-9]+)")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val rangeHeader = request.getHeader("Range") ?: return MockResponse().setResponseCode(416)
        val match = rangePattern.find(rangeHeader) ?: return MockResponse().setResponseCode(416)
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val length = (end - start + 1).toInt()
        val buffer = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buffer)
        }
        return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(buffer))
    }
}

class PmtilesExtractorTest {

    private val server = MockWebServer()
    private lateinit var sourceFile: File
    private lateinit var outputFile: File

    @Before
    fun setUp() {
        val dir = createTempDirectory("pocket-travel-pmtiles-extractor-test").toFile()
        sourceFile = File(dir, "source.pmtiles")
        outputFile = File(dir, "map.pmtiles")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fakeTile(z: Int, x: Int, y: Int): ByteArray = byteArrayOf(z.toByte(), x.toByte(), y.toByte())

    private fun tileId(z: Int, x: Int, y: Int): Long =
        ((1L shl (2 * z)) - 1L) / 3L + Hilbert.zxyToIndex(z, x.toLong(), y.toLong())

    @Test
    fun `estrae solo le tile dentro il bounding box dalla sorgente remota`() {
        // Sorgente "remota" whole-planet finta: tile su tutto il pianeta a zoom 0-1, di cui
        // solo una parte cade nel bounding box richiesto (l'emisfero ovest, x=0 a zoom 1).
        val remoteEntries = listOf(
            PmtilesEntry(tileId(0, 0, 0), fakeTile(0, 0, 0)),
            PmtilesEntry(tileId(1, 0, 0), fakeTile(1, 0, 0)), // dentro il bbox richiesto
            PmtilesEntry(tileId(1, 0, 1), fakeTile(1, 0, 1)), // dentro il bbox richiesto
            PmtilesEntry(tileId(1, 1, 0), fakeTile(1, 1, 0)), // fuori dal bbox richiesto
            PmtilesEntry(tileId(1, 1, 1), fakeTile(1, 1, 1)), // fuori dal bbox richiesto
        )
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = remoteEntries,
            metadataJson = """{"name":"whole-planet-fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0,
            maxZoom = 1,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()

        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/whole-planet.pmtiles").toString(),
            minLon = -170.0, minLat = -80.0, maxLon = -10.0, maxLat = 80.0, // emisfero ovest: x=0 a zoom 1
            minZoom = 0, maxZoom = 1,
        )

        PmtilesExtractor().extract(mapSource, outputFile)

        Reader(outputFile).use { reader ->
            assertArrayEquals(fakeTile(0, 0, 0), reader.getTile(0, 0, 0))
            assertArrayEquals(fakeTile(1, 0, 0), reader.getTile(1, 0, 0))
            assertArrayEquals(fakeTile(1, 0, 1), reader.getTile(1, 0, 1))
            assertNull("una tile fuori dal bbox non deve essere stata estratta", reader.getTile(1, 1, 0))
            assertNull("una tile fuori dal bbox non deve essere stata estratta", reader.getTile(1, 1, 1))
        }
    }

    @Test(expected = PmtilesExtractionException::class)
    fun `nessuna tile nel bbox lancia un errore esplicito`() {
        val remoteEntries = listOf(PmtilesEntry(tileId(0, 0, 0), fakeTile(0, 0, 0)))
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = remoteEntries,
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 5,
            maxZoom = 5,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()

        // richiede solo zoom 5, dove la sorgente finta non ha nessuna tile (solo z0 esiste)
        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/whole-planet.pmtiles").toString(),
            minLon = -170.0, minLat = -80.0, maxLon = -10.0, maxLat = 80.0,
            minZoom = 5, maxZoom = 5,
        )

        PmtilesExtractor().extract(mapSource, outputFile)
    }
}
