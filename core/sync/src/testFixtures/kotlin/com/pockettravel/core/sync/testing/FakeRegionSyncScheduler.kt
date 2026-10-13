package com.pockettravel.core.sync.testing

import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * [RegionSyncScheduler] in memoria: non esegue nulla, registra le richieste ([enqueued], [guidesSyncs], [cancelled],
 * [periodicChecks]) e tiene lo stato dei download che il test puo' leggere e cambiare ([downloadState], [guidesSync],
 * [downloadingRegions], [pendingDownloads]). Con [rejectDownloads] i download non vengono accodati, come con l'app da aggiornare.
 */
class FakeRegionSyncScheduler : RegionSyncScheduler {
    /** Una richiesta di [RegionSyncScheduler.enqueueGuidesSync]. */
    data class GuidesSyncRequest(val onlyOnWifi: Boolean, val automatic: Boolean)

    var periodicChecks = 0
        private set
    val enqueued = mutableListOf<Pair<RegionManifestEntry, Set<PackageKind>>>()
    val guidesSyncs = mutableListOf<GuidesSyncRequest>()
    val cancelled = mutableListOf<String>()
    var rejectDownloads = false

    val guidesSync = MutableStateFlow<WorkInfo?>(null)
    val downloadingRegions = MutableStateFlow<Set<String>>(emptySet())
    val pendingDownloads = mutableSetOf<String>()
    var guidesSyncPending = false

    private val downloads = mutableMapOf<String, MutableStateFlow<WorkInfo?>>()

    /** Lo stato del download di [regionId], da cambiare nel test. */
    fun downloadState(regionId: String): MutableStateFlow<WorkInfo?> = downloads.getOrPut(regionId) { MutableStateFlow(null) }

    override fun schedulePeriodicManifestCheck() {
        periodicChecks++
    }

    override fun enqueueDownload(entry: RegionManifestEntry, kinds: Set<PackageKind>): Flow<WorkInfo?>? {
        if (rejectDownloads) return null
        enqueued += entry to kinds
        pendingDownloads += entry.regionId
        downloadingRegions.update { it + entry.regionId }
        return downloadState(entry.regionId)
    }

    override fun enqueueGuidesSync(onlyOnWifi: Boolean, automatic: Boolean) {
        guidesSyncs += GuidesSyncRequest(onlyOnWifi, automatic)
        guidesSyncPending = true
    }

    override fun observeGuidesSync(): Flow<WorkInfo?> = guidesSync

    override fun observeDownload(regionId: String): Flow<WorkInfo?> = downloadState(regionId)

    override fun observeDownloadingRegions(): Flow<Set<String>> = downloadingRegions

    override suspend fun isDownloadPending(regionId: String): Boolean = regionId in pendingDownloads

    override suspend fun isGuidesSyncPending(): Boolean = guidesSyncPending

    override fun cancelDownload(regionId: String) {
        cancelled += regionId
        pendingDownloads -= regionId
        downloadingRegions.update { it - regionId }
    }
}
