package com.pockettravel.app.regions

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
import com.pockettravel.core.sync.attachAddressGridCells
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
)

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
    // Riquadro geografico della regione (dalla mappa del manifest), per le regioni vicine del primo avvio.
    val bbox: RegionBbox? = null,
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
) : ViewModel() {

    private val manifestRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    private val replacedRegions = MutableStateFlow<List<ReplacedRegion>>(emptyList())
    private val status = MutableStateFlow(LoadStatus())
    private val query = MutableStateFlow("")
    private val locale = MutableStateFlow(Locale.getDefault())

    /** Lingua dell'interfaccia, per i nomi dei paesi (vedi [localizedNames]); la passa la schermata. */
    fun setLocale(newLocale: Locale) {
        locale.value = newLocale
    }

    val uiState = combine(
        combine(manifestRegions, locale) { regions, currentLocale -> regions.map { it.localizedNames(currentLocale) } },
        // Con "Indicazioni" la dimensione di "Scarica" comprende i percorsi.
        combine(regionRepository.observeInstalled(), usageModePreferences.wantsDirections, ::Pair),
        status,
        query,
        replacedRegions,
    ) { remoteRegions, (installed, wantsDirections), currentStatus, currentQuery, replacedByManifest ->
        val installedByRegion = installed.associateBy { it.regionId }
        val items = remoteRegions
            .filter { matchesQuery(it.displayName, currentQuery) }
            .map { remote -> regionUiItem(remote, installedByRegion[remote.regionId], regionRepository::packageBytes, regionRepository::installedAddressCells, wantsDirections) }
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
                manifestRegions.value = attachTransitFeeds(attachAddressGridCells(manifest.regions, addressGridIndex), transitIndex)
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
        val entry = manifestRegions.value.firstOrNull { it.regionId == regionId } ?: return
        viewModelScope.launch {
            val local = regionRepository.installed(regionId)
            enqueue(entry, if (local == null) entry.downloadKinds(usageModePreferences.wantsDirections.value) else outdatedKinds(entry, local))
        }
    }

    /** Scarica i pacchetti scelti di una regione (primo avvio, selezione multipla). */
    fun downloadKinds(regionId: String, kinds: Set<PackageKind>) {
        val entry = manifestRegions.value.firstOrNull { it.regionId == regionId } ?: return
        enqueue(entry, kinds)
    }

    /** Scarica o aggiorna un solo pacchetto della regione (foglio "Pacchetti"). */
    fun downloadPackage(regionId: String, kind: PackageKind) {
        val entry = manifestRegions.value.firstOrNull { it.regionId == regionId } ?: return
        enqueue(entry, setOf(kind))
    }

    private fun enqueue(entry: RegionManifestEntry, kinds: Set<PackageKind>) {
        if (kinds.isEmpty()) return
        if (regionRepository.availableStorageBytes() < entry.downloadBytes(kinds)) {
            status.update { it.copy(message = R.string.regions_not_enough_space) }
            return
        }
        regionSyncScheduler.enqueueDownload(entry, kinds)
    }

    fun onMessageShown() {
        status.update { it.copy(message = null) }
    }

    fun delete(regionId: String) {
        regionSyncScheduler.cancelDownload(regionId)
        viewModelScope.launch { regionRepository.remove(regionId) }
    }

    fun deletePackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch { regionRepository.removePackage(regionId, kind) }
    }

    fun observeDownloadProgress(regionId: String): Flow<WorkInfo?> =
        regionSyncScheduler.observeDownload(regionId)
}

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
    val unavailable = missing.filter { it == PackageKind.ADDRESSES }
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
            detail = if (kind == PackageKind.TRANSIT) remote.transit?.feeds?.joinToString { it.name } else null,
        )
    }
    val sizeBytes = when (status) {
        RegionStatus.NOT_INSTALLED -> remote.downloadBytes(remote.downloadKinds(withRouting), addressCellVersions)
        RegionStatus.UPDATE_AVAILABLE -> remote.downloadBytes(outdated, addressCellVersions)
        RegionStatus.INSTALLED -> local!!.sizeBytes
    }
    return RegionUiItem(
        remote.regionId, remote.displayName, sizeBytes, status, remote.continent, remote.countryCode, packages, unavailable,
        remote.groupName, remote.groupLabel,
        bbox = remote.map.source.let { RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat) },
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

// Ricerca senza distinzione di maiuscole e accenti ("cina" trova "Cina", "sao" trova "São Tomé").
internal fun matchesQuery(displayName: String, query: String): Boolean {
    if (query.isBlank()) return true
    return displayName.foldForSearch().contains(query.trim().foldForSearch())
}

private fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
