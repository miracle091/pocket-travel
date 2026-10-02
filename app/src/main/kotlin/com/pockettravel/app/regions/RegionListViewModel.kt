package com.pockettravel.app.regions

import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.MapDetailPreferences
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RegionZonePreferences
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionPackage
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.ReplacedRegion
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.attachAddressGridCells
import com.pockettravel.core.sync.restrictedTo
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.core.sync.guidesChoice
import com.pockettravel.core.ui.countryName
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import javax.inject.Inject

enum class RegionStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

/** Stato di un pacchetto (mappa, routing, POI) di una regione, per il foglio "Pacchetti". */
data class PackageUiState(
    val kind: PackageKind,
    val status: RegionStatus,
    // Byte da scaricare (0 per la mappa: estratta sul device, dimensione nota solo dopo).
    val downloadBytes: Long,
    // Byte occupati sul device, null se il pacchetto non e' installato o la dimensione non e' nota.
    val installedBytes: Long?,
    // Riga in piu' sotto il nome del pacchetto: le reti dei mezzi pubblici, se e' quello.
    val detail: String? = null,
    // Mezzi pubblici con piu' reti: ognuna si sceglie a parte (TransitNetworkPreferences).
    val networks: List<TransitNetworkUi> = emptyList(),
    // Mezzi pubblici con la scelta di default: come sono state scelte le reti (null se ha scelto l'utente).
    val transitDefaultReason: TransitDefaultReason? = null,
)

data class TransitNetworkUi(val id: String, val name: String, val downloadBytes: Long, val selected: Boolean)

data class RegionUiItem(
    val regionId: String,
    val displayName: String,
    // Non installata: da scaricare; da aggiornare: dei soli pacchetti cambiati; installata: sul device.
    val sizeBytes: Long,
    val status: RegionStatus,
    val continent: String? = null,
    val countryCode: String? = null,
    val packages: List<PackageUiState> = emptyList(),
    // Pacchetti che il manifest non offre per questa regione e che non sono installati (oggi solo
    // i civici: nessuna fonte o regione troppo grande): mostrati come "non disponibili".
    val unavailableKinds: List<PackageKind> = emptyList(),
    // Paese diviso in piu' regioni e nome breve della regione nel gruppo (dal manifest).
    val groupName: String? = null,
    val groupLabel: String? = null,
    // Riquadro geografico della regione (dalla mappa del manifest), per le regioni vicine del primo avvio e la scelta della zona.
    val bbox: RegionBbox? = null,
    // Zona scelta dall'utente (RegionZonePreferences): mappa, percorsi e civici solo li'; null = tutta la regione.
    val zone: RegionZone? = null,
    // La mappa e' tra i pacchetti da scaricare: il suo peso non e' in sizeBytes (si conosce solo estraendola).
    val includesMap: Boolean = false,
)

data class RegionBbox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double)

/** Regione installata che il manifest ha tolto e diviso nelle regioni del gruppo [groupName]. */
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
)

private data class LoadStatus(
    val isLoading: Boolean = true,
    @StringRes val loadError: Int? = null,
    @StringRes val message: Int? = null,
)

@HiltViewModel
class RegionListViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val addressGridClient: AddressGridClient,
    private val transitClient: TransitClient,
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val appUpdateCheckScheduler: AppUpdateCheckScheduler,
    private val llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler,
    private val usageModePreferences: UsageModePreferences,
    private val transitNetworkPreferences: TransitNetworkPreferences,
    private val mapDetailPreferences: MapDetailPreferences,
    private val regionZonePreferences: RegionZonePreferences,
    private val lastKnownPosition: LastKnownPosition,
    private val countryLocator: CountryLocator,
) : ViewModel() {

    // Per le reti dei mezzi pubblici vicine (defaultTransitChoice): ultima posizione nota e il suo paese, senza GPS.
    private val place = MutableStateFlow<DevicePlace?>(null)

    private val manifestRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    // Manifest e indice dei mezzi pubblici come arrivano: manifestRegions li unisce con le reti scelte.
    private val baseRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    private val transitIndex = MutableStateFlow<TransitIndex?>(null)
    private val replacedRegions = MutableStateFlow<List<ReplacedRegion>>(emptyList())
    private val status = MutableStateFlow(LoadStatus())
    private val query = MutableStateFlow("")
    private val locale = MutableStateFlow(Locale.getDefault())

    /** Lingua dell'interfaccia, per i nomi dei paesi (vedi [localizedNames]); la passa la schermata. */
    fun setLocale(newLocale: Locale) {
        locale.value = newLocale
    }

    val uiState = combine(
        // Ogni regione con la sua zona: dimensioni e versioni (quella dei civici dipende dalle celle) sono quelle della zona.
        combine(manifestRegions, locale, regionZonePreferences.zones) { regions, currentLocale, zones ->
            regions.map { it.localizedNames(currentLocale) to zones[it.regionId] }
        },
        // Con "Indicazioni" la dimensione di "Scarica" comprende i percorsi.
        combine(regionRepository.observeInstalled(), usageModePreferences.wantsDirections, ::Pair),
        status,
        query,
        replacedRegions,
    ) { zonedRegions, (installed, wantsDirections), currentStatus, currentQuery, replacedByManifest ->
        val remoteRegions = zonedRegions.map { it.first }
        val installedByRegion = installed.associateBy { it.regionId }
        // Senza catalogo (offline, o non ancora letto) le nazioni installate restano apribili: i loro dati sono sul telefono.
        val items = if (remoteRegions.isEmpty()) {
            installed.filter { matchesQuery(it.displayName, currentQuery) }.map { offlineRegionItem(it, regionRepository::packageBytes) }
        } else {
            zonedRegions
                .filter { (remote, _) -> matchesQuery(remote.displayName, currentQuery) }
                .map { (remote, zone) ->
                    regionUiItem(remote.restrictedTo(zone), installedByRegion[remote.regionId], regionRepository::packageBytes, regionRepository::installedAddressCells, wantsDirections, transitIndex.value != null)
                        .copy(bbox = remote.map.source.let { RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat) }, zone = zone)
                }
        }
        val replaced = replacedItems(installed, remoteRegions, replacedByManifest).filter { matchesQuery(it.displayName, currentQuery) }
        RegionListUiState(
            items = items,
            replaced = replaced,
            query = currentQuery,
            isLoading = currentStatus.isLoading,
            loadError = currentStatus.loadError,
            message = currentStatus.message,
        )
    }
        // packageBytes legge le dimensioni di mappa e routing dal disco.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegionListUiState())

    init {
        refresh()
        // Le reti scelte cambiano versione e dimensione del pacchetto: le regioni si ricalcolano subito.
        viewModelScope.launch {
            combine(baseRegions, transitIndex, transitNetworkPreferences.excluded, place) { regions, index, stored, here ->
                val choices = effectiveTransitChoices(regions, index, stored, here)
                attachTransitFeeds(regions, index, choices.excluded, choices.reasons)
            }.collect { manifestRegions.value = it }
        }
        viewModelScope.launch(Dispatchers.Default) {
            place.value = lastKnownPosition.get()?.let { (lat, lon) -> DevicePlace(lat, lon, countryLocator.countryAt(lat, lon)) }
        }
    }

    /** Aggiunge o toglie una rete dei mezzi pubblici della regione (foglio Contenuti). */
    fun setTransitNetwork(regionId: String, feedId: String, included: Boolean) {
        val region = baseRegions.value.filter { it.regionId == regionId }
        val defaults = effectiveTransitChoices(region, transitIndex.value, emptyMap(), place.value).excluded[regionId].orEmpty()
        transitNetworkPreferences.setIncluded(regionId, feedId, included, defaults)
    }

    fun refresh() {
        viewModelScope.launch {
            status.update { it.copy(isLoading = true) }
            try {
                val manifest = manifestClient.fetchManifest()
                // Civici a griglia: un errore qui (rete, indice non
                // valido) non deve bloccare l'elenco delle regioni, solo lasciarle senza civici a
                // griglia per questo aggiornamento — riprovera' al prossimo refresh().
                val addressGridIndex = manifest.addressGrid?.let { entry -> runCatching { addressGridClient.fetchIndex(entry) }.getOrNull() }
                // Come i civici: senza indice dei mezzi pubblici le regioni restano senza quel pacchetto.
                val transitIndex = manifest.transit?.let { entry -> runCatching { transitClient.fetchIndex(entry) }.getOrNull() }
                this@RegionListViewModel.transitIndex.value = transitIndex
                baseRegions.value = attachAddressGridCells(manifest.regions, addressGridIndex)
                replacedRegions.value = manifest.replacedRegions
                // Le guide si aggiornano da sole (su Wi-Fi) anche da qui, non solo col controllo periodico.
                if (regionRepository.installedGuidesVersion() != manifest.guidesChoice().installedVersion) regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = true)
                // Regioni installate prima che il database salvasse il codice paese: serve alla bandiera in Spazio.
                val withoutCode = regionRepository.observeInstalled().first().filter { it.countryCode == null }.mapTo(mutableSetOf()) { it.regionId }
                manifest.regions.filter { it.regionId in withoutCode }.forEach { remote ->
                    remote.countryCode?.let { regionRepository.fillCountryCode(remote.regionId, it) }
                }
                status.update { it.copy(loadError = null) }
            } catch (_: Exception) {
                status.update { it.copy(loadError = R.string.regions_load_error) }
            } finally {
                status.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Controllo manuale immediato, in aggiunta a quello periodico: manifest regioni (qui, sincrono)
     *  + versione app e modello IA (in background, notificano se c'e' un aggiornamento). */
    fun checkForUpdatesNow() {
        refresh()
        appUpdateCheckScheduler.checkNow()
        llmModelUpdateCheckScheduler.checkNow()
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    /** Non installata: scarica tutti i pacchetti. Installata: aggiorna solo quelli cambiati. */
    fun download(regionId: String) {
        val entry = zonedEntry(regionId) ?: return
        viewModelScope.launch {
            val local = regionRepository.installed(regionId)
            enqueue(entry, if (local == null) entry.downloadKinds(usageModePreferences.wantsDirections.value) else outdatedKinds(entry, local))
        }
    }

    /** Scarica i pacchetti scelti di una regione (primo avvio, selezione multipla): false se non e' partito. */
    fun downloadKinds(regionId: String, kinds: Set<PackageKind>): Boolean {
        val entry = zonedEntry(regionId) ?: return false
        return enqueue(entry, kinds)
    }

    /** Scarica o aggiorna un solo pacchetto della regione (foglio "Pacchetti"). */
    fun downloadPackage(regionId: String, kind: PackageKind) {
        val entry = zonedEntry(regionId) ?: return
        enqueue(entry, setOf(kind))
    }

    private fun enqueue(entry: RegionManifestEntry, kinds: Set<PackageKind>): Boolean {
        if (kinds.isEmpty()) return false
        if (regionRepository.availableStorageBytes() < entry.downloadBytes(kinds)) {
            status.update { it.copy(message = R.string.regions_not_enough_space) }
            return false
        }
        transitNetworkPreferences.rememberChoice(entry, kinds)
        regionSyncScheduler.enqueueDownload(entry, kinds)
        return true
    }

    fun onMessageShown() {
        status.update { it.copy(message = null) }
    }

    fun delete(regionId: String) {
        regionSyncScheduler.cancelDownload(regionId)
        regionZonePreferences.setZone(regionId, null)
        viewModelScope.launch { regionRepository.remove(regionId) }
    }

    fun deletePackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch { regionRepository.removePackage(regionId, kind) }
    }

    /** Mappa leggera: la scelta dell'utente o, senza, com'e' la mappa installata; senza mappa leggera (il default). */
    fun observeMapLight(regionId: String): Flow<Boolean> =
        combine(mapDetailPreferences.choices, mapDetailPreferences.installedLight, regionRepository.observeInstalled()) { choices, light, installed ->
            choices[regionId] ?: (regionId in light || installed.none { it.regionId == regionId && it.versionOf(PackageKind.MAP) != null })
        }

    /**
     * Sceglie la mappa leggera o dettagliata. Con la mappa gia' installata la si estrae di nuovo subito: le tile si
     * riusano da quella installata, quindi verso la leggera non si scarica niente e verso la dettagliata solo la z14.
     */
    fun setMapLight(regionId: String, light: Boolean) {
        mapDetailPreferences.setChoice(regionId, light)
        viewModelScope.launch {
            if (regionRepository.installed(regionId)?.versionOf(PackageKind.MAP) != null) downloadPackage(regionId, PackageKind.MAP)
        }
    }

    // La voce del manifest limitata alla zona scelta: anche il worker la limita, ma dimensioni, spazio libero e versioni
    // da confrontare devono essere gia' quelle della zona.
    private fun zonedEntry(regionId: String, zone: RegionZone? = regionZonePreferences.zone(regionId)): RegionManifestEntry? =
        manifestRegions.value.firstOrNull { it.regionId == regionId }?.restrictedTo(zone)

    /**
     * Sceglie la zona della regione (null = tutta). Con la regione installata mappa, percorsi e civici si rifanno subito:
     * la mappa riusa le tile gia' installate, i percorsi i segmenti gia' scaricati, i civici le celle invariate; i civici
     * senza celle nella zona si tolgono. Con [download] e la regione non installata parte il download di tutta la regione.
     */
    fun setZone(regionId: String, zone: RegionZone?, download: Boolean = false) {
        // Solo la parte dentro la regione (la zona inquadrata puo' prendere anche paesi vicini, vedi regionsInZone).
        val source = manifestRegions.value.firstOrNull { it.regionId == regionId }?.map?.source ?: return
        if (zone != null && (zone.maxLon <= source.minLon || zone.minLon >= source.maxLon || zone.maxLat <= source.minLat || zone.minLat >= source.maxLat)) return
        val clamped = zone?.let {
            RegionZone(maxOf(it.minLon, source.minLon), maxOf(it.minLat, source.minLat), minOf(it.maxLon, source.maxLon), minOf(it.maxLat, source.maxLat))
        }
        regionZonePreferences.setZone(regionId, clamped)
        val entry = zonedEntry(regionId, clamped) ?: return
        viewModelScope.launch {
            val local = regionRepository.installed(regionId)
            if (local == null) {
                if (download) enqueue(entry, entry.downloadKinds(usageModePreferences.wantsDirections.value))
                return@launch
            }
            val installedKinds = setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.ADDRESSES).filter { local.versionOf(it) != null }
            if (PackageKind.ADDRESSES in installedKinds && PackageKind.ADDRESSES !in entry.availableKinds) {
                regionRepository.removePackage(regionId, PackageKind.ADDRESSES)
            }
            enqueue(entry, installedKinds.filterTo(mutableSetOf()) { it in entry.availableKinds })
        }
    }

    /** Le regioni del catalogo dentro [zone], dalla piu' presente (terra, non mare): id e nome nella lingua dell'interfaccia. */
    suspend fun regionsInZone(zone: RegionZone): List<Pair<String, String>> = withContext(Dispatchers.Default) {
        val regions = manifestRegions.value
        val candidates = regions.mapNotNull { region ->
            region.countryCode?.let { code -> region.map.source.let { ZoneCandidate(region.regionId, code, RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat)) } }
        }
        zoneShares(zone, candidates, countryLocator::countryAt).map { (id, _) ->
            id to regions.first { it.regionId == id }.localizedNames(locale.value).displayName
        }
    }

    fun observeDownloadProgress(regionId: String): Flow<WorkInfo?> =
        regionSyncScheduler.observeDownload(regionId)
}

/**
 * Una nazione installata quando il catalogo non c'e': solo i pacchetti sul telefono, tutti "installati" (senza
 * catalogo non si sa se ci sono aggiornamenti) e senza nulla da scaricare.
 */
internal fun offlineRegionItem(local: RegionPackage, installedBytes: (RegionPackage, PackageKind) -> Long?): RegionUiItem =
    RegionUiItem(
        regionId = local.regionId,
        displayName = local.displayName,
        sizeBytes = local.sizeBytes,
        status = RegionStatus.INSTALLED,
        countryCode = local.countryCode,
        packages = PackageKind.entries.filter { local.versionOf(it) != null }.map { kind ->
            PackageUiState(kind = kind, status = RegionStatus.INSTALLED, downloadBytes = 0, installedBytes = installedBytes(local, kind))
        },
    )

/** Regioni installate che il manifest non offre piu' perche' divise in regioni piu' piccole. */
internal fun replacedItems(
    installed: List<RegionPackage>,
    remoteRegions: List<RegionManifestEntry>,
    replacedRegions: List<ReplacedRegion>,
): List<ReplacedRegionItem> {
    val remoteIds = remoteRegions.mapTo(mutableSetOf()) { it.regionId }
    val groups = replacedRegions.associate { it.regionId to it.groupName }
    return installed
        .filter { it.regionId !in remoteIds && it.regionId in groups }
        .map { ReplacedRegionItem(it.regionId, it.displayName, it.countryCode, groups.getValue(it.regionId), it.sizeBytes) }
}

/** Pacchetti installati la cui versione nel manifest e' cambiata (tra quelli che il manifest offre ancora). */
internal fun outdatedKinds(remote: RegionManifestEntry, local: RegionPackage?): Set<PackageKind> =
    remote.availableKinds.filterTo(mutableSetOf()) { kind -> local?.versionOf(kind)?.let { it != remote.versionOf(kind) } == true }

internal fun regionUiItem(
    remote: RegionManifestEntry,
    local: RegionPackage?,
    installedBytes: (RegionPackage, PackageKind) -> Long?,
    // Civici a griglia: celle gia' installate (id -> version), per
    // contare solo quelle nuove o cambiate nella dimensione da scaricare. Non serve per le regioni
    // senza griglia: il default basta a tutti i test.
    installedAddressCells: (regionId: String) -> Map<String, String> = { emptyMap() },
    // "Indicazioni" attivo: "Scarica" comprende i percorsi (RegionManifestEntry.downloadKinds).
    withRouting: Boolean = false,
    // Indice dei mezzi pubblici letto: una regione senza reti le mostra come "non disponibili" (senza indice non si sa).
    transitKnown: Boolean = false,
): RegionUiItem {
    val addressCellVersions = if (remote.addressGrid != null && local != null) installedAddressCells(remote.regionId) else emptyMap()
    val outdated = outdatedKinds(remote, local)
    val status = when {
        local == null -> RegionStatus.NOT_INSTALLED
        outdated.isNotEmpty() -> RegionStatus.UPDATE_AVAILABLE
        else -> RegionStatus.INSTALLED
    }
    // POI extra e civici possono mancare dal manifest: la riga c'e' se il manifest li offre o se sono
    // installati. Solo i civici mancanti si segnalano come "non disponibili": i POI extra arrivano man
    // mano che la pipeline rigenera i POI delle regioni.
    val (shown, missing) = PackageKind.entries.partition { it in remote.availableKinds || local?.versionOf(it) != null }
    val unavailable = missing.filter { it == PackageKind.ADDRESSES || (it == PackageKind.TRANSIT && transitKnown) }
    val packages = shown.map { kind ->
        PackageUiState(
            kind = kind,
            status = when {
                local?.versionOf(kind) == null -> RegionStatus.NOT_INSTALLED
                kind in outdated -> RegionStatus.UPDATE_AVAILABLE
                else -> RegionStatus.INSTALLED
            },
            downloadBytes = remote.downloadBytes(setOf(kind), addressCellVersions),
            installedBytes = local?.let { installedBytes(it, kind) },
            detail = if (kind == PackageKind.TRANSIT && remote.transit?.available.orEmpty().size <= 1) remote.transit?.feeds?.joinToString { it.name } else null,
            networks = if (kind == PackageKind.TRANSIT) remote.transitNetworks() else emptyList(),
            transitDefaultReason = if (kind == PackageKind.TRANSIT) remote.transit?.defaultReason else null,
        )
    }
    val toDownload = when (status) {
        RegionStatus.NOT_INSTALLED -> remote.downloadKinds(withRouting)
        RegionStatus.UPDATE_AVAILABLE -> outdated
        RegionStatus.INSTALLED -> emptySet()
    }
    val sizeBytes = if (status == RegionStatus.INSTALLED) local!!.sizeBytes else remote.downloadBytes(toDownload, addressCellVersions)
    return RegionUiItem(
        remote.regionId, remote.displayName, sizeBytes, status, remote.continent, remote.countryCode, packages, unavailable,
        remote.groupName, remote.groupLabel,
        bbox = remote.map.source.let { RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat) },
        includesMap = PackageKind.MAP in toDownload,
    )
}

/**
 * Il catalogo ha i nomi in italiano: nazioni intere e paesi divisi in piu' regioni prendono il nome
 * dal codice paese nella lingua dell'interfaccia ("be" -> "Belgio"/"Belgium"). Le singole regioni di un
 * paese diviso prendono groupLabelEn in inglese (se il catalogo lo ha), groupLabel altrimenti, col nome
 * del paese davanti.
 */
internal fun RegionManifestEntry.localizedNames(locale: Locale): RegionManifestEntry {
    val code = countryCode ?: return this
    val country = countryName(code, locale).takeIf { !it.equals(code, ignoreCase = true) } ?: return this
    return if (groupName == null) {
        copy(displayName = country)
    } else {
        val label = groupLabelEn?.takeIf { locale.language == "en" } ?: groupLabel
        copy(displayName = label?.let { "$country - $it" } ?: displayName, groupName = country, groupLabel = label)
    }
}

/**
 * Il nome salvato di una regione installata (in italiano, dal catalogo) nella lingua dell'interfaccia,
 * senza manifest: il nome del paese dal codice quando e' il paese intero ("Francia", "Sint Maarten
 * (Paesi Bassi)"), il paese tradotto davanti all'etichetta per le regioni di un paese diviso
 * ("Francia - Bretagna" -> "France - Bretagna"). Altrimenti resta com'e'.
 */
internal fun localizedInstalledName(displayName: String, countryCode: String?, locale: Locale): String {
    if (countryCode == null || locale.language == "it") return displayName
    val italian = countryName(countryCode, Locale.ITALIAN)
    val local = countryName(countryCode, locale).takeIf { !it.equals(countryCode, ignoreCase = true) } ?: return displayName
    return when {
        displayName == italian || displayName.startsWith("$italian (") -> local
        displayName.startsWith("$italian - ") -> local + displayName.removePrefix(italian)
        else -> displayName
    }
}

// Ricerca senza distinzione di maiuscole e accenti ("cina" trova "Cina", "sao" trova "São Tomé").
internal fun matchesQuery(displayName: String, query: String): Boolean {
    if (query.isBlank()) return true
    return displayName.foldForSearch().contains(query.trim().foldForSearch())
}

private fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

// Le reti da scegliere una per una: solo se la regione ne ha piu' di una.
private fun RegionManifestEntry.transitNetworks(): List<TransitNetworkUi> {
    val transit = transit ?: return emptyList()
    if (transit.available.size <= 1) return emptyList()
    val chosen = transit.feeds.mapTo(mutableSetOf()) { it.id }
    return transit.available.map { TransitNetworkUi(it.id, it.name, it.downloadFile.sizeBytes, it.id in chosen) }
}
