package com.pockettravel.core.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

// Server con Range: serve fette di [data] e registra le intestazioni Range ricevute (la sonda "bytes=0-0" compresa).
class SegmentedDownloadTest {

    private val server = MockWebServer()
    private val data = ByteArray(5000) { (it * 7).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private val ranges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var dir: File
    private lateinit var part: File

    // Cambiano il comportamento del server nei singoli test.
    @Volatile private var ignoreRange = false

    @Volatile private var wrongTotal = false

    @Volatile private var failFromByte = Long.MAX_VALUE

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = serve(request.getHeader("Range"))
    }

    private fun serve(range: String?): MockResponse {
        range?.let(ranges::add)
        val match = range?.let { Regex("bytes=(\\d+)-(\\d+)").matchEntire(it) }
        val (first, last) = match?.destructured?.let { it.component1().toInt() to it.component2().toInt() } ?: (0 to 0)
        val total = if (wrongTotal) data.size + 1 else data.size
        return when {
            ignoreRange || match == null -> MockResponse().setResponseCode(200).setBody(Buffer().write(data))
            first >= failFromByte -> MockResponse().setResponseCode(500)
            else -> MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $first-$last/$total")
                .setBody(Buffer().write(data, first, last - first + 1))
        }
    }

    @Before
    fun setUp() {
        server.dispatcher = dispatcher
        server.start()
        dir = createTempDirectory("pocket-travel-segmented-test").toFile()
        part = File(dir, "file.bin.part")
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    // Segmenti da 1000 byte: 5 segmenti; la soglia minima e' sotto la dimensione cosi' i file piccoli del test li usano.
    private fun download(parallelism: Int = 3) = SegmentedDownload(OkHttpClient(), segmentBytes = 1000, parallelism = parallelism, minBytes = 100)

    private fun fetch(downloader: SegmentedDownload = download(), url: String = server.url("/file").toString(), progress: MutableList<Long> = mutableListOf()) =
        runBlocking { downloader.fetch(url, part, data.size.toLong(), sha256, 1L) { progress += it } }

    @Test
    fun `scarica a segmenti, riempie il file e toglie la mappa`() {
        val progress = Collections.synchronizedList(mutableListOf<Long>())

        assertTrue(fetch(progress = progress))

        assertArrayEquals(data, part.readBytes())
        assertFalse(SegmentedDownload.mapFile(part).exists())
        assertEquals(5, ranges.count { it != "bytes=0-0" })
        assertEquals(data.size.toLong(), progress.last())
    }

    @Test
    fun `un file sotto la soglia non fa nessuna richiesta`() {
        val small = SegmentedDownload(OkHttpClient(), segmentBytes = 1000, parallelism = 2, minBytes = 10_000)

        assertFalse(fetch(small))

        assertEquals(0, server.requestCount)
        assertFalse(part.exists())
    }

    @Test
    fun `un server che ignora Range obbliga allo scarico in linea senza toccare i file`() {
        ignoreRange = true

        assertFalse(fetch())

        assertFalse(part.exists())
        assertFalse(SegmentedDownload.mapFile(part).exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `una dimensione totale diversa da quella del catalogo nella sonda obbliga allo scarico in linea`() {
        wrongTotal = true

        assertFalse(fetch())
        assertFalse(part.exists())
    }

    @Test
    fun `un Content-Range sbagliato in un segmento fallisce e scarta lo stato di ripresa`() {
        // La sonda risponde giusta, i segmenti no.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range == "bytes=0-0") return serve(range)
                return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/${data.size}").setBody("x")
            }
        }

        try {
            fetch()
            fail("un Content-Range diverso dalla richiesta deve far fallire il download")
        } catch (_: SegmentedDownloadException) {
            // atteso
        }
        assertFalse(part.exists())
        assertFalse(SegmentedDownload.mapFile(part).exists())
    }

    @Test
    fun `dopo un'interruzione riprende dalla mappa senza riscaricare i segmenti finiti`() {
        failFromByte = 2000

        try {
            fetch(download(parallelism = 1))
            fail("il segmento rifiutato deve far fallire il tentativo")
        } catch (_: IOException) {
            // atteso: i segmenti 0 e 1 sono finiti
        }
        assertEquals(listOf("0", "1"), SegmentedDownload.mapFile(part).readLines().drop(1))
        ranges.clear()
        failFromByte = Long.MAX_VALUE

        assertTrue(fetch(download(parallelism = 1)))

        assertArrayEquals(data, part.readBytes())
        assertEquals(listOf("bytes=0-0", "bytes=2000-2999", "bytes=3000-3999", "bytes=4000-4999"), ranges.toList())
    }

    @Test
    fun `una mappa di un altro sha256 si scarta e si riparte da zero`() {
        part.writeBytes(ByteArray(data.size) { 1 })
        SegmentedDownload.mapFile(part).writeText("v1 ${data.size} 1000 ${"0".repeat(64)}\n0\n1\n2\n3\n4\n")

        assertTrue(fetch())

        assertArrayEquals(data, part.readBytes())
    }

    @Test
    fun `un indirizzo firmato scaduto si risolve di nuovo dal catalogo e si continua`() {
        val probes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/file" -> MockResponse().setResponseCode(302).setHeader("Location", "/signed/${probes.incrementAndGet()}")
                // il primo indirizzo vale solo per la sonda
                "/signed/1" -> if (request.getHeader("Range") == "bytes=0-0") serve("bytes=0-0") else MockResponse().setResponseCode(403)
                else -> serve(request.getHeader("Range"))
            }
        }

        assertTrue(fetch())

        assertArrayEquals(data, part.readBytes())
        assertEquals(2, probes.get())
    }

    @Test
    fun `un indirizzo rifiutato anche dopo la nuova risoluzione fallisce`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Range") == "bytes=0-0") serve("bytes=0-0") else MockResponse().setResponseCode(403)
        }

        try {
            fetch()
            fail("un 403 ripetuto dopo la nuova risoluzione deve far fallire il download")
        } catch (_: SegmentedDownloadException) {
            // atteso
        }
    }
}
