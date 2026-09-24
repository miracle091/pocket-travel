package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
        downloader = RegionPackageDownloader(OkHttpClient(), regionStorage)
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
    fun `dimensione diversa da quella dichiarata fa fallire il download`() = runBlocking {
        val bytes = "abc".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody("abc"))
        val manifest = manifestFile(bytes).copy(sizeBytes = 999)

        try {
            downloader.downloadAndVerify(manifest, File(targetDir, "content.db"))
            fail("una dimensione sbagliata deve far fallire il download")
        } catch (_: PermanentRegionPackageException) {
            // atteso
        }
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
}
