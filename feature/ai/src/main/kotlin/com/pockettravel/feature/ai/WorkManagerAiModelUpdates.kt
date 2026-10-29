package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import com.pockettravel.core.sync.AiModelManifestEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** [AiModelUpdates] con il confronto del controllo periodico ([LlmModelUpdateCheckWorker]) e il download di WorkManager. */
class WorkManagerAiModelUpdates @Inject constructor(
    private val modelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
    private val downloadScheduler: LlmModelDownloadScheduler,
) : AiModelUpdates {
    override suspend fun availableUpdate(published: List<AiModelManifestEntry>): AiModelManifestEntry? {
        val definition = aiSettingsStore.selectedModelDefinition()
        val remote = published.firstOrNull { it.modelId == definition.id }.takeIf { modelManager.isDownloaded(definition) }
        // Lo sha256 si calcola solo se c'è una versione pubblicata da confrontare.
        val installedSha256 = remote?.let { modelManager.installedSha256(definition) }
        return remote?.takeIf { installedSha256 != null && isAiModelUpdateAvailable(installedSha256, it) }
    }

    override fun download(update: AiModelManifestEntry): Boolean {
        if (modelManager.availableStorageBytes() < update.sizeBytes) return false
        downloadScheduler.enqueueDownload(update.modelId)
        return true
    }

    override fun observeDownload(): Flow<WorkInfo?> = downloadScheduler.observeDownload()
}
