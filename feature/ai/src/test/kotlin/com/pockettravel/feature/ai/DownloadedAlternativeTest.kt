package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedAlternativeTest {
    private val models = LlmModelCatalog.ALL.take(2)

    private fun state(selected: String, downloaded: Set<String>, progress: Float? = null) = AiUiState(
        mode = AiEngineMode.ON_DEVICE,
        isDeviceCapable = true,
        availableModels = models,
        selectedModelId = selected,
        isModelDownloaded = selected in downloaded,
        downloadedModelIds = downloaded,
        downloadProgress = progress,
        isApiKeyConfigured = false,
    )

    @Test
    fun `propone il modello gia' scaricato se quello scelto manca`() {
        assertEquals(models[0].id, downloadedAlternative(state(models[1].id, setOf(models[0].id)))?.id)
    }

    @Test
    fun `niente proposta se nessun altro modello e' scaricato o se un download e' in corso`() {
        assertNull(downloadedAlternative(state(models[1].id, emptySet())))
        assertNull(downloadedAlternative(state(models[1].id, setOf(models[0].id), progress = 0.3f)))
    }
}
