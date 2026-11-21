package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
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
import kotlin.io.path.createTempDirectory

// Il "formato compresso" del test e' il contenuto rovesciato: il fake di ZstdDecompressor lo rovescia di nuovo.
// Il decompressore vero e' nativo e si prova sul dispositivo (ZstdDeviceTest (core:translate)).
class BergamotModelManagerTest {

    private val server = MockWebServer()
    private lateinit var modelsDir: File
    private val contents = mapOf("a.bin" to "contenuto del primo file".repeat(40), "b.spm" to "secondo file".repeat(25))
    private var decompressFailure: IOException? = null
    private var corruptDecompressed = false

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun compressed(name: String) = contents.getValue(name).toByteArray().reversedArray()

    private val files = contents.keys.map { name ->
        BergamotFile(
            fileName = name,
            attachment = "files/$name.zst",
            compressedBytes = compressed(name).size.toLong(),
            compressedSha256 = sha256Hex(compressed(name)),
            bytes = contents.getValue(name).length.toLong(),
            sha256 = sha256Hex(contents.getValue(name).toByteArray()),
        )
    }

    private val zstd = ZstdDecompressor { source, target ->
        decompressFailure?.let { throw it }
        val bytes = source.readBytes().reversedArray()
        target.writeBytes(if (corruptDecompressed) bytes.copyOf(bytes.size - 1) + 'X'.code.toByte() else bytes)
    }

    private lateinit var manager: FileBergamotModelManager

    private fun newManager() = FileBergamotModelManager(
        OkHttpClient(), modelsDir, Dispatchers.IO, zstd, null, server.url("/").toString(), files,
    )

    private val translateDir get() = File(modelsDir, "translate")

    private fun leftovers(): List<String> = translateDir.list().orEmpty().toList()

    @Before
    fun setUp() {
        server.start()
        modelsDir = createTempDirectory("pocket-travel-bergamot-test").toFile()
        manager = newManager()
    }

    @After
    fun tearDown() {
        server.shutdown()
        modelsDir.deleteRecursively()
    }

    private fun body(name: String) = Buffer().write(compressed(name))

    private fun enqueueAll() = files.forEach { server.enqueue(MockResponse().setResponseCode(200).setBody(body(it.fileName))) }

    @Test
    fun `scarica, decomprime e installa tutti i file, con l'avanzamento fino al totale`() = runBlocking {
        enqueueAll()
        val progress = mutableListOf<Pair<Long, Long>>()

        manager.download { downloaded, total -> progress += downloaded to total }

        files.forEach { assertEquals(contents.getValue(it.fileName), manager.file(it.fileName).readText()) }
        assertTrue(manager.installed.value)
        assertEquals(files.sumOf { it.bytes }, manager.sizeOnDisk())
        assertEquals(files.map { it.fileName }.toSet(), leftovers().toSet())
        val total = files.sumOf { it.compressedBytes }
        assertEquals(total to total, progress.last())
        assertEquals("/files/a.bin.zst", server.takeRequest().path)
    }

    @Test
    fun `con i file gia' installati non fa nessuna richiesta`() = runBlocking {
        enqueueAll()
        manager.download { _, _ -> }
        val requests = server.requestCount

        val again = newManager()
        assertTrue(again.installed.value)
        again.download { _, _ -> }

        assertEquals(requests, server.requestCount)
    }

    @Test
    fun `un file a meta' non vale come installato e si riscarica solo quello`() = runBlocking {
        enqueueAll()
        manager.download { _, _ -> }
        manager.file("b.spm").writeText("troncato")
        assertFalse(newManager().installed.value)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body("b.spm")))
        val before = server.requestCount

        newManager().download { _, _ -> }

        assertEquals(before + 1, server.requestCount)
        assertEquals(contents.getValue("b.spm"), manager.file("b.spm").readText())
    }

    @Test
    fun `lo sha256 del file compresso sbagliato blocca l'installazione e cancella il parziale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("non e' il file atteso"))

        try {
            manager.download { _, _ -> }
            fail("un checksum sbagliato deve bloccare l'installazione")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertFalse(manager.installed.value)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `lo sha256 del file decompresso sbagliato cancella file compresso e decompresso`() = runBlocking {
        enqueueAll()
        corruptDecompressed = true

        try {
            manager.download { _, _ -> }
            fail("un file decompresso sbagliato deve bloccare l'installazione")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertFalse(manager.installed.value)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `un errore del decompressore lascia il file compresso verificato e nessun decompresso`() = runBlocking {
        enqueueAll()
        decompressFailure = IOException("scrittura fallita: No space left on device")

        val failure = runCatching { manager.download { _, _ -> } }.exceptionOrNull()

        assertTrue(failure is IOException && failure.isNoSpaceLeft())
        assertEquals(listOf("a.bin.zst.part"), leftovers())
    }

    @Test
    fun `riprende un file compresso parziale con Range e Content-Range`() = runBlocking {
        val first = files.first()
        val bytes = compressed(first.fileName)
        translateDir.mkdirs()
        File(translateDir, "${first.fileName}.zst.part").writeBytes(bytes.copyOf(30))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 30-${bytes.size - 1}/${bytes.size}")
                .setBody(Buffer().write(bytes.copyOfRange(30, bytes.size))),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(files.last().fileName)))

        manager.download { _, _ -> }

        assertEquals("bytes=30-", server.takeRequest().getHeader("Range"))
        assertEquals(contents.getValue(first.fileName), manager.file(first.fileName).readText())
    }

    @Test
    fun `se il server risponde 200 al Range riparte da zero`() = runBlocking {
        val first = files.first()
        translateDir.mkdirs()
        File(translateDir, "${first.fileName}.zst.part").writeBytes(compressed(first.fileName).copyOf(30))
        enqueueAll()

        manager.download { _, _ -> }

        assertEquals(contents.getValue(first.fileName), manager.file(first.fileName).readText())
        assertTrue(manager.installed.value)
    }

    @Test
    fun `un file compresso gia' completo non si riscarica`() = runBlocking {
        val first = files.first()
        translateDir.mkdirs()
        File(translateDir, "${first.fileName}.zst.part").writeBytes(compressed(first.fileName))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(files.last().fileName)))

        manager.download { _, _ -> }

        assertEquals(1, server.requestCount)
        assertTrue(manager.installed.value)
    }

    @Test
    fun `un 404 non si ritenta, un 500 si`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(runCatching { manager.download { _, _ -> } }.exceptionOrNull() is ModelDownloadFailedException)

        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(runCatching { manager.download { _, _ -> } }.exceptionOrNull() is IOException)
        assertFalse(manager.installed.value)
    }

    @Test
    fun `un corpo piu' grande di quello pubblicato si interrompe e cancella il parziale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(compressed("a.bin") + ByteArray(500))))

        assertTrue(runCatching { manager.download { _, _ -> } }.exceptionOrNull() is ModelDownloadFailedException)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `elimina file e parziali e l'installazione torna falsa`() = runBlocking {
        enqueueAll()
        manager.download { _, _ -> }
        File(translateDir, "x.zst.part").writeText("parziale")

        manager.delete()

        assertFalse(manager.installed.value)
        assertEquals(0L, manager.sizeOnDisk())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `la pulizia dei file orfani dei modelli dell'assistente non tocca la cartella della traduzione`() = runBlocking {
        enqueueAll()
        manager.download { _, _ -> }

        FileLlmModelManager(OkHttpClient(), modelsDir, AiModelCoordinator(), Dispatchers.IO)
            .deleteOrphanedFiles(LlmModelCatalog.ALL.first())

        assertTrue(newManager().installed.value)
    }
}
