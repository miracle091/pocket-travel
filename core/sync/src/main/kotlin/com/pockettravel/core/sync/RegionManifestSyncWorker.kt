package com.pockettravel.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.poiToRedownload
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Controllo periodico, qualunque rete incluse i dati cellulari (vincolo impostato da
 * [RegionSyncScheduler] — manifest.json e' pochi KB): confronta il `manifest.json` remoto con le
 * regioni già installate e, se la versione di un pacchetto installato è cambiata, notifica — i
 * pacchetti delle regioni non si scaricano mai in automatico; solo le guide, su Wi-Fi. Controlla anche civici e orari
 * dei mezzi pubblici ([regionUpdates]).
 */
@HiltWorker
class RegionManifestSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val notifier: UpdateAvailableNotifier,
    private val scheduler: RegionSyncScheduler,
    private val addressGridClient: AddressGridClient,
    private val transitClient: TransitClient,
    private val regionZonePreferences: RegionZonePreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val manifest = manifestClient.fetchManifest()
            val installedById = regionRepository.observeInstalled().first().associateBy { it.regionId }

            // Gli indici di civici e mezzi pubblici si scaricano solo se c'e' una regione che ha quel pacchetto; un errore
            // li lascia fuori dal confronto di questo giro, come nell'elenco delle nazioni.
            val addressIndex = manifest.addressGrid?.takeIf { installedById.values.any { it.addressesVersion != null } }
                ?.let { entry -> fetchOrNull { addressGridClient.fetchIndex(entry) } }
            val transitIndex = manifest.transit?.takeIf { installedById.values.any { it.transitVersion != null } }
                ?.let { entry -> fetchOrNull { transitClient.fetchIndex(entry) } }
            val updates = withContext(ioDispatcher) {
                regionUpdates(
                    manifest.regions, installedById, addressIndex, transitIndex,
                    regionZonePreferences::zone, regionRepository::installedTransitFeeds,
                )
            }
            updates.forEach { notifier.notifyUpdateAvailable(it.remote, it.kinds, it.local.poiToRedownload()) }
            // Le guide (tutte le nazioni) si installano e aggiornano da sole: sotto i 5 MB anche su rete a consumo.
            val guides = manifest.guidesChoice()
            if (regionRepository.installedGuidesVersion() != guides.installedVersion) {
                scheduler.enqueueGuidesSync(onlyOnWifi = !guides.automaticOnMeteredNetwork, automatic = true)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Log.w(TAG, "Sync del catalogo fallita", error)
            Result.failure()
        }
    }

    private suspend fun <T> fetchOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "Indice del catalogo non letto", error)
        null
    }

    private companion object {
        private val TAG = RegionManifestSyncWorker::class.java.simpleName
    }
}
