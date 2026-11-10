package com.pockettravel.app.regions

import androidx.annotation.StringRes
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.TransitMissing

enum class RegionStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

/** Stato di un pacchetto (mappa, rete stradale, POI) di una regione, per il foglio "Contenuti". */
data class PackageUiState(
    val kind: PackageKind,
    val status: RegionStatus,
    // Byte da scaricare (0 per la mappa: estratta sul dispositivo, dimensione nota solo dopo).
    val downloadBytes: Long,
    // Byte occupati sul dispositivo, null se il pacchetto non e' installato o la dimensione non e' nota.
    val installedBytes: Long?,
    // Riga in piu' sotto il nome del pacchetto: le reti dei mezzi pubblici, se e' quello.
    val detail: String? = null,
    // Mezzi pubblici con piu' reti: ognuna si sceglie a parte (TransitNetworkPreferences).
    val networks: List<TransitNetworkUi> = emptyList(),
    // Mezzi pubblici con la scelta di default: come sono state scelte le reti (null se ha scelto l'utente).
    val transitDefaultReason: TransitDefaultReason? = null,
    // Mezzi pubblici: le reti della regione non ancora incluse, con il motivo.
    val missingNetworks: List<TransitMissing> = emptyList(),
    // Mezzi pubblici: nomi delle reti installate i cui orari non sono piu' aggiornati.
    val staleNetworks: List<String> = emptyList(),
)

data class TransitNetworkUi(val id: String, val name: String, val downloadBytes: Long, val selected: Boolean)

data class RegionUiItem(
    val regionId: String,
    val displayName: String,
    // Non installata: da scaricare; da aggiornare: dei soli pacchetti cambiati; installata: sul dispositivo.
    val sizeBytes: Long,
    val status: RegionStatus,
    val continent: String? = null,
    val countryCode: String? = null,
    val packages: List<PackageUiState> = emptyList(),
    // Pacchetti che il catalogo non offre per questa regione e che non sono installati (oggi solo
    // i civici: nessuna fonte o regione troppo grande): mostrati come "non disponibili".
    val unavailableKinds: List<PackageKind> = emptyList(),
    // Nazione divisa in piu' regioni e nome breve della regione nel gruppo (dal catalogo).
    val groupName: String? = null,
    val groupLabel: String? = null,
    // Rettangolo geografico della regione (dalla mappa del catalogo), per le regioni vicine del primo avvio e la scelta della zona.
    val bbox: RegionBbox? = null,
    // Zona scelta dall'utente (RegionZonePreferences): mappa, rete stradale e civici solo li'; null = tutta la regione.
    val zone: RegionZone? = null,
    // Il catalogo offre la rete stradale "solo auto" (routingCar): nel foglio Contenuti c'e' l'interruttore.
    val routingCarAvailable: Boolean = false,
    // La mappa e' tra i pacchetti da scaricare: il suo peso non e' in sizeBytes (si conosce solo estraendola).
    val includesMap: Boolean = false,
    // Voce della copia salvata del catalogo, mentre si aspetta il sito: download e scelte dei contenuti disattivati.
    val fromSavedCopy: Boolean = false,
) {
    // Parte di una nazione divisa: "Tutta la regione" invece di "Tutta la nazione" (senza catalogo, dal nome).
    val splitCountry: Boolean get() = groupName != null || isSplitCountryName(displayName, countryCode)
}

data class RegionBbox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

/** Regione installata che il catalogo ha tolto e diviso nelle regioni del gruppo [groupName]. */
data class ReplacedRegionItem(
    val regionId: String,
    val displayName: String,
    val countryCode: String?,
    val groupName: String,
    val sizeBytes: Long,
)

data class RegionListUiState(
    val items: List<RegionUiItem> = emptyList(),
    // Si possono ancora aprire (i dati sono sul dispositivo) ma non si aggiornano piu'.
    val replaced: List<ReplacedRegionItem> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    // Catalogo irraggiungibile: sostituisce l'elenco (non c'e' nulla da mostrare).
    @StringRes val loadError: Int? = null,
    // Messaggio una tantum (es. spazio insufficiente): Snackbar, poi onMessageShown().
    @StringRes val message: Int? = null,
    // Le regioni in download o in coda: nell'elenco salgono subito tra le nazioni scaricate.
    val downloadingRegions: Set<String> = emptySet(),
    // Lettura del catalogo in corso: le nazioni scaricate mostrano il controllo al posto di dimensione e stato.
    val checking: CatalogCheck? = null,
)

/** Controllo in corso: con la rete [UPDATES] (l'elenco viene dalla copia salvata), senza [CATALOG]. */
enum class CatalogCheck { UPDATES, CATALOG }
