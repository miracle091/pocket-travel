package com.pockettravel.core.sync

import android.util.Log
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.currentGuidesLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Dopo un cambio di lingua dell'app riscarica da solo quello che dipende dalla lingua: le guide e le citta' delle regioni
 * installate. Mappa, rete stradale, POI, civici e orari sono gli stessi in ogni lingua e restano. Le citta' si
 * confrontano prima con il catalogo salvato, senza rete: il `manifest.json` pesa qualche MB e si scarica solo se c'e'
 * qualcosa da riprendere.
 */
class LanguageChangeReload @Inject constructor(
    private val manifestClient: ManifestClient,
    private val regionRepository: RegionRepository,
    private val scheduler: RegionSyncScheduler,
) {
    suspend fun run(language: String = currentGuidesLanguage()) {
        val guides = regionRepository.installedGuidesVersion()
        // Se il catalogo non offre guide inglesi il worker non fa nulla.
        if (guides != null && isEnglishGuidesVersion(guides) != (language == "en")) {
            scheduler.enqueueGuidesSync(onlyOnWifi = false)
        }
        val installed = regionRepository.observeInstalled().first()
        // Qualunque errore del catalogo (rete, firma, formato) lascia le ricariche gia' in coda: le riprende il prossimo avvio.
        @Suppress("TooGenericExceptionCaught")
        val toReload = try {
            val saved = manifestClient.savedManifest()
            if (saved?.citiesToReload(installed, language).isNullOrEmpty()) {
                emptyList()
            } else {
                manifestClient.fetchManifest().citiesToReload(installed, language)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Catalogo non letto", error)
            return
        }
        // Anche vuoto: annulla le ricariche di un cambio di lingua precedente ancora in coda.
        scheduler.reloadCitiesForLanguage(toReload)
    }

    private companion object {
        private val TAG = LanguageChangeReload::class.java.simpleName
    }
}
