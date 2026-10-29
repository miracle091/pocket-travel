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
import com.pockettravel.core.data.PackageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** [RegionSyncScheduler] sui lavori di WorkManager. */
class WorkManagerRegionSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val appCompatibility: AppCompatibility,
) : RegionSyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    // Anche su dati cellulari: manifest.json e' pochi KB, non i pacchetti regionali veri e propri
    // (poi.db + segmenti .rd5, quelli si', mai su rete a consumo) — solo il download esplicito
    // richiesto dall'utente in enqueueDownload() resta vincolato a una rete qualunque ma sempre
    // su richiesta, mai automatico.
    override fun schedulePeriodicManifestCheck() {
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

    override fun enqueueDownload(entry: RegionManifestEntry, kinds: Set<PackageKind>): Flow<WorkInfo?>? {
        // Dati in un formato che quest'app non sa leggere: invito ad aggiornarla invece di un errore muto.
        if (appCompatibility.requiresAppUpdate()) {
            appCompatibility.showUpdateRequiredMessage()
            return null
        }
        val data = Data.Builder()
            .putString(
                RegionPackageDownloadWorker.KEY_MANIFEST_ENTRY_FILE,
                // In un file, non nei dati di lavoro (tetto di 10 KB di WorkManager, superato dalle regioni con molte tile .rd5).
                RegionPackageDownloadWorker.requestFiles(context).write(
                    json.encodeToString(RegionManifestEntry.serializer(), if (PackageKind.TRANSIT in kinds) entry else entry.copy(transit = null)),
                ),
            )
            .putStringArray(RegionPackageDownloadWorker.KEY_PACKAGE_KINDS, kinds.map { it.name }.toTypedArray())
            .build()
        val request = OneTimeWorkRequestBuilder<RegionPackageDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(SyncConfig.DOWNLOAD_TAG)
            .addTag(tagFor(entry.regionId))
            .addTag(ENQUEUED_AT_TAG + System.currentTimeMillis())
            .build()
        // Un nome univoco per richiesta, non una catena sul nome della regione (APPEND_OR_REPLACE): se un download
        // fallisce, WorkManager segnerebbe come falliti anche quelli accodati dopo. Le richieste della stessa regione
        // partono indipendenti e si mettono in fila sul lock dello staging (RegionPackageDownloader.withStagingLock);
        // il tag "$DOWNLOAD_TAG:<regionId>" le raccoglie per osservarle e annullarle.
        workManager.enqueueUniqueWork(
            SyncConfig.DOWNLOAD_WORK_NAME_PREFIX + request.id,
            ExistingWorkPolicy.KEEP,
            request,
        )
        enqueueGuidesSync(onlyOnWifi = false)
        return workManager.getWorkInfoByIdFlow(request.id)
    }

    override fun enqueueGuidesSync(onlyOnWifi: Boolean, automatic: Boolean) {
        // In automatico si salta in silenzio; su richiesta dell'utente lo si avvisa.
        if (appCompatibility.requiresAppUpdate()) {
            if (!automatic) appCompatibility.showUpdateRequiredMessage()
            return
        }
        val networkType = if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<GuidesSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SyncConfig.GUIDES_SYNC_WORK_NAME,
            if (automatic) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun observeGuidesSync(): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncConfig.GUIDES_SYNC_WORK_NAME).map { it.firstOrNull() }

    override fun observeDownload(regionId: String): Flow<WorkInfo?> =
        workManager.getWorkInfosByTagFlow(tagFor(regionId)).map(::currentDownload)

    override fun observeDownloadingRegions(): Flow<Set<String>> =
        workManager.getWorkInfosByTagFlow(SyncConfig.DOWNLOAD_TAG).map { infos ->
            infos.filter { !it.state.isFinished }
                .flatMap { it.tags }
                .filter { it.startsWith("${SyncConfig.DOWNLOAD_TAG}:") }
                .mapTo(mutableSetOf()) { it.removePrefix("${SyncConfig.DOWNLOAD_TAG}:") }
        }

    override suspend fun isDownloadPending(regionId: String): Boolean =
        workManager.getWorkInfosByTagFlow(tagFor(regionId)).first().any { !it.state.isFinished }

    override suspend fun isGuidesSyncPending(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(SyncConfig.GUIDES_SYNC_WORK_NAME).first().any { !it.state.isFinished }

    override fun cancelDownload(regionId: String) {
        workManager.cancelAllWorkByTag(tagFor(regionId))
    }

    private fun tagFor(regionId: String) = "${SyncConfig.DOWNLOAD_TAG}:$regionId"
}

// Non comincia con "$DOWNLOAD_TAG:", che observeDownloadingRegions legge come id di una regione.
internal const val ENQUEUED_AT_TAG = "region-download-at:"

/**
 * Il download da mostrare tra quelli di una regione: uno non ancora finito, altrimenti l'ultimo accodato. WorkManager
 * non restituisce i lavori di un tag in ordine, e un download fallito prima di uno riuscito non deve tornare a galla.
 */
internal fun currentDownload(infos: List<WorkInfo>): WorkInfo? =
    infos.firstOrNull { !it.state.isFinished } ?: infos.maxByOrNull { info ->
        info.tags.firstNotNullOfOrNull { tag -> tag.takeIf { it.startsWith(ENQUEUED_AT_TAG) }?.removePrefix(ENQUEUED_AT_TAG)?.toLongOrNull() } ?: 0L
    }
