package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
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
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

// L'mmproj su disco: scaricato su richiesta e verificato, tenuto dalla pulizia, tolto col suo modello se nessun altro lo usa.
class MmprojFilesTest {

    private val server = MockWebServer()
    private lateinit var modelsDir: File
    private lateinit var manager: FileLlmModelManager

    private val itTwoB = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-2b" }
    private val enTwoB = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-2b-en" }
    private val smallModel = LlmModelCatalog.ALL.first { it.id == "pt-qwen3.5-0.8b" }
    private val mmprojName = requireNotNull(itTwoB.mmproj).fileName

    @Before
    fun setUp() {
        server.start()
        modelsDir = createTempDirectory("pocket-travel-mmproj-test").toFile()
        manager = FileLlmModelManager(OkHttpClient(), modelsDir, AiModelCoordinator(), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.shutdown()
        modelsDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun install(model: LlmModelDefinition) = File(modelsDir, model.fileName).writeText("modello ${model.id}")

    private fun installMmproj() {
        File(modelsDir, mmprojName).writeText("mmproj")
        File(modelsDir, "$mmprojName.sha256").writeText("x")
    }

    private fun withServerMmproj(content: String, sha256: String = sha256Hex(content.toByteArray())) =
        itTwoB.copy(mmproj = requireNotNull(itTwoB.mmproj).copy(url = server.url("/mmproj").toString(), sha256 = sha256, sizeBytes = content.length.toLong()))

    @Test
    fun `scarica l'mmproj accanto ai modelli e lo verifica con lo sha256`() = runBlocking {
        val content = "mmproj finto ".repeat(50)
        server.enqueue(MockResponse().setBody(content))
        val definition = withServerMmproj(content)
        assertFalse(manager.isMmprojDownloaded(definition))

        manager.downloadMmproj(definition) { _, _ -> }

        assertTrue(manager.isMmprojDownloaded(definition))
        assertEquals(content, File(modelsDir, mmprojName).readText())
        assertFalse(File(modelsDir, "$mmprojName.part").exists())
        assertFalse(manager.isDownloaded(definition))
    }

    @Test
    fun `un mmproj con lo sha256 sbagliato non si installa`() = runBlocking {
        val content = "mmproj finto"
        server.enqueue(MockResponse().setBody(content))
        val definition = withServerMmproj(content, sha256 = sha256Hex("altro".toByteArray()))

        try {
            manager.downloadMmproj(definition) { _, _ -> }
            fail("lo sha256 sbagliato deve bloccare l'installazione")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertFalse(manager.isMmprojDownloaded(definition))
        assertFalse(File(modelsDir, "$mmprojName.part").exists())
    }

    @Test
    fun `un modello senza mmproj non lo scarica`() = runBlocking {
        try {
            manager.downloadMmproj(smallModel) { _, _ -> }
            fail("lo 0.8B non legge le foto")
        } catch (_: IllegalArgumentException) {
            // atteso
        }
        assertFalse(manager.isMmprojDownloaded(smallModel))
    }

    @Test
    fun `la pulizia dei file orfani tiene l'mmproj anche con un altro modello scelto`() {
        install(smallModel)
        installMmproj()
        File(modelsDir, "vecchio.litertlm").writeText("orfano")

        manager.deleteOrphanedFiles(smallModel)

        assertTrue(File(modelsDir, mmprojName).exists())
        assertTrue(File(modelsDir, "$mmprojName.sha256").exists())
        assertFalse(File(modelsDir, "vecchio.litertlm").exists())
    }

    @Test
    fun `eliminando il modello si elimina anche il suo mmproj`() = runBlocking {
        install(itTwoB)
        installMmproj()

        manager.delete(itTwoB)

        assertFalse(File(modelsDir, mmprojName).exists())
        assertFalse(File(modelsDir, "$mmprojName.sha256").exists())
    }

    @Test
    fun `l'mmproj resta finche' un altro 2B installato lo usa`() = runBlocking {
        install(itTwoB)
        install(enTwoB)
        installMmproj()

        manager.delete(itTwoB)
        assertTrue(File(modelsDir, mmprojName).exists())

        manager.delete(enTwoB)
        assertFalse(File(modelsDir, mmprojName).exists())
    }

    @Test
    fun `eliminando un modello senza mmproj il file degli altri resta`() = runBlocking {
        install(smallModel)
        installMmproj()

        manager.delete(smallModel)

        assertTrue(File(modelsDir, mmprojName).exists())
    }
}
