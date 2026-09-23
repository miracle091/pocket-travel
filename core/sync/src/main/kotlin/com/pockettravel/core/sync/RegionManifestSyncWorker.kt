package com.pockettravel.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Controllo periodico, qualunque rete incluse i dati cellulari (vincolo impostato da
 * [RegionSyncScheduler] — manifest.json e' pochi KB): confronta il manifest remoto con le
 * regioni già installate e, se la versione di un pacchetto installato è cambiata, notifica — i
 * pacchetti delle regioni non si scaricano mai in automatico; solo le guide, su Wi-Fi.
 * Vedi "Flusso di sincronizzazione" nella specifica tecnica.
 */
@HiltWorker
class RegionManifestSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val notifier: UpdateAvailableNotifier,
    private val scheduler: RegionSyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val manifest = manifestClient.fetchManifest()
            val installedById = regionRepository.observeInstalled().first().associateBy { it.regionId }

            manifest.regions.forEach { remote ->
                val local = installedById[remote.regionId]
                if (local == null) return@forEach
                val outdated = PackageKind.entries.filterTo(mutableSetOf()) { kind ->
                    local.versionOf(kind)?.let { it != remote.versionOf(kind) } == true
                }
                if (outdated.isNotEmpty()) notifier.notifyUpdateAvailable(remote, outdated)
            }
            // Le guide (tutte le nazioni, meno di un MB) si installano e aggiornano da sole, su Wi-Fi.
            if (regionRepository.installedGuidesVersion() != manifest.guides.version) scheduler.enqueueGuidesSync(onlyOnWifi = true)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
