package com.pockettravel.app.regions

import com.pockettravel.app.R
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.sync.RegionSyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Elimina una regione o un suo pacchetto, per l'elenco delle regioni e per Spazio di archiviazione. */
class RegionRemoval @Inject constructor(
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val regionZonePreferences: RegionZonePreferences,
    private val recentRegionStore: RecentRegionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Toglie la regione, il suo download in corso e la sua zona. La stringa (R.string) dell'errore se i file non si eliminano, altrimenti null. */
    suspend fun remove(regionId: String): Int? {
        regionSyncScheduler.cancelDownload(regionId)
        regionZonePreferences.setZone(regionId, null)
        return removeOrReport {
            regionRepository.remove(regionId)
            withContext(ioDispatcher) { recentRegionStore.clearIfLast(regionId) }
        }
    }

    /** Toglie un pacchetto della regione. La stringa (R.string) dell'errore se i file non si eliminano, altrimenti null. */
    suspend fun removePackage(regionId: String, kind: PackageKind): Int? = removeOrReport {
        regionRepository.removePackage(regionId, kind)
        // Con l'ultimo pacchetto se ne va l'intera regione.
        if (regionRepository.installed(regionId) == null) withContext(ioDispatcher) { recentRegionStore.clearIfLast(regionId) }
    }

    // L'eliminazione dei file puo' fallire (check nel repository): un messaggio invece del crash.
    private suspend fun removeOrReport(remove: suspend () -> Unit): Int? =
        try {
            remove()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            R.string.regions_delete_error
        }
}
