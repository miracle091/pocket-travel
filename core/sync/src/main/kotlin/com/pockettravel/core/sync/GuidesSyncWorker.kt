package com.pockettravel.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pockettravel.core.data.RegionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

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
            val choice = manifestClient.fetchManifest().guidesChoice()
            if (regionRepository.installedGuidesVersion() != choice.installedVersion) guidesInstaller.install(choice.entry, choice.installedVersion)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: PermanentRegionPackageException) {
            Result.failure()
        } catch (_: IOException) {
            // Errore di rete: si riprova, ma non all'infinito (un errore persistente terrebbe il lavoro in
            // attesa per sempre e lo staging delle guide non verrebbe mai ripulito). Il prossimo controllo
            // periodico del manifest lo riaccoda.
            if (shouldRetryGuidesSync(runAttemptCount)) Result.retry() else Result.failure()
        } catch (error: Exception) {
            Log.w(TAG, "Sync guide fallita", error)
            Result.failure()
        }
    }

    private companion object {
        private val TAG = GuidesSyncWorker::class.java.simpleName
    }
}

// Tentativi ripetuti dopo il primo per errori di rete, come per i pacchetti delle regioni.
private const val GUIDES_SYNC_MAX_RETRIES = 5

internal fun shouldRetryGuidesSync(runAttemptCount: Int): Boolean = runAttemptCount < GUIDES_SYNC_MAX_RETRIES
