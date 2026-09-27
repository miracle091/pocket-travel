package com.pockettravel.core.sync

import android.content.Context
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * All'avvio dell'app: chiude le attivazioni di pacchetti interrotte da un crash e toglie lo
 * staging che nessun download usera' piu'. Le regioni con un download in coda o in corso restano
 * al loro worker (RegionPackageInstaller recupera da se' prima di attivare).
 */
class RegionStartupRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionRepository: RegionRepository,
    private val regionStorage: RegionStorage,
    private val regionSyncScheduler: RegionSyncScheduler,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        // Staging delle versioni precedenti dell'app, in cacheDir: solo dati temporanei.
        File(context.cacheDir, "regions_staging").deleteRecursively()

        for (regionId in regionStorage.regionIdsOnDisk()) {
            if (!regionSyncScheduler.isDownloadPending(regionId)) regionRepository.recoverInterruptedActivations(regionId)
        }

        val installed = regionRepository.observeInstalled().first().map { it.regionId }.toSet()
        for (stagingId in regionStorage.stagingIds()) {
            val keep = if (stagingId == GuidesInstaller.STAGING_ID) regionSyncScheduler.isGuidesSyncPending()
            else stagingId in installed || regionSyncScheduler.isDownloadPending(stagingId)
            if (!keep) regionStorage.deleteStaging(stagingId)
        }
    }
}
