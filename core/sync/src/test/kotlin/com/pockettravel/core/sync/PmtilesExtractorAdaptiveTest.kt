package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.Reader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.random.Random

/** Blocchi adattivi e rilevamento dei trasferimenti fermi nell'estrazione della mappa (soglie ridotte per tenere i test brevi). */
class PmtilesExtractorAdaptiveTest {

    private val server = MockWebServer()
    private val dir = createTempDirectory("pocket-travel-pmtiles-adaptive-test").toFile()
    private val sourceFile = File(dir, "source.pmtiles")
    private val outputFile = File(dir, "map.pmtiles")

    /** Richieste delle sole tile (dopo header e directory): intervallo e numero d'ordine sulla connessione. */
    private class TileRequest(val range: LongRange, val sequenceNumber: Int)

    private val tileRequests = CopyOnWriteArrayList<TileRequest>()
    private var tileDataStart = 0L

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun tileId(z: Int, x: Int, y: Int): Long =
        ((1L shl (2 * z)) - 1L) / 3L + Hilbert.zxyToIndex(z, x.toLong(), y.toLong())

    /** Scrive la sorgente con [count] tile di [size] byte casuali negli id consecutivi di z3 (tutte dentro il rettangolo di [mapSource]). */
    private fun writeSource(count: Int, size: Int, zoom: Int): List<ByteArray> {
        val random = Random(7)
        val first = tileId(zoom, 0, 0)
        val tiles = List(count) { random.nextBytes(size) }
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = tiles.mapIndexed { i, data -> PmtilesEntry(first + i, data) },
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = zoom, maxZoom = zoom,
            minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0,
        )
        // I dati delle tile sono in fondo al file.
        tileDataStart = sourceFile.length() - count.toLong() * size
        return tiles
    }

    /** Serve la sorgente; [shape] puo' rallentare la risposta di una richiesta di tile (numero d'ordine da 1, tra le tile). */
    private fun serve(shape: (tileRequestNumber: Int, MockResponse) -> MockResponse = { _, response -> response }) {
        val inner = RangeFileDispatcher(sourceFile)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = inner.dispatch(request)
                val range = inner.requestedRanges.last()
                if (range.first < tileDataStart) return response
                tileRequests += TileRequest(range, request.sequenceNumber)
                return shape(tileRequests.size, response)
            }
        }
        server.start()
    }

    private fun mapSource(zoom: Int) = MapExtractionSource(
        sourceUrl = server.url("/planet.pmtiles").toString(),
        minLon = -170.0, minLat = -80.0, maxLon = 170.0, maxLat = 80.0, minZoom = zoom, maxZoom = zoom,
    )

    private fun lengths() = tileRequests.map { it.range.last - it.range.first + 1 }

    /** Le tile estratte (quelle dentro il rettangolo: non le righe polari) sono quelle della sorgente, almeno [minimum]. */
    private fun assertOutputHas(tiles: List<ByteArray>, zoom: Int, minimum: Int) {
        val side = 1 shl zoom
        var found = 0
        Reader(outputFile).use { reader ->
            for (x in 0 until side) {
                for (y in 0 until side) {
                    val index = tileId(zoom, x, y) - tileId(zoom, 0, 0)
                    val data = reader.getTile(zoom, x, y)
                    if (data != null) {
                        assertArrayEquals(tiles[index.toInt()], data)
                        found++
                    }
                }
            }
        }
        assertTrue("tile estratte: $found", found >= minimum)
    }

    @Test(timeout = 60_000)
    fun `su un server veloce i blocchi crescono fino al massimo`() {
        val tiles = writeSource(count = 256, size = 2_000, zoom = 4)
        serve()
        val tuning = RangeTuning(
            initialBatchBytes = 16_000, minBatchBytes = 4_000, maxBatchBytes = 200_000, targetBatchMs = 1_000, minSampleBytes = 4_000,
        )

        newExtractor(tuning = tuning).extract(mapSource(4), outputFile)

        val sizes = lengths()
        assertTrue("primo blocco vicino all'iniziale: $sizes", sizes.first() in 14_000L..16_000L)
        assertTrue("blocchi cresciuti: $sizes", sizes.drop(1).max() >= 150_000L)
        assertTrue("mai oltre il massimo: $sizes", sizes.all { it <= 200_000L })
        assertOutputHas(tiles, 4, minimum = 200)
    }

    @Test(timeout = 60_000)
    fun `su un server lento i blocchi si restringono`() {
        val tiles = writeSource(count = 64, size = 1_000, zoom = 3)
        // 4096 byte ogni 100 ms: circa 40 KB/s.
        serve { _, response -> response.throttleBody(4_096, 100, TimeUnit.MILLISECONDS) }
        val tuning = RangeTuning(
            initialBatchBytes = 16_000, minBatchBytes = 2_000, maxBatchBytes = 64_000, targetBatchMs = 250, minSampleBytes = 2_000,
        )

        newExtractor(tuning = tuning).extract(mapSource(3), outputFile)

        val sizes = lengths()
        assertTrue("primo blocco vicino all'iniziale: $sizes", sizes.first() in 14_000L..16_000L)
        assertTrue("blocchi ristretti: $sizes", sizes.drop(1).all { it <= 14_000L })
        assertOutputHas(tiles, 3, minimum = 60)
    }

    private val stallTuning = RangeTuning(
        initialBatchBytes = 32_000, minBatchBytes = 32_000, maxBatchBytes = 32_000, minSampleBytes = 10_000,
        stallWindowMs = 300, stallFloorBytesPerSecond = 100, stallRatio = 0.1, minSamplesForRelative = 1,
    )

    @Test(timeout = 60_000)
    fun `una richiesta a goccia dopo una veloce si interrompe e si ritenta su una connessione nuova`() {
        val tiles = writeSource(count = 4, size = 20_000, zoom = 3)
        // La seconda richiesta di tile e' a circa 10 KB/s; il ritentativo e le altre sono a piena velocita'.
        serve { number, response -> if (number == 2) response.throttleBody(1_000, 100, TimeUnit.MILLISECONDS) else response }

        newExtractor(retryDelaysMs = listOf(10L, 10L), tuning = stallTuning).extract(mapSource(3), outputFile)

        val requests = tileRequests.toList()
        assertEquals("tile 1, tile 2 interrotta, tile 2 ritentata, tile 3, tile 4", 5, requests.size)
        assertEquals(requests[1].range, requests[2].range)
        assertTrue("la seconda richiesta era su una connessione gia' usata", requests[1].sequenceNumber > 0)
        assertEquals("il ritentativo apre una connessione nuova", 0, requests[2].sequenceNumber)
        assertOutputHas(tiles, 3, minimum = 4)
    }

    @Test(timeout = 60_000)
    fun `un server lento in modo uniforme non e' considerato fermo`() {
        val tiles = writeSource(count = 4, size = 20_000, zoom = 3)
        serve { _, response -> response.throttleBody(4_096, 100, TimeUnit.MILLISECONDS) }

        newExtractor(tuning = stallTuning).extract(mapSource(3), outputFile)

        val ranges = tileRequests.map { it.range }
        assertEquals("nessuna richiesta ripetuta", ranges.size, ranges.distinct().size)
        assertEquals(4, ranges.size)
        assertOutputHas(tiles, 3, minimum = 4)
    }

    @Test(timeout = 60_000)
    fun `un'attesa delle intestazioni piu' lunga della finestra non e' un fermo`() {
        val tiles = writeSource(count = 4, size = 20_000, zoom = 3)
        // Le intestazioni della seconda richiesta arrivano dopo due finestre intere; il corpo poi va a piena velocita'.
        serve { number, response -> if (number == 2) response.setHeadersDelay(700, TimeUnit.MILLISECONDS) else response }

        newExtractor(tuning = stallTuning).extract(mapSource(3), outputFile)

        val ranges = tileRequests.map { it.range }
        assertEquals("nessuna richiesta ripetuta", ranges.size, ranges.distinct().size)
        assertEquals(4, ranges.size)
        assertOutputHas(tiles, 3, minimum = 4)
    }

    @Test(timeout = 60_000)
    fun `i fermi ripetuti esauriscono i ritentativi e la lettura fallisce`() {
        writeSource(count = 4, size = 20_000, zoom = 3)
        // La seconda richiesta e' a circa 10 KB/s (fermo rispetto alla media); il ritentativo a 10 byte/s, sotto la soglia assoluta.
        serve { number, response ->
            when (number) {
                1 -> response
                2 -> response.throttleBody(1_000, 100, TimeUnit.MILLISECONDS)
                else -> response.throttleBody(1, 100, TimeUnit.MILLISECONDS)
            }
        }

        val error = runCatching { newExtractor(retryDelaysMs = listOf(10L), tuning = stallTuning).extract(mapSource(3), outputFile) }
            .exceptionOrNull()

        assertTrue(error.toString(), generateSequence(error) { it.cause }.any { it is StalledReadException })
        assertEquals("tile 1, tile 2 e un ritentativo", 3, tileRequests.size)
    }
}
