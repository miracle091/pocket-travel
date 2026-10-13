package com.pockettravel.feature.ai.testing

import androidx.work.WorkInfo
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.feature.ai.AiModelUpdates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [AiModelUpdates] in memoria: [update] e' quello disponibile, [work] il lavoro di download; registra modelli pubblicati e download. */
class FakeAiModelUpdates : AiModelUpdates {
    var update: AiModelManifestEntry? = null
    var availableFailure: Throwable? = null
    var enoughSpace = true
    val work = MutableStateFlow<WorkInfo?>(null)
    val published = mutableListOf<List<AiModelManifestEntry>>()
    val downloads = mutableListOf<AiModelManifestEntry>()

    override suspend fun availableUpdate(published: List<AiModelManifestEntry>): AiModelManifestEntry? {
        this.published.add(published)
        availableFailure?.let { throw it }
        return update
    }

    override fun download(update: AiModelManifestEntry): Boolean {
        if (!enoughSpace) return false
        downloads += update
        return true
    }

    override fun observeDownload(): Flow<WorkInfo?> = work
}
