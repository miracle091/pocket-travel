package com.pockettravel.feature.ai

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

// download() prende un LlmModelDefinition intero (non solo url/sha256 sciolti) apposta per poter
// costruire qui una definizione di test che punta al MockWebServer, verificando il checksum reale
// senza scaricare centinaia di MB da un repo HuggingFace vero — chiude il gap "nessuna verifica
// di integrita' del modello scaricato" segnalato nel log di sviluppo.
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
        fileName = "test-model.litertlm",
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
        val oldDefinition = testDefinition.copy(id = "old-model", fileName = "old-model.litertlm")
        File(modelsDir, oldDefinition.fileName).writeText("vecchio modello installato")

        val newContent = "nuovo modello"
        server.enqueue(MockResponse().setResponseCode(200).setBody(newContent))
        val newDefinition = testDefinition.copy(
            id = "new-model",
            fileName = "new-model.litertlm",
            url = server.url("/model").toString(),
            sha256 = sha256Hex(newContent.toByteArray()),
        )

        modelManager.selectAndDownload(newDefinition, currentlyInstalled = oldDefinition) { _, _ -> }

        assertFalse("il vecchio modello deve essere eliminato dopo lo switch", modelManager.isDownloaded(oldDefinition))
        assertEquals(newContent, modelManager.modelFile(newDefinition).readText())
    }
}
