package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.feature.ai.testing.FakeLlmModelManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.io.path.createTempDirectory

class PendingModelSwitchTest {

    private val modelsDir = createTempDirectory("pending-model-test").toFile()
    private val previousLocale = Locale.getDefault().also { Locale.setDefault(Locale.ITALIAN) }
    private val models = LlmModelCatalog.ALL.filter { LlmModelCatalog.isUsableIn(it, "it") }.take(2)
    private val inUse = models[0]
    private val target = models[1]

    private val preferences = InMemoryPreferencesFactory()
    private fun store() = AiSettingsStore(preferences, modelsDir) { RamTier.MINIMO }

    private fun state(pending: String? = null, inUseDownloaded: Boolean = true) = AiUiState(
        mode = AiEngineMode.ON_DEVICE,
        isDeviceCapable = true,
        availableModels = models,
        selectedModelId = inUse.id,
        pendingModelId = pending,
        isModelDownloaded = inUseDownloaded,
        isApiKeyConfigured = false,
    )

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        modelsDir.deleteRecursively()
    }

    @Test
    fun `scegliere un modello da scaricare lascia in uso quello scaricato`() {
        val manager = FakeLlmModelManager(setOf(inUse.id))

        assertTrue(state().keepsModelInUse(target.id, manager::isDownloaded))
    }

    @Test
    fun `un modello gia' scaricato, quello in uso o senza modello in uso scaricato si sceglie subito`() {
        val manager = FakeLlmModelManager(setOf(inUse.id, target.id))

        assertFalse(state().keepsModelInUse(target.id, manager::isDownloaded))
        assertFalse(state().keepsModelInUse(inUse.id, manager::isDownloaded))
        assertFalse(state(inUseDownloaded = false).keepsModelInUse(target.id, FakeLlmModelManager()::isDownloaded))
    }

    @Test
    fun `l'elenco mostra il modello in attesa, altrimenti quello in uso`() {
        assertEquals(inUse.id, state().listedModelId)
        assertEquals(target.id, state(pending = target.id).listedModelId)
    }

    @Test
    fun `la scelta in attesa resta salvata e la chiude scegliere un modello`() {
        store().setPendingModelId(target.id)
        assertEquals(target.id, store().pendingModelId())

        store().setSelectedModelId(target.id)

        assertNull(store().pendingModelId())
    }

    @Test
    fun `a download finito il modello in uso passa a quello scaricato`() {
        val settings = store().apply { setSelectedModelId(inUse.id); setPendingModelId(target.id) }
        val manager = FakeLlmModelManager(setOf(target.id))
        settings.setSelectedModelId(target.id)

        val after = state(pending = target.id).copy(downloadProgress = 1f).afterModelDownload(settings, manager)

        assertEquals(target.id, after.selectedModelId)
        assertNull(after.pendingModelId)
        assertNull(after.downloadProgress)
        assertTrue(after.isModelDownloaded)
    }

    @Test
    fun `senza spazio o impronta il download e' rifiutato con il messaggio giusto`() {
        assertEquals(R.string.ai_error_ram, state().downloadRefusal(target, isCapable = false, freeBytes = Long.MAX_VALUE)?.errorMessage)
        assertEquals(R.string.ai_error_space, state().downloadRefusal(target.copy(sha256 = "a".repeat(64)), true, 0L)?.errorMessage)
        assertNull(state().downloadRefusal(target.copy(sha256 = "a".repeat(64)), true, Long.MAX_VALUE))
    }
}
