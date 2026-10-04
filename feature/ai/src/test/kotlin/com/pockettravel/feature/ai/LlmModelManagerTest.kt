package com.pockettravel.feature.ai

import com.pockettravel.core.sync.AiModelManifestEntry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

// download() prende un LlmModelDefinition intero (non solo url/sha256 sciolti) apposta per poter
// costruire qui una definizione di test che punta al MockWebServer, verificando il checksum reale
// senza scaricare centinaia di MB da un repo HuggingFace vero.
class LlmModelManagerTest {

    private val server = MockWebServer()
    private lateinit var modelManager: LlmModelManager
    private lateinit var modelsDir: File

    // sha256/url sono placeholder qui: ogni test li sovrascrive con .copy() secondo cosa vuole
    // verificare (checksum atteso reale, url del MockWebServer, ecc).
    private val testDefinition = LlmModelDefinition(
        id = "test-model",
        displayName = "Test Model",
        url = "",
        fileName = "test-model.gguf",
        sha256 = "",
        sizeBytes = 0L,
        minRamTier = RamTier.MINIMO,
    )

    @Before
    fun setUp() {
        server.start()
        modelsDir = createTempDirectory("pocket-travel-llm-test").toFile()
        modelManager = LlmModelManager(OkHttpClient(), modelsDir, AiModelCoordinator())
    }

    @After
    fun tearDown() {
        server.shutdown()
        modelsDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `scarica il modello e lo installa quando il checksum corrisponde`() = runBlocking {
        val content = "modello finto per il test".repeat(100)
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val definition = testDefinition.copy(url = server.url("/model").toString(), sha256 = sha256Hex(content.toByteArray()))

        modelManager.download(definition) { _, _ -> }

        assertEquals(content, modelManager.modelFile(definition).readText())
        assertFalse(File(modelsDir, "${definition.fileName}.part").exists())
    }

    @Test
    fun `un checksum diverso da quello atteso blocca l'installazione`() = runBlocking {
        val content = "contenuto scaricato"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val wrongSha256 = sha256Hex("contenuto diverso".toByteArray())
        val definition = testDefinition.copy(url = server.url("/model").toString(), sha256 = wrongSha256)

        try {
            modelManager.download(definition) { _, _ -> }
            fail("un checksum sbagliato deve bloccare l'installazione del modello")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertFalse("il modello non deve essere installato se il checksum non corrisponde", modelManager.isDownloaded(definition))
        assertFalse(File(modelsDir, "${definition.fileName}.part").exists())
    }

    @Test
    fun `riprende un download parziale con Range e Content-Range`() = runBlocking {
        val fullText = "0123456789ABCDEF".repeat(10)
        val alreadyDownloaded = fullText.substring(0, 60)
        val remaining = fullText.substring(60)
        val definition = testDefinition.copy(url = server.url("/model").toString(), sha256 = sha256Hex(fullText.toByteArray()))
        File(modelsDir, "${definition.fileName}.part").writeBytes(alreadyDownloaded.toByteArray())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 60-${fullText.length - 1}/${fullText.length}")
                .setBody(remaining),
        )

        modelManager.download(definition) { _, _ -> }

        assertEquals(fullText, modelManager.modelFile(definition).readText())
        val request = server.takeRequest()
        assertEquals("bytes=60-", request.getHeader("Range"))
    }

    @Test
    fun `un download oltre la dimensione attesa si interrompe e cancella il parziale`() = runBlocking {
        val content = "0123456789".repeat(20)
        val definition = testDefinition.copy(
            url = server.url("/model").toString(),
            sha256 = sha256Hex(content.toByteArray()),
            sizeBytes = content.length - 1L,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))

        try {
            modelManager.download(definition) { _, _ -> }
            fail("un file piu' grande di sizeBytes deve interrompere il download")
        } catch (_: ModelDownloadFailedException) {
            // atteso
        }

        assertFalse(File(modelsDir, "${definition.fileName}.part").exists())
        assertFalse(modelManager.isDownloaded(definition))
    }

    @Test
    fun `il limite di dimensione conta anche i byte del parziale ripreso`() = runBlocking {
        val fullText = "0123456789ABCDEF".repeat(10)
        val definition = testDefinition.copy(
            url = server.url("/model").toString(),
            sha256 = sha256Hex(fullText.toByteArray()),
            sizeBytes = fullText.length - 1L,
        )
        File(modelsDir, "${definition.fileName}.part").writeBytes(fullText.substring(0, 60).toByteArray())
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 60-${fullText.length - 1}/${fullText.length}")
                .setBody(fullText.substring(60)),
        )

        try {
            modelManager.download(definition) { _, _ -> }
            fail("parziale + risposta oltre sizeBytes deve interrompere il download")
        } catch (_: ModelDownloadFailedException) {
            // atteso
        }

        assertFalse(File(modelsDir, "${definition.fileName}.part").exists())
    }

    @Test
    fun `un errore 404 e' permanente`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val definition = testDefinition.copy(url = server.url("/model").toString())

        try {
            modelManager.download(definition) { _, _ -> }
            fail("un 404 deve essere un errore permanente")
        } catch (_: ModelDownloadFailedException) {
            // atteso
        }
    }

    @Test
    fun `un errore 500 non e' permanente ed e' quindi ritentabile`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val definition = testDefinition.copy(url = server.url("/model").toString())

        try {
            modelManager.download(definition) { _, _ -> }
            fail("un 500 deve restare un errore transitorio ritentabile, non permanente")
        } catch (_: ModelDownloadFailedException) {
            fail("un 500 non deve essere trattato come permanente")
        } catch (_: IOException) {
            // atteso: errore transitorio, il worker chiamante puo' ritentare
        }
    }

    @Test
    fun `selectAndDownload elimina il modello precedente prima di scaricare quello nuovo`() = runBlocking {
        val oldDefinition = testDefinition.copy(id = "old-model", fileName = "old-model.gguf")
        File(modelsDir, oldDefinition.fileName).writeText("vecchio modello installato")

        val newContent = "nuovo modello"
        server.enqueue(MockResponse().setResponseCode(200).setBody(newContent))
        val newDefinition = testDefinition.copy(
            id = "new-model",
            fileName = "new-model.gguf",
            url = server.url("/model").toString(),
            sha256 = sha256Hex(newContent.toByteArray()),
        )

        modelManager.selectAndDownload(newDefinition, currentlyInstalled = oldDefinition) { _, _ -> }

        assertFalse("il vecchio modello deve essere eliminato dopo lo switch", modelManager.isDownloaded(oldDefinition))
        assertEquals(newContent, modelManager.modelFile(newDefinition).readText())
    }

    @Test
    fun `deleteOrphanedFiles elimina i file fuori catalogo e conserva modelli e il download del modello scelto`() {
        val catalogModel = LlmModelCatalog.ALL.first()
        val selected = LlmModelCatalog.ALL.last()
        val installed = File(modelsDir, catalogModel.fileName).apply { writeText("modello installato") }
        val installedSha = File(modelsDir, "${catalogModel.fileName}.sha256").apply { writeText("a".repeat(64)) }
        val partial = File(modelsDir, "${selected.fileName}.part").apply { writeText("download a meta'") }
        val oldLiteRt = File(modelsDir, "qwen3_0_6b_mixed_int4.litertlm").apply { writeText("vecchio formato") }
        val oldLiteRtPart = File(modelsDir, "qwen3_4b_mixed_int4.litertlm.part").apply { writeText("vecchio parziale") }

        modelManager.deleteOrphanedFiles(selected)

        assertTrue(installed.exists())
        assertTrue(installedSha.exists())
        assertTrue(partial.exists())
        assertFalse(oldLiteRt.exists())
        assertFalse(oldLiteRtPart.exists())
    }

    @Test
    fun `deleteOrphanedFiles elimina il parziale di un modello abbandonato`() {
        val selected = LlmModelCatalog.ALL.last()
        val abandoned = LlmModelCatalog.ALL.first { it.id != selected.id }
        val abandonedPart = File(modelsDir, "${abandoned.fileName}.part").apply { writeText("parziale abbandonato") }

        modelManager.deleteOrphanedFiles(selected)

        assertFalse(abandonedPart.exists())
    }

    @Test
    fun `download elimina il parziale di un altro modello e salva lo sha256 verificato`() = runBlocking {
        val content = "nuovo modello"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val definition = testDefinition.copy(url = server.url("/model").toString(), sha256 = sha256Hex(content.toByteArray()))
        val abandoned = LlmModelCatalog.ALL.first()
        val abandonedPart = File(modelsDir, "${abandoned.fileName}.part").apply { writeText("parziale abbandonato") }

        modelManager.download(definition) { _, _ -> }

        assertFalse(abandonedPart.exists())
        assertEquals(definition.sha256, modelManager.installedSha256(definition))
        assertEquals(definition.sha256, File(modelsDir, "${definition.fileName}.sha256").readText())
    }

    @Test
    fun `installedSha256 e' nullo senza modello`() = runBlocking {
        assertNull(modelManager.installedSha256(testDefinition))
    }

    @Test
    fun `installedSha256 di un'installazione senza sha salvato lo calcola e lo salva`() = runBlocking {
        val content = "modello scaricato da una versione precedente"
        File(modelsDir, testDefinition.fileName).writeText(content)

        val sha = modelManager.installedSha256(testDefinition)

        assertEquals(sha256Hex(content.toByteArray()), sha)
        assertEquals(sha, File(modelsDir, "${testDefinition.fileName}.sha256").readText())
    }

    @Test
    fun `chi ha la versione precedente con lo stesso id vede l'aggiornamento anche se cambia solo lo sha256`() = runBlocking {
        val oldContent = "modello v7"
        val newContent = "modello v8"
        val definition = testDefinition.copy(url = server.url("/model").toString(), sha256 = sha256Hex(oldContent.toByteArray()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(oldContent))
        modelManager.download(definition) { _, _ -> }

        val installed = requireNotNull(modelManager.installedSha256(definition))
        val remote = AiModelManifestEntry(definition.id, definition.id, sha256Hex(newContent.toByteArray()), newContent.length.toLong())

        assertTrue(isAiModelUpdateAvailable(installed, remote))
    }

    @Test
    fun `delete elimina anche lo sha256 salvato`() = runBlocking {
        val content = "modello"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val definition = testDefinition.copy(url = server.url("/model").toString(), sha256 = sha256Hex(content.toByteArray()))
        modelManager.download(definition) { _, _ -> }

        modelManager.delete(definition)

        assertFalse(File(modelsDir, "${definition.fileName}.sha256").exists())
        assertNull(modelManager.installedSha256(definition))
    }

    @Test
    fun `un parziale gia' completo non fa nessuna richiesta e viene solo verificato`() = runBlocking {
        val content = "0123456789ABCDEF".repeat(10)
        val definition = testDefinition.copy(
            url = server.url("/model").toString(),
            sha256 = sha256Hex(content.toByteArray()),
            sizeBytes = content.length.toLong(),
        )
        File(modelsDir, "${definition.fileName}.part").writeBytes(content.toByteArray())

        modelManager.download(definition) { _, _ -> }

        assertEquals(0, server.requestCount)
        assertEquals(content, modelManager.modelFile(definition).readText())
    }

    @Test
    fun `un parziale completo ma corrotto viene scartato col checksum non valido`() = runBlocking {
        val content = "0123456789ABCDEF".repeat(10)
        val definition = testDefinition.copy(
            url = server.url("/model").toString(),
            sha256 = sha256Hex("altro contenuto".toByteArray()),
            sizeBytes = content.length.toLong(),
        )
        File(modelsDir, "${definition.fileName}.part").writeBytes(content.toByteArray())

        try {
            modelManager.download(definition) { _, _ -> }
            fail("un parziale completo con sha256 sbagliato deve dare ModelIntegrityException")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertEquals(0, server.requestCount)
        assertFalse(File(modelsDir, "${definition.fileName}.part").exists())
    }
}
