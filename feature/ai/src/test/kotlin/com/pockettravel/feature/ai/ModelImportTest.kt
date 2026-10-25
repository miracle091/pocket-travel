package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class ModelImportTest {

    private lateinit var modelsDir: File
    private lateinit var manager: FileLlmModelManager

    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val definition = LlmModelDefinition(
        id = "test-model",
        displayName = "Test Model",
        url = "",
        fileName = "test-model.gguf",
        sha256 = sha256Hex(content),
        sizeBytes = content.size.toLong(),
        minRamTier = RamTier.MINIMO,
    )

    @Before
    fun setUp() {
        modelsDir = createTempDirectory("pocket-travel-import-test").toFile()
        manager = FileLlmModelManager(OkHttpClient(), modelsDir, AiModelCoordinator(), Dispatchers.IO)
    }

    @After
    fun tearDown() {
        modelsDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun import(bytes: ByteArray, name: String? = null) = runBlocking {
        manager.importModel(ByteArrayInputStream(bytes), name, bytes.size.toLong(), listOf(definition))
    }

    @Test
    fun `il confronto accetta il file con lo sha256 del catalogo`() {
        val verdict = classifyImport(definition.sha256.orEmpty().uppercase(), content.size.toLong(), null, listOf(definition))

        assertEquals(ImportVerdict.Match(definition), verdict)
    }

    @Test
    fun `il confronto scarta come danneggiato un file con un byte cambiato`() {
        val changed = content.copyOf().also { it[10] = (it[10] + 1).toByte() }

        val verdict = classifyImport(sha256Hex(changed), changed.size.toLong(), null, listOf(definition))

        assertEquals(ImportVerdict.Damaged, verdict)
    }

    @Test
    fun `il confronto scarta come danneggiato un file incompleto con il nome del catalogo`() {
        val truncated = content.copyOf(1000)

        val verdict = classifyImport(sha256Hex(truncated), truncated.size.toLong(), definition.fileName, listOf(definition))

        assertEquals(ImportVerdict.Damaged, verdict)
    }

    @Test
    fun `il confronto scarta un file fuori dal catalogo`() {
        val other = "un altro file".toByteArray()

        val verdict = classifyImport(sha256Hex(other), other.size.toLong(), "foto.jpg", listOf(definition))

        assertEquals(ImportVerdict.Unknown, verdict)
    }

    @Test
    fun `il confronto ignora i modelli senza sha256`() {
        val verdict = classifyImport(sha256Hex(content), content.size.toLong(), null, listOf(definition.copy(sha256 = null)))

        assertEquals(ImportVerdict.Unknown, verdict)
    }

    @Test
    fun `importa un file del catalogo come dopo un download`() {
        val result = import(content)

        assertEquals(definition, result)
        assertTrue(manager.isDownloaded(definition))
        assertTrue(content.contentEquals(manager.modelFile(definition).readBytes()))
        assertEquals(definition.sha256, runBlocking { manager.installedSha256(definition) })
        assertFalse(File(modelsDir, "import.part").exists())
    }

    @Test
    fun `un byte cambiato blocca l'importazione e non lascia file`() {
        val changed = content.copyOf().also { it[0] = (it[0] + 1).toByte() }

        try {
            import(changed)
            fail("un file alterato non deve essere installato")
        } catch (_: ModelIntegrityException) {
            // atteso
        }

        assertFalse(manager.isDownloaded(definition))
        assertEquals(emptyList<String>(), modelsDir.list().orEmpty().toList())
    }

    @Test
    fun `un file sconosciuto blocca l'importazione e non lascia file`() {
        try {
            import("tutt'altro".toByteArray(), "appunti.txt")
            fail("un file fuori dal catalogo non deve essere installato")
        } catch (_: ModelUnknownFileException) {
            // atteso
        }

        assertEquals(emptyList<String>(), modelsDir.list().orEmpty().toList())
    }

    @Test
    fun `senza spazio libero l'importazione si ferma prima della copia`() {
        val noSpace = runCatching {
            runBlocking { manager.importModel(ByteArrayInputStream(content), null, Long.MAX_VALUE, listOf(definition)) }
        }.exceptionOrNull()

        assertTrue(noSpace is ModelNoSpaceException)
        assertEquals(emptyList<String>(), modelsDir.list().orEmpty().toList())
    }
}
