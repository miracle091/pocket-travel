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

// download() accetta modelUrl/expectedSha256 (default di produzione: AiModelConfig) apposta per
// poter verificare qui il checksum reale senza scaricare 584 MB da un repo HuggingFace gated —
// chiude il gap "nessuna verifica di integrita' del modello scaricato" segnalato nel log di sviluppo.
class LlmModelManagerTest {

    private val server = MockWebServer()
    private lateinit var modelManager: LlmModelManager
    private lateinit var modelsDir: File

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

        modelManager.download(modelUrl = server.url("/model").toString(), expectedSha256 = sha256Hex(content.toByteArray())) { _, _ -> }

        assertEquals(content, modelManager.modelFile.readText())
        assertFalse(File(modelsDir, "${AiModelConfig.MODEL_FILE_NAME}.part").exists())
    }

    @Test
    fun `un checksum diverso da quello atteso blocca l'installazione`() = runBlocking {
        val content = "contenuto scaricato"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))
        val wrongSha256 = sha256Hex("contenuto diverso".toByteArray())

        try {
            modelManager.download(modelUrl = server.url("/model").toString(), expectedSha256 = wrongSha256) { _, _ -> }
            fail("un checksum sbagliato deve bloccare l'installazione del modello")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertFalse("il modello non deve essere installato se il checksum non corrisponde", modelManager.isDownloaded())
        assertFalse(File(modelsDir, "${AiModelConfig.MODEL_FILE_NAME}.part").exists())
    }

    @Test
    fun `un token viene inviato come header Authorization Bearer`() = runBlocking {
        val content = "modello finto"
        server.enqueue(MockResponse().setResponseCode(200).setBody(content))

        modelManager.download(
            modelUrl = server.url("/model").toString(),
            expectedSha256 = sha256Hex(content.toByteArray()),
            hfToken = "hf_test_token",
        ) { _, _ -> }

        assertEquals("Bearer hf_test_token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `una risposta 401 lancia ModelAuthException invece di un errore generico`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            modelManager.download(modelUrl = server.url("/model").toString()) { _, _ -> }
            fail("un 401 deve lanciare ModelAuthException")
        } catch (_: ModelAuthException) {
            // atteso
        }

        assertFalse(File(modelsDir, "${AiModelConfig.MODEL_FILE_NAME}.part").exists())
    }

    @Test
    fun `riprende un download parziale con Range e Content-Range`() = runBlocking {
        val fullText = "0123456789ABCDEF".repeat(10)
        val alreadyDownloaded = fullText.substring(0, 60)
        val remaining = fullText.substring(60)
        File(modelsDir, "${AiModelConfig.MODEL_FILE_NAME}.part").writeBytes(alreadyDownloaded.toByteArray())

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 60-${fullText.length - 1}/${fullText.length}")
                .setBody(remaining),
        )

        modelManager.download(modelUrl = server.url("/model").toString(), expectedSha256 = sha256Hex(fullText.toByteArray())) { _, _ -> }

        assertEquals(fullText, modelManager.modelFile.readText())
        val request = server.takeRequest()
        assertEquals("bytes=60-", request.getHeader("Range"))
    }

    @Test
    fun `un errore 404 e' permanente`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        try {
            modelManager.download(modelUrl = server.url("/model").toString()) { _, _ -> }
            fail("un 404 deve essere un errore permanente")
        } catch (_: ModelDownloadFailedException) {
            // atteso
        }
    }

    @Test
    fun `un errore 500 non e' permanente ed e' quindi ritentabile`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            modelManager.download(modelUrl = server.url("/model").toString()) { _, _ -> }
            fail("un 500 deve restare un errore transitorio ritentabile, non permanente")
        } catch (_: ModelDownloadFailedException) {
            fail("un 500 non deve essere trattato come permanente")
        } catch (_: IOException) {
            // atteso: errore transitorio, il worker chiamante puo' ritentare
        }
    }
}
