package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import com.pockettravel.core.sync.AiModelManifestEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Aggiornamento del modello IA sul dispositivo, per la schermata Aggiornamenti: stesso confronto del controllo periodico
 * ([LlmModelUpdateCheckWorker]) e stesso download di "Scarica" ([LlmModelDownloadScheduler]).
 */
class AiModelUpdates @Inject constructor(
    private val modelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
    private val downloadScheduler: LlmModelDownloadScheduler,
) {
    /** La versione pubblicata del modello scelto se è scaricato e ha un'impronta diversa, altrimenti null. */
    suspend fun availableUpdate(published: List<AiModelManifestEntry>): AiModelManifestEntry? {
        val definition = aiSettingsStore.selectedModelDefinition()
        val remote = published.firstOrNull { it.modelId == definition.id }.takeIf { modelManager.isDownloaded(definition) }
        // Lo sha256 si calcola solo se c'è una versione pubblicata da confrontare.
        val installedSha256 = remote?.let { modelManager.installedSha256(definition) }
        return remote?.takeIf { installedSha256 != null && isAiModelUpdateAvailable(installedSha256, it) }
    }

    /** Mette in coda il download della versione pubblicata: false se lo spazio libero non basta. */
    fun download(update: AiModelManifestEntry): Boolean {
        if (modelManager.availableStorageBytes() < update.sizeBytes) return false
        downloadScheduler.enqueueDownload()
        return true
    }

    fun observeDownload(): Flow<WorkInfo?> = downloadScheduler.observeDownload()
}
