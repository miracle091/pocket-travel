package com.pockettravel.feature.ai.testing

import com.pockettravel.feature.ai.LlmModelDefinition
import com.pockettravel.feature.ai.LlmModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream

/** Gestore dei modelli in memoria per i test JVM: nessun file su disco, lo stato dei modelli scaricati si imposta a mano. */
class FakeLlmModelManager(initial: Set<String> = emptySet()) : LlmModelManager {

    override val downloadedModelIds = MutableStateFlow(initial)

    /** Dimensione su disco dei modelli scaricati, per id; 0 se manca. */
    val sizes = mutableMapOf<String, Long>()

    var availableBytes: Long = Long.MAX_VALUE

    /** Se non nullo, [download] lo lancia. */
    var downloadFailure: Throwable? = null

    val downloaded = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val orphanCleanups = mutableListOf<String>()

    override fun modelFile(definition: LlmModelDefinition): File = File("fake-models", definition.fileName)

    override fun isDownloaded(definition: LlmModelDefinition): Boolean = definition.id in downloadedModelIds.value

    override fun sizeOnDisk(definition: LlmModelDefinition): Long =
        if (isDownloaded(definition)) sizes[definition.id] ?: definition.sizeBytes else 0L

    override fun availableStorageBytes(): Long = availableBytes

    override suspend fun download(definition: LlmModelDefinition, onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit) {
        downloadFailure?.let { throw it }
        onProgress(definition.sizeBytes, definition.sizeBytes)
        downloaded += definition.id
        downloadedModelIds.value += definition.id
    }

    override suspend fun selectAndDownload(
        newDefinition: LlmModelDefinition,
        currentlyInstalled: LlmModelDefinition?,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        if (currentlyInstalled != null && currentlyInstalled.id != newDefinition.id) delete(currentlyInstalled)
        download(newDefinition, onProgress)
    }

    /** Se non nullo, [importModel] restituisce questo modello senza leggere la sorgente. */
    var importResult: LlmModelDefinition? = null

    /** Se non nullo, [importModel] lo lancia. */
    var importFailure: Throwable? = null

    val imported = mutableListOf<String>()

    override suspend fun importModel(
        source: InputStream,
        displayName: String?,
        sizeBytes: Long,
        candidates: List<LlmModelDefinition>,
    ): LlmModelDefinition {
        importFailure?.let { throw it }
        val definition = requireNotNull(importResult) { "importResult non impostato" }
        imported += definition.id
        downloadedModelIds.value += definition.id
        return definition
    }

    override suspend fun installedSha256(definition: LlmModelDefinition): String? =
        if (isDownloaded(definition)) definition.sha256 else null

    override suspend fun delete(definition: LlmModelDefinition): Boolean = deleteWithoutLock(definition)

    override fun deleteWithoutLock(definition: LlmModelDefinition): Boolean {
        val existed = isDownloaded(definition)
        deleted += definition.id
        downloadedModelIds.value -= definition.id
        return existed
    }

    override fun deleteOrphanedFiles(selected: LlmModelDefinition) {
        orphanCleanups += selected.id
    }
}
