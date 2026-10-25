package com.pockettravel.feature.ai

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Importazione di un modello sul dispositivo, con lo spazio misurato dal StorageManager vero e il FileProvider
 * del manifest. Da lanciare con installDebugAndroidTest + am instrument, mai con connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class ModelImportDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var manager: FileLlmModelManager

    private val content = ByteArray(2_000_000) { (it % 241).toByte() }
    private val definition = LlmModelDefinition(
        id = "import-device-test",
        displayName = "Import device test",
        url = "",
        fileName = "import-device-test.gguf",
        sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        sizeBytes = content.size.toLong(),
        minRamTier = RamTier.MINIMO,
    )

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "import-device-test").apply { deleteRecursively(); mkdirs() }
        val storageManager = context.getSystemService(StorageManager::class.java)
        manager = FileLlmModelManager(OkHttpClient(), dir, AiModelCoordinator(), Dispatchers.IO, storageManager)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun importaUnFileDelCatalogoEScartaQuelloAlterato() = runBlocking {
        val installed = manager.importModel(ByteArrayInputStream(content), definition.fileName, content.size.toLong(), listOf(definition))
        assertEquals(definition, installed)
        assertTrue(manager.isDownloaded(definition))
        assertEquals(definition.sha256, manager.installedSha256(definition))

        manager.delete(definition)
        val changed = content.copyOf().also { it[100] = (it[100] + 1).toByte() }
        try {
            manager.importModel(ByteArrayInputStream(changed), definition.fileName, changed.size.toLong(), listOf(definition))
            fail("un file con un byte cambiato non deve essere installato")
        } catch (_: ModelIntegrityException) {
            // atteso
        }
        assertFalse(manager.isDownloaded(definition))
    }

    @Test
    fun ilFileProviderDeiModelliCondivideIlFile() {
        val file = File(context.filesDir, "models/provider-check.gguf").apply { parentFile?.mkdirs(); writeText("x") }
        try {
            val uri = FileProvider.getUriForFile(context, modelFileAuthority(context), file)
            assertNotNull(context.contentResolver.openInputStream(uri))
        } finally {
            file.delete()
        }
    }
}
