package com.pockettravel.core.sync

import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import kotlinx.coroutines.flow.Flow

/** Accoda e osserva i lavori di sincronizzazione: controllo del catalogo, download delle regioni, guide. Implementazione: [WorkManagerRegionSyncScheduler]. */
interface RegionSyncScheduler {
    /** Controllo periodico del catalogo, qualunque rete — mai un download automatico. */
    fun schedulePeriodicManifestCheck()

    /**
     * Download esplicito richiesto dall'utente dei pacchetti [kinds] di una regione. Accoda anche
     * il controllo delle guide senza vincolo di Wi-Fi: chi scarica una regione si aspetta di
     * trovarne subito la guida. Restituisce lo stato di questo solo lavoro (anche con altri download della regione in
     * catena), null se non accodato.
     */
    fun enqueueDownload(entry: RegionManifestEntry, kinds: Set<PackageKind>): Flow<WorkInfo?>?

    /**
     * Installa o aggiorna il pacchetto guide se il catalogo ne ha una versione diversa. In
     * automatico ([automatic]) non sostituisce una richiesta gia' in coda; su richiesta
     * dell'utente la sostituisce, cosi' non resta in attesa del Wi-Fi. [onlyOnWifi] aspetta una rete senza
     * limiti di traffico: in automatico solo per le guide da 5 MB in su (GuidesChoice.automaticOnMeteredNetwork).
     */
    fun enqueueGuidesSync(onlyOnWifi: Boolean, automatic: Boolean = onlyOnWifi)

    fun observeGuidesSync(): Flow<WorkInfo?>

    /** Lo stato del download della regione: quello non ancora finito, altrimenti l'ultimo; null senza download. */
    fun observeDownload(regionId: String): Flow<WorkInfo?>

    /** Le regioni con un download in coda o in corso: nell'elenco stanno con le nazioni scaricate. */
    fun observeDownloadingRegions(): Flow<Set<String>>

    /** Vero se il download della regione e' in coda, in attesa di un nuovo tentativo o in corso. */
    suspend fun isDownloadPending(regionId: String): Boolean

    suspend fun isGuidesSyncPending(): Boolean

    fun cancelDownload(regionId: String)
}
