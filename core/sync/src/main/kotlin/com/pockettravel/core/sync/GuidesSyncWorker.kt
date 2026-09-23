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

/**
 * Installa o aggiorna il pacchetto guide (tutte le nazioni, meno di un MB) se la versione del
 * manifest e' diversa da quella installata. Accodato da [RegionSyncScheduler]: in automatico
 * solo su Wi-Fi, subito quando l'utente scarica una regione.
 */
@HiltWorker
class GuidesSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val guidesInstaller: GuidesInstaller,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val guides = manifestClient.fetchManifest().guides
            if (regionRepository.installedGuidesVersion() != guides.version) guidesInstaller.install(guides)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: PermanentRegionPackageException) {
            Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
