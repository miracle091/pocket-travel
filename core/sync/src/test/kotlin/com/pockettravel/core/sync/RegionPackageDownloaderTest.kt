package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis

// downloadAndVerify() e' internal apposta (vedi commento nella classe di produzione): qui si
// esercita solo il download/ripresa/verifica byte-per-byte via un vero server HTTP locale
// (MockWebServer), non il vincolo HTTPS+host-allowlist di RegionManifestEntry.validate()
// (gia' coperto da RegionManifestTest).
class RegionPackageDownloaderTest {

    private val server = MockWebServer()
    private lateinit var downloader: RegionPackageDownloader
    private lateinit var regionStorage: RegionStorage
    private lateinit var targetDir: File

    @Before
    fun setUp() {
        server.start()
        val root = createTempDirectory("pocket-travel-downloader-test").toFile()
        targetDir = File(root, "target").apply { mkdirs() }
        regionStorage = RegionStorage(
            regionsDir = File(root, "regions").apply { mkdirs() },
            stagingDir = File(root, "staging").apply { mkdirs() },
        )
        downloader = RegionPackageDownloader(OkHttpClient(), regionStorage, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manifestFile(bytes: ByteArray, name: String = "content.db") = RegionManifestFile(
        name = name,
        url = server.url("/$name").toString(),
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `scarica e verifica un file completo`() = runBlocking {
        val text = "contenuto di test"
        server.enqueue(MockResponse().setResponseCode(200).setBody(text))
        val target = File(targetDir, "content.db")

        downloader.downloadAndVerify(manifestFile(text.toByteArray()), target)

        assertEquals(text, target.readText())
        assertFalse(File(targetDir, "content.db.part").exists())
    }

    @Test
    fun `riprende un download parziale con Range e Content-Range`() = runBlocking {
        val fullText = "0123456789ABCDEF"
        val alreadyDownloaded = fullText.substring(0, 6)
        val remaining = fullText.substring(6)
        val partFile = File(targetDir, "content.db.part")
        partFile.writeBytes(alreadyDownloaded.toByteArray())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 6-${fullText.length - 1}/${fullText.length}")
                .setBody(remaining),
        )

        downloader.downloadAndVerify(manifestFile(fullText.toByteArray()), File(targetDir, "content.db"))

        assertEquals(fullText, File(targetDir, "content.db").readText())
        val request = server.takeRequest()
        assertEquals("bytes=6-", request.getHeader("Range"))
    }

    @Test
    fun `checksum non valido fa fallire il download e cancella il part file`() = runBlocking {
        val bytes = "contenuto reale".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("contenuto reale"))
        val wrongManifest = manifestFile(bytes).copy(sha256 = sha256Hex("altro contenuto".toByteArray()))

        try {
            downloader.downloadAndVerify(wrongManifest, File(targetDir, "content.db"))
            fail("un checksum sbagliato deve far fallire il download")
        } catch (_: PermanentRegionPackageException) {
            // atteso
        }
        assertFalse(File(targetDir, "content.db.part").exists())
        assertFalse(File(targetDir, "content.db").exists())
    }

    @Test
    fun `un flusso piu' corto di quanto dice il catalogo e' ritentabile e tiene il part file`() = runBlocking {
        val bytes = "abc".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        val manifest = manifestFile(bytes).copy(sizeBytes = 999)

        try {
            downloader.downloadAndVerify(manifest, File(targetDir, "content.db"))
            fail("un flusso troncato deve far fallire il tentativo")
        } catch (_: PermanentRegionPackageException) {
            fail("un flusso troncato non deve essere trattato come permanente")
        } catch (_: IOException) {
            // atteso: al prossimo tentativo si riprende dal .part
        }
        assertEquals("abc", File(targetDir, "content.db.part").readText())
    }

    @Test
    fun `un Content-Range inatteso cancella il part file ed e' ritentabile`() = runBlocking {
        val fullText = "0123456789"
        val partFile = File(targetDir, "content.db.part")
        partFile.writeBytes("01234".toByteArray())
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setBody(fullText),
        )

        try {
            downloader.downloadAndVerify(manifestFile(fullText.toByteArray()), File(targetDir, "content.db"))
            fail("un Content-Range inatteso deve far fallire il tentativo")
        } catch (_: PermanentRegionPackageException) {
            fail("un Content-Range inatteso non deve essere permanente")
        } catch (_: IOException) {
            // atteso: al prossimo tentativo si riparte da zero
        }
        assertFalse(partFile.exists())
    }

    @Test
    fun `un flusso piu' lungo di quanto dice il catalogo si ferma subito e cancella il part file`() = runBlocking {
        val bytes = "abc".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc".repeat(100_000)))

        try {
            downloader.downloadAndVerify(manifestFile(bytes), File(targetDir, "content.db"))
            fail("piu' byte di quelli del catalogo devono far fallire il download")
        } catch (_: PermanentRegionPackageException) {
            // atteso
        }
        assertFalse(File(targetDir, "content.db.part").exists())
        assertFalse(File(targetDir, "content.db").exists())
    }

    @Test
    fun `un errore 404 e' permanente`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val bytes = "x".toByteArray()

        try {
            downloader.downloadAndVerify(manifestFile(bytes), File(targetDir, "content.db"))
            fail("un 404 deve essere un errore permanente")
        } catch (_: PermanentRegionPackageException) {
            // atteso
        }
    }

    @Test
    fun `riusa i segmenti rd5 installati invariati e scarica solo quelli cambiati`() = runBlocking {
        val installedRouting = File(regionStorage.directoryFor("italia"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        val unchanged = "tile invariata".toByteArray()
        File(installedRouting, "E10_N40.rd5").writeBytes(unchanged)
        // Stessa dimensione del nuovo contenuto ma hash diverso: va riscaricato, non riusato.
        File(installedRouting, "E10_N45.rd5").writeBytes("tile vecchia!".toByteArray())
        val changed = "tile cambiata".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("tile cambiata"))

        val staging = downloader.download(
            "italia", "routing-2",
            listOf(manifestFile(unchanged, "E10_N40.rd5"), manifestFile(changed, "E10_N45.rd5")),
        )

        assertEquals(1, server.requestCount)
        assertEquals("/E10_N45.rd5", server.takeRequest().path)
        assertEquals("tile invariata", File(staging, "E10_N40.rd5").readText())
        assertEquals("tile cambiata", File(staging, "E10_N45.rd5").readText())
        // Il segmento installato resta al suo posto per il rollback.
        assertEquals("tile invariata", File(installedRouting, "E10_N40.rd5").readText())
    }

    @Test
    fun `un segmento rd5 gia' spostato in staging-routing da un'installazione annullata si riusa`() = runBlocking {
        val moved = "tile gia' scaricata".toByteArray()
        val stagingRouting = File(regionStorage.stagingDirectoryFor("italia", "routing-2"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(stagingRouting, "E10_N40.rd5").writeBytes(moved)

        val staging = downloader.download("italia", "routing-2", listOf(manifestFile(moved, "E10_N40.rd5")))

        assertEquals(0, server.requestCount)
        assertEquals("tile gia' scaricata", File(staging, "E10_N40.rd5").readText())
    }

    @Test
    fun `un segmento rd5 installato con lo SHA diverso non si copia in staging`() = runBlocking {
        val installedRouting = File(regionStorage.directoryFor("italia"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(installedRouting, "E10_N45.rd5").writeBytes("tile vecchia!".toByteArray())
        val changed = "tile cambiata".toByteArray()
        // Il download si ferma subito (500): quello che conta e' che in staging non sia stato copiato il vecchio file.
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            downloader.download("italia", "routing-2", listOf(manifestFile(changed, "E10_N45.rd5")))
            fail("il download doveva fallire")
        } catch (_: IOException) {
            // atteso
        }

        assertFalse(File(regionStorage.stagingDirectoryFor("italia", "routing-2"), "E10_N45.rd5").exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `un errore 500 non e' permanente ed e' quindi ritentabile`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val bytes = "x".toByteArray()

        try {
            downloader.downloadAndVerify(manifestFile(bytes), File(targetDir, "content.db"))
            fail("un 500 deve restare un errore transitorio ritentabile, non permanente")
        } catch (_: PermanentRegionPackageException) {
            fail("un 500 non deve essere trattato come permanente")
        } catch (_: IOException) {
            // atteso: errore transitorio, il worker chiamante puo' ritentare
        }
    }

    @Test
    fun `un part file gia' completo viene verificato e finalizzato senza richieste HTTP`() = runBlocking {
        val bytes = "0123456789".toByteArray()
        File(targetDir, "content.db.part").writeBytes(bytes)

        downloader.downloadAndVerify(manifestFile(bytes), File(targetDir, "content.db"))

        assertEquals("0123456789", File(targetDir, "content.db").readText())
        assertFalse(File(targetDir, "content.db.part").exists())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `un 416 cancella il part file ed e' ritentabile`() = runBlocking {
        val bytes = "0123456789".toByteArray()
        val partFile = File(targetDir, "content.db.part")
        partFile.writeBytes("01234".toByteArray())
        server.enqueue(MockResponse().setResponseCode(416))

        try {
            downloader.downloadAndVerify(manifestFile(bytes), File(targetDir, "content.db"))
            fail("un 416 deve far fallire il tentativo")
        } catch (_: PermanentRegionPackageException) {
            fail("un 416 non deve essere trattato come permanente")
        } catch (_: IOException) {
            // atteso: al prossimo tentativo si riparte da zero
        }
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        assertFalse(partFile.exists())
    }

    @Test
    fun `due installazioni sullo stesso id di staging con versioni diverse sono serializzate`() = runBlocking {
        val inFirst = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        var firstMarkerSurvived = false

        // Come RegionPackageInstaller: download + uso dei file dentro lo stesso lock, poi pulizia.
        val first = launch(Dispatchers.Default) {
            downloader.withStagingLock("it") {
                val staging = downloader.downloadLocked("it", "v1", emptyList())
                val marker = File(staging, "poi.db").apply { writeText("v1") }
                events += "first-start"
                inFirst.complete(Unit)
                delay(300)
                // Se la seconda fosse entrata, cleanupStagingExcept avrebbe cancellato lo staging di v1.
                firstMarkerSurvived = marker.exists()
                events += "first-end"
            }
        }
        val second = launch(Dispatchers.Default) {
            inFirst.await()
            downloader.withStagingLock("it") {
                events += "second-start"
                downloader.downloadLocked("it", "v2", emptyList())
                events += "second-end"
            }
        }
        first.join()
        second.join()

        assertTrue(firstMarkerSurvived)
        assertEquals(listOf("first-start", "first-end", "second-start", "second-end"), events.toList())
    }

    @Test
    fun `un secondo download della stessa regione con un'altra versione non cancella il part file del primo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        val truncated = manifestFile("abc".toByteArray()).copy(sizeBytes = 999)

        try {
            downloader.download("it", "v1", listOf(truncated))
            fail("un flusso troncato deve far fallire il tentativo")
        } catch (_: IOException) {
            // atteso: il .part resta per la ripresa
        }
        val part = File(regionStorage.stagingDirectoryFor("it", "v1"), "content.db.part")
        assertTrue(part.exists())

        downloader.download("it", "v2", emptyList())

        assertTrue(part.exists())
    }

    @Test
    fun `annullare un download a una connessione fermo sul corpo lo chiude subito`() = runBlocking {
        // Intestazioni subito, corpo dopo 30 s: senza Call.cancel() si aspetterebbe il timeout di lettura (10 s).
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc").setBodyDelay(30, TimeUnit.SECONDS))
        val job = launch(Dispatchers.IO) { downloader.downloadAndVerify(manifestFile("abc".toByteArray()), File(targetDir, "content.db")) }
        server.takeRequest()
        delay(200)

        val elapsed = measureTimeMillis { job.cancelAndJoin() }

        assertTrue("annullamento in $elapsed ms", elapsed < 2_000)
    }

    @Test
    fun `un indice scaricato con downloadLatest lascia nello staging solo la sua versione`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("uno"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("due"))

        downloader.downloadLatest("_transit", "v1", listOf(manifestFile("uno".toByteArray(), "index.json")))
        val latest = downloader.downloadLatest("_transit", "v2", listOf(manifestFile("due".toByteArray(), "index.json")))

        assertEquals("due", File(latest, "index.json").readText())
        assertEquals(listOf("v2"), regionStorage.stagingDirectoryFor("_transit", "v2").parentFile?.list()?.toList())
    }

    @Test
    fun `il disco pieno si riconosce dal messaggio dell'errore di scrittura`() {
        assertTrue(isNoSpaceLeft(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(isNoSpaceLeft(IOException("No space left on device")))
        assertFalse(isNoSpaceLeft(IOException("Connection reset")))
        assertFalse(isNoSpaceLeft(IOException()))
    }

    @Test
    fun `la pulizia senza attesa non entra mentre un altro lavoro tiene il lock`() = runBlocking {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            downloader.withStagingLock("it") {
                inside.complete(Unit)
                release.await()
            }
        }
        inside.await()
        var cleaned = false
        assertFalse(downloader.tryWithStagingLock("it") { cleaned = true })
        assertFalse(cleaned)
        release.complete(Unit)
        holder.join()
        assertTrue(downloader.tryWithStagingLock("it") { cleaned = true })
        assertTrue(cleaned)
    }

    // Server che risponde ai Range con fette di [data]; registra le intestazioni Range.
    private fun serveRanges(data: ByteArray, ranges: MutableList<String>) {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse().setBody(okio.Buffer().write(data))
                ranges += range
                // "bytes=a-b" dei segmenti o "bytes=a-" della ripresa in linea.
                val match = checkNotNull(Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range)).groupValues
                val first = match[1].toInt()
                val last = match[2].toIntOrNull() ?: (data.size - 1)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $first-$last/${data.size}")
                    .setBody(okio.Buffer().write(data, first, last - first + 1))
            }
        }
    }

    @Test
    fun `un file grande si scarica a segmenti e passa la verifica`() = runBlocking {
        val data = ByteArray(5000) { (it * 7).toByte() }
        val ranges = Collections.synchronizedList(mutableListOf<String>())
        serveRanges(data, ranges)
        downloader.segmentedDownload = SegmentedDownload(OkHttpClient(), segmentBytes = 1000, parallelism = 2, minBytes = 100)

        downloader.downloadAndVerify(manifestFile(data), File(targetDir, "content.db"))

        assertTrue(data.contentEquals(File(targetDir, "content.db").readBytes()))
        assertEquals(5, ranges.count { it != "bytes=0-0" })
        assertFalse(File(targetDir, "content.db.part").exists())
        assertFalse(File(targetDir, "content.db.part.map").exists())
    }

    @Test
    fun `un part lineare senza mappa riprende in linea anche per un file grande`() = runBlocking {
        val data = ByteArray(5000) { (it * 7).toByte() }
        val ranges = Collections.synchronizedList(mutableListOf<String>())
        serveRanges(data, ranges)
        downloader.segmentedDownload = SegmentedDownload(OkHttpClient(), segmentBytes = 1000, parallelism = 2, minBytes = 100)
        File(targetDir, "content.db.part").writeBytes(data.copyOf(1500))

        downloader.downloadAndVerify(manifestFile(data), File(targetDir, "content.db"))

        assertTrue(data.contentEquals(File(targetDir, "content.db").readBytes()))
        assertEquals(listOf("bytes=1500-"), ranges.toList())
    }
}
