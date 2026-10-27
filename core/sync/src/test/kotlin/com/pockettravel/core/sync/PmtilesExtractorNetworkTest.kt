package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Reader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

/** Lettura remota della mappa: timeout, annullamento, tipo di risposta e ritentativi della singola richiesta. */
class PmtilesExtractorNetworkTest {

    private val server = MockWebServer()
    private lateinit var sourceFile: File
    private lateinit var outputFile: File
    private val tile = byteArrayOf(1, 2, 3)

    @Before
    fun setUp() {
        val dir = createTempDirectory("pocket-travel-pmtiles-network-test").toFile()
        sourceFile = File(dir, "source.pmtiles")
        outputFile = File(dir, "map.pmtiles")
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = listOf(PmtilesEntry(0L, tile)),
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0, maxZoom = 0,
            minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        sourceFile.parentFile?.deleteRecursively()
    }

    private fun mapSource() = MapExtractionSource(
        sourceUrl = server.url("/planet.pmtiles").toString(),
        minLon = -170.0, minLat = -80.0, maxLon = 170.0, maxLat = 80.0, minZoom = 0, maxZoom = 0,
    )

    /** Il server di prova: [respond] riceve il numero d'ordine (da 1) della richiesta e la risposta giusta, e la puo' alterare. */
    private fun serve(respond: (number: Int, valid: MockResponse) -> MockResponse): AtomicInteger {
        val inner = RangeFileDispatcher(sourceFile)
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(requests.incrementAndGet(), inner.dispatch(request))
        }
        server.start()
        return requests
    }

    private fun stalled() = serve { _, _ -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }

    private fun assertFailsWithIOException(block: () -> Unit): IOException {
        try {
            block()
        } catch (expected: IOException) {
            return expected
        }
        fail("attesa IOException")
        throw AssertionError()
    }

    @Test
    fun `una risposta 200 al posto della 206 e' un errore e non si ritenta`() {
        val requests = serve { _, valid -> valid.setResponseCode(200) }

        val error = assertFailsWithIOException { newExtractor(retryDelaysMs = listOf(10L, 10L)).extract(mapSource(), outputFile) }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("200"))
        assertEquals(1, requests.get())
        assertFalse(outputFile.exists())
    }

    @Test
    fun `un Content-Range che non parte dal byte richiesto e' un errore`() {
        serve { _, valid -> valid.setHeader("Content-Range", "bytes 5-30/100") }

        val error = assertFailsWithIOException { newExtractor().extract(mapSource(), outputFile) }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Content-Range"))
    }

    @Test
    fun `una risposta senza Content-Range e' un errore`() {
        serve { _, valid -> valid.removeHeader("Content-Range") }

        val error = assertFailsWithIOException { newExtractor().extract(mapSource(), outputFile) }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Content-Range"))
    }

    @Test(timeout = 30_000)
    fun `un server che non risponde fa scadere la lettura invece di bloccare l'estrazione`() {
        stalled()

        val started = System.nanoTime()
        assertFailsWithIOException { newExtractor(readTimeoutMs = 300).extract(mapSource(), outputFile) }

        assertTrue("scaduta dopo ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 10_000_000_000L)
        assertFalse(outputFile.exists())
        assertTrue(outputFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test(timeout = 30_000)
    fun `l'annullamento interrompe la richiesta in corso senza aspettare il timeout`() {
        stalled()
        val started = System.nanoTime()
        // Il timeout di lettura e' di un minuto: se la richiesta non si interrompe, il test non finisce in tempo.
        val error = assertFailsWithIOException {
            newExtractor(readTimeoutMs = 60_000).extract(mapSource(), outputFile, isCancelled = { System.nanoTime() - started > 500_000_000L })
        }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("interrotta dopo $elapsedMs ms", elapsedMs < 10_000)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("annullata"))
        assertFalse(outputFile.exists())
        assertTrue(outputFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `una richiesta fallita con 503 si ritenta e l'estrazione riesce`() {
        val requests = serve { number, valid -> if (number <= 2) MockResponse().setResponseCode(503) else valid }

        newExtractor(retryDelaysMs = listOf(10L, 10L)).extract(mapSource(), outputFile)

        assertTrue(requests.get() > 2)
        Reader(outputFile).use { assertArrayEquals(tile, it.getTile(0, 0, 0)) }
    }

    @Test
    fun `i tentativi per richiesta sono limitati e poi l'errore si propaga`() {
        val requests = serve { _, _ -> MockResponse().setResponseCode(503) }

        assertFailsWithIOException { newExtractor(retryDelaysMs = listOf(10L, 10L)).extract(mapSource(), outputFile) }

        assertEquals("1 tentativo + 2 ritentativi", 3, requests.get())
        assertFalse(outputFile.exists())
    }

    @Test
    fun `un errore 404 non si ritenta`() {
        val requests = serve { _, _ -> MockResponse().setResponseCode(404) }

        assertFailsWithIOException { newExtractor(retryDelaysMs = listOf(10L, 10L)).extract(mapSource(), outputFile) }

        assertEquals(1, requests.get())
    }

    @Test
    fun `se la build cambia durante l'estrazione si interrompe senza ritentare`() {
        // Header e root directory del Reader e header dell'indice sono della build "a"; dopo, la build e' "b".
        val requests = serve { number, valid -> valid.setHeader("ETag", if (number <= 3) "\"a\"" else "\"b\"") }

        val error = assertFailsWithIOException { newExtractor(retryDelaysMs = listOf(10L, 10L)).extract(mapSource(), outputFile) }

        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is SourceChangedIOException })
        assertEquals(4, requests.get())
        assertFalse(outputFile.exists())
    }
}
