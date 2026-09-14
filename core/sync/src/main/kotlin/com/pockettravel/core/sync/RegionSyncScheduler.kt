package com.pockettravel.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class RegionSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Controllo periodico del manifest, solo su Wi-Fi — mai un download automatico. */
    fun schedulePeriodicManifestCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = PeriodicWorkRequestBuilder<RegionManifestSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            SyncConfig.PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Download esplicito richiesto dall'utente per una regione. */
    fun enqueueDownload(entry: RegionManifestEntry) {
        val data = Data.Builder()
            .putString(
                RegionPackageDownloadWorker.KEY_MANIFEST_ENTRY,
                json.encodeToString(RegionManifestEntry.serializer(), entry),
            )
            .build()
        val request = OneTimeWorkRequestBuilder<RegionPackageDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            workNameFor(entry.regionId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun observeDownload(regionId: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(workNameFor(regionId)).map { it.firstOrNull() }

    fun cancelDownload(regionId: String) {
        workManager.cancelUniqueWork(workNameFor(regionId))
    }

    private fun workNameFor(regionId: String) = SyncConfig.DOWNLOAD_WORK_NAME_PREFIX + regionId
}
