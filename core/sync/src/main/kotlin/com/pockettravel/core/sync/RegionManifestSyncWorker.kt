package com.pockettravel.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pockettravel.core.data.RegionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Controllo periodico, qualunque rete incluse i dati cellulari (vincolo impostato da
 * [RegionSyncScheduler] — manifest.json e' pochi KB): confronta il manifest remoto con le
 * regioni già installate e, se una versione è cambiata, notifica — non scarica mai
 * automaticamente. Vedi "Flusso di sincronizzazione" nella specifica tecnica.
 */
@HiltWorker
class RegionManifestSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val notifier: UpdateAvailableNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val manifest = manifestClient.fetchManifest()
            val installedById = regionRepository.observeInstalled().first().associateBy { it.regionId }

            manifest.regions.forEach { remote ->
                val local = installedById[remote.regionId]
                if (local != null && local.version != remote.version) {
                    notifier.notifyUpdateAvailable(remote)
                }
            }
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
