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

    // Anche su dati cellulari: manifest.json e' pochi KB, non i pacchetti regionali veri e propri
    // (poi.db + segmenti .rd5, quelli si', mai su rete a consumo) — solo il download esplicito
    // richiesto dall'utente in enqueueDownload() resta vincolato a una rete qualunque ma sempre
    // su richiesta, mai automatico.
    /** Controllo periodico del manifest, qualunque rete — mai un download automatico. */
    fun schedulePeriodicManifestCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
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

    /** Controllo manuale immediato (es. tasto "Controlla aggiornamenti"), in aggiunta a quello periodico sopra. */
    fun checkNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RegionManifestSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            "${SyncConfig.PERIODIC_SYNC_WORK_NAME}-manual",
            ExistingWorkPolicy.REPLACE,
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
