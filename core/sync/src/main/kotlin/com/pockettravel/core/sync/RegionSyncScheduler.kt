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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class RegionSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val appCompatibility: AppCompatibility,
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

    /**
     * Download esplicito richiesto dall'utente dei pacchetti [kinds] di una regione. Accoda anche
     * il controllo delle guide senza vincolo di Wi-Fi: chi scarica una regione si aspetta di
     * trovarne subito la guida.
     */
    fun enqueueDownload(entry: RegionManifestEntry, kinds: Set<PackageKind>) {
        // Dati in un formato che quest'app non sa leggere: invito ad aggiornarla invece di un errore muto.
        if (appCompatibility.requiresAppUpdate()) return appCompatibility.showUpdateRequiredMessage()
        val data = Data.Builder()
            .putString(
                RegionPackageDownloadWorker.KEY_MANIFEST_ENTRY,
                json.encodeToString(RegionManifestEntry.serializer(), entry),
            )
            .putStringArray(RegionPackageDownloadWorker.KEY_PACKAGE_KINDS, kinds.map { it.name }.toTypedArray())
            .build()
        val request = OneTimeWorkRequestBuilder<RegionPackageDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE, non KEEP: se un download di questa regione e' gia' in coda o in corso,
        // una richiesta di altri pacchetti (es. routing mentre scarica map) si accoda invece di
        // sparire in silenzio. Il worker legge i kinds dal proprio input, quindi ogni link della
        // catena installa solo i pacchetti richiesti in quella chiamata, senza toccare lo staging
        // (nome per kinds+versione) degli altri. cancelDownload usa lo stesso nome univoco e annulla
        // l'intera catena, incluse le richieste ancora in coda.
        workManager.enqueueUniqueWork(
            workNameFor(entry.regionId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        enqueueGuidesSync(onlyOnWifi = false)
    }

    /**
     * Installa o aggiorna il pacchetto guide se il manifest ne ha una versione diversa. In
     * automatico ([onlyOnWifi]) non sostituisce una richiesta gia' in coda; su richiesta
     * dell'utente la sostituisce, cosi' non resta in attesa del Wi-Fi.
     */
    fun enqueueGuidesSync(onlyOnWifi: Boolean) {
        // In automatico (onlyOnWifi) si salta in silenzio; su richiesta dell'utente lo si avvisa.
        if (appCompatibility.requiresAppUpdate()) {
            if (!onlyOnWifi) appCompatibility.showUpdateRequiredMessage()
            return
        }
        val networkType = if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<GuidesSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SyncConfig.GUIDES_SYNC_WORK_NAME,
            if (onlyOnWifi) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun observeGuidesSync(): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncConfig.GUIDES_SYNC_WORK_NAME).map { it.firstOrNull() }

    // Con APPEND_OR_REPLACE (vedi enqueueDownload) il nome univoco puo' avere piu' download in catena:
    // si segue quello non ancora finito, altrimenti l'ultimo; il primo della lista poteva essere un
    // download gia' riuscito mentre il successivo scaricava ancora.
    fun observeDownload(regionId: String): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(workNameFor(regionId))
            .map { infos -> infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull() }

    /** Vero se il download della regione e' in coda, in attesa di un nuovo tentativo o in corso. */
    suspend fun isDownloadPending(regionId: String): Boolean = isPending(workNameFor(regionId))

    suspend fun isGuidesSyncPending(): Boolean = isPending(SyncConfig.GUIDES_SYNC_WORK_NAME)

    private suspend fun isPending(workName: String): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(workName).first().any { !it.state.isFinished }

    fun cancelDownload(regionId: String) {
        workManager.cancelUniqueWork(workNameFor(regionId))
    }

    private fun workNameFor(regionId: String) = SyncConfig.DOWNLOAD_WORK_NAME_PREFIX + regionId
}
