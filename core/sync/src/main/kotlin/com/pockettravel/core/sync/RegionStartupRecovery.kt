package com.pockettravel.core.sync

import android.content.Context
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.poiToRedownload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * All'avvio dell'app: chiude le attivazioni di pacchetti interrotte da un crash, dimentica i
 * pacchetti registrati i cui file non ci sono piu' e toglie lo staging che nessun download usera' piu'; poi restituisce lo spazio libero di region.db. Le regioni con un download in coda o in corso restano
 * al loro worker (RegionPackageInstaller recupera da se' prima di attivare).
 */
class RegionStartupRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regionRepository: RegionRepository,
    private val regionStorage: RegionStorage,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val manifestCheckNow: RegionManifestCheckNow,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        // Staging delle versioni precedenti dell'app, in cacheDir: solo dati temporanei.
        File(context.cacheDir, "regions_staging").deleteRecursively()

        for (regionId in regionStorage.regionIdsOnDisk()) {
            if (regionSyncScheduler.isDownloadPending(regionId)) continue
            regionRepository.recoverInterruptedActivations(regionId)
            // File di una prima installazione interrotta fra l'attivazione e il database: nessuna riga
            // li conosce, resterebbero su disco per sempre. Il database si rilegge qui: un download
            // finito nel frattempo ha gia' registrato la regione.
            if (regionRepository.installed(regionId) == null && !regionSyncScheduler.isDownloadPending(regionId)) {
                regionStorage.delete(regionId)
            }
        }
        val installed = regionRepository.observeInstalled().first().map { it.regionId }.toSet()
        // Dopo il recupero delle attivazioni, che puo' rimettere a posto un pacchetto dal backup.
        for (regionId in installed) {
            if (!regionSyncScheduler.isDownloadPending(regionId)) regionRepository.forgetMissingPackages(regionId)
        }

        for (stagingId in regionStorage.stagingIds()) {
            // _transit e _address_grid tengono l'indice scaricato per versione (TransitClient,
            // AddressGridClient): cancellarli a ogni avvio li farebbe riscaricare.
            val keep = when (stagingId) {
                GuidesInstaller.STAGING_ID -> regionSyncScheduler.isGuidesSyncPending()
                TransitClient.STAGING_ID, AddressGridClient.STAGING_ID -> true
                // Anche di una regione installata: i backup delle attivazioni stanno nella cartella della regione, lo
                // staging senza un download in coda e' solo un avanzo ("Annulla" prima di Android 12, un crash).
                else -> regionSyncScheduler.isDownloadPending(stagingId)
            }
            if (!keep) regionStorage.deleteStaging(stagingId)
        }

        // Punti di interesse tolti dalla migrazione del database per mancanza di spazio: la notifica non aspetta il controllo
        // periodico (fino a un giorno), parte appena c'e' una rete.
        if (regionRepository.observeInstalled().first().any { it.poiToRedownload().isNotEmpty() }) manifestCheckNow.enqueue()

        // Le pagine che una migrazione del database (la conversione dei POI) o una rimozione fatta in un'altra sessione hanno
        // lasciato libere: per ultimo, e' il lavoro piu' lungo e non serve a niente di quanto sopra.
        regionRepository.compactDatabase()
    }
}
