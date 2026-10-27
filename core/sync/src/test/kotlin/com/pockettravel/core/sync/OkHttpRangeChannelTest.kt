package com.pockettravel.core.sync

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Lettura a intervalli: errori che non sono IOException durante la lettura del corpo. */
class OkHttpRangeChannelTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serveRange() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val match = Regex("^bytes=([0-9]+)-([0-9]+)").find(request.getHeader("Range").orEmpty())
                    ?: return MockResponse().setResponseCode(416)
                val start = match.groupValues[1].toLong()
                val end = match.groupValues[2].toLong()
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/1000")
                    .setBody(Buffer().write(ByteArray((end - start + 1).toInt())))
            }
        }
        server.start()
    }

    /** Client che sostituisce il corpo della risposta con uno che lancia [failure] alla prima lettura. */
    private fun clientWithFailingBody(failure: Throwable) = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val response = chain.proceed(chain.request())
                val original = response.body
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = original.contentLength()
                    override fun source(): BufferedSource = throw failure
                }).build()
            },
        )
        .build()

    private fun readWithFailure(failure: Throwable): Throwable {
        serveRange()
        val channel = OkHttpRangeChannel(clientWithFailingBody(failure), server.url("/f").toString(), emptyList()) { false }
        try {
            channel.read(ByteBuffer.allocate(100), 0)
        } catch (expected: Throwable) {
            return expected
        }
        fail("attesa un'eccezione")
        throw AssertionError()
    }

    /** Serve [content] a intervalli; [shape] puo' alterare la risposta della richiesta numero N (da 1). */
    private fun serveContent(content: ByteArray, ranges: MutableList<LongRange>, shape: (Int, MockResponse) -> MockResponse = { _, r -> r }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val match = Regex("^bytes=([0-9]+)-([0-9]+)").find(request.getHeader("Range").orEmpty())
                    ?: return MockResponse().setResponseCode(416)
                val start = match.groupValues[1].toLong()
                val end = match.groupValues[2].toLong()
                ranges += start..end
                val response = MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${content.size}")
                    .setBody(Buffer().write(content.copyOfRange(start.toInt(), end.toInt() + 1)))
                return shape(ranges.size, response)
            }
        }
        server.start()
    }

    private val smallPieces = RangeTuning(initialBatchBytes = 1_000, minBatchBytes = 500, maxBatchBytes = 1_000)

    @Test(timeout = 10_000)
    fun `una lettura grande si divide in pezzi lunghi quanto il blocco e l'ultimo non e' piu' corto del minimo`() {
        val content = ByteArray(6_000) { it.toByte() }
        val ranges = CopyOnWriteArrayList<LongRange>()
        serveContent(content, ranges)
        val channel = OkHttpRangeChannel(OkHttpClient(), server.url("/f").toString(), emptyList(), smallPieces) { false }

        val buffer = ByteBuffer.allocate(5_300)
        assertEquals(5_300, channel.read(buffer, 100))

        assertArrayEquals(content.copyOfRange(100, 5_400), buffer.array())
        assertEquals(listOf(1_000L, 1_000L, 1_000L, 1_000L, 1_300L), ranges.map { it.last - it.first + 1 })
        assertEquals(listOf(100L, 1_100L, 2_100L, 3_100L, 4_100L), ranges.map { it.first })
    }

    @Test(timeout = 10_000)
    fun `dopo un errore a meta' di un pezzo la lettura riparte dal byte gia' ricevuto`() {
        val content = ByteArray(1_000) { (it * 7).toByte() }
        val ranges = CopyOnWriteArrayList<LongRange>()
        serveContent(content, ranges) { number, response ->
            if (number == 1) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else response
        }
        val channel = OkHttpRangeChannel(OkHttpClient(), server.url("/f").toString(), listOf(10L), RangeTuning()) { false }

        val buffer = ByteBuffer.allocate(1_000)
        assertEquals(1_000, channel.read(buffer, 0))

        assertArrayEquals(content, buffer.array())
        assertEquals(2, ranges.size)
        assertTrue("il ritentativo parte dopo i byte ricevuti: $ranges", ranges[1].first > 0L)
        assertEquals(999L, ranges[1].last)
    }

    @Test(timeout = 10_000)
    fun `le connessioni stanno in un pool proprio svuotato alla chiusura`() {
        serveRange()
        val appClient = OkHttpClient()
        val channel = OkHttpRangeChannel(appClient, server.url("/f").toString(), emptyList()) { false }

        channel.read(ByteBuffer.allocate(100), 0)

        assertEquals("il pool dell'app non e' toccato", 0, appClient.connectionPool.connectionCount())
        assertEquals(1, channel.pool.connectionCount())
        channel.close()
        assertEquals("connessione chiusa con il canale", 0, channel.pool.connectionCount())
    }

    @Test(timeout = 10_000)
    fun `una RuntimeException durante la lettura del corpo fa fallire la lettura invece di bloccarla`() {
        val failure = IllegalStateException("corpo non leggibile")

        assertEquals(failure, readWithFailure(failure))
    }

    @Test(timeout = 10_000)
    fun `un Error durante la lettura del corpo arriva a chi aspetta com'e'`() {
        val failure = AssertionError("errore grave")

        val error = readWithFailure(failure)

        assertTrue(error.toString(), error === failure)
    }

    private val tuning = RangeTuning(
        initialBatchBytes = 1_000, minBatchBytes = 100, maxBatchBytes = 10_000, targetBatchMs = 1_000, minSampleBytes = 50,
        averageWeight = 0.5, stallFloorBytesPerSecond = 10, stallRatio = 0.1, minSamplesForRelative = 2,
    )
    private val second = 1_000_000_000L

    @Test
    fun `senza misure il blocco e' quello iniziale e la soglia di fermo e' solo quella assoluta`() {
        val estimator = ThroughputEstimator(tuning)

        assertEquals(1_000L, estimator.batchBytes())
        assertEquals(10.0, estimator.stallThreshold(), 0.0)
    }

    @Test
    fun `il blocco dura circa il tempo obiettivo alla velocita' misurata, entro i limiti`() {
        val estimator = ThroughputEstimator(tuning)

        estimator.record(4_000, second)
        assertEquals("4000 byte/s per un secondo", 4_000L, estimator.batchBytes())

        estimator.record(100_000, second)
        assertEquals("rete veloce: massimo", 10_000L, estimator.batchBytes())

        val slow = ThroughputEstimator(tuning)
        slow.record(60, 60 * second)
        assertEquals("rete lentissima: minimo", 100L, slow.batchBytes())
    }

    @Test
    fun `la media e' mobile e le misure recenti pesano di piu'`() {
        val estimator = ThroughputEstimator(tuning)

        estimator.record(8_000, second)
        estimator.record(2_000, second)

        assertEquals("(8000 + 2000) / 2", 5_000L, estimator.batchBytes())
    }

    @Test
    fun `le letture troppo piccole non contano`() {
        val estimator = ThroughputEstimator(tuning)

        estimator.record(49, 1)
        estimator.record(1_000, 0)

        assertEquals(1_000L, estimator.batchBytes())
        assertEquals(10.0, estimator.stallThreshold(), 0.0)
    }

    @Test
    fun `la soglia relativa vale solo con abbastanza misure`() {
        val estimator = ThroughputEstimator(tuning)

        estimator.record(10_000, second)
        assertEquals("una misura sola", 10.0, estimator.stallThreshold(), 0.0)

        estimator.record(10_000, second)
        assertEquals("un decimo della media", 1_000.0, estimator.stallThreshold(), 0.001)
    }

    @Test
    fun `dopo un fermo i blocchi seguono la velocita' osservata e la soglia resta quella di prima`() {
        val estimator = ThroughputEstimator(tuning)
        estimator.record(10_000, second)
        estimator.record(10_000, second)

        estimator.reset(500.0)

        assertEquals(500L, estimator.batchBytes())
        // Un decimo della media di prima del fermo: una connessione nuova altrettanto lenta si interrompe di nuovo.
        assertEquals(1_000.0, estimator.stallThreshold(), 0.0)

        // La prima richiesta conclusa rientra nella media mobile, che scende un po' alla volta.
        estimator.record(1_000, second)
        assertEquals(5_500L, estimator.batchBytes())
        assertEquals(550.0, estimator.stallThreshold(), 0.0)
    }
}
