package com.pockettravel.app.regions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionPackage
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Normalizer
import javax.inject.Inject
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

enum class RegionStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

/** Stato di un pacchetto (mappa, routing, POI) di una regione, per il foglio "Pacchetti". */
data class PackageUiState(
    val kind: PackageKind,
    val status: RegionStatus,
    // Byte da scaricare (0 per la mappa: estratta sul device, dimensione nota solo dopo).
    val downloadBytes: Long,
    // Byte occupati sul device, null se il pacchetto non e' installato o la dimensione non e' nota.
    val installedBytes: Long?,
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
)

data class RegionListUiState(
    val items: List<RegionUiItem> = emptyList(),
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
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val appUpdateCheckScheduler: AppUpdateCheckScheduler,
    private val llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler,
) : ViewModel() {

    private val manifestRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    private val status = MutableStateFlow(LoadStatus())
    private val query = MutableStateFlow("")

    val uiState = combine(
        manifestRegions,
        regionRepository.observeInstalled(),
        status,
        query,
    ) { remoteRegions, installed, currentStatus, currentQuery ->
        val installedByRegion = installed.associateBy { it.regionId }
        val items = remoteRegions
            .filter { matchesQuery(it.displayName, currentQuery) }
            .map { remote -> regionUiItem(remote, installedByRegion[remote.regionId], regionRepository::packageBytes) }
        RegionListUiState(
            items = items,
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
                manifestRegions.value = manifest.regions
                // Le guide si aggiornano da sole (su Wi-Fi) anche da qui, non solo col controllo periodico.
                if (regionRepository.installedGuidesVersion() != manifest.guides.version) regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = true)
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
            enqueue(entry, if (local == null) entry.availableKinds else outdatedKinds(entry, local))
        }
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
        viewModelScope.launch { regionRepository.remove(regionId) }
    }

    fun deletePackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch { regionRepository.removePackage(regionId, kind) }
    }

    fun observeDownloadProgress(regionId: String): Flow<WorkInfo?> =
        regionSyncScheduler.observeDownload(regionId)
}

/** Pacchetti installati la cui versione nel manifest e' cambiata (tra quelli che il manifest offre ancora). */
internal fun outdatedKinds(remote: RegionManifestEntry, local: RegionPackage?): Set<PackageKind> =
    remote.availableKinds.filterTo(mutableSetOf()) { kind -> local?.versionOf(kind)?.let { it != remote.versionOf(kind) } == true }

internal fun regionUiItem(
    remote: RegionManifestEntry,
    local: RegionPackage?,
    installedBytes: (RegionPackage, PackageKind) -> Long?,
): RegionUiItem {
    val outdated = outdatedKinds(remote, local)
    val status = when {
        local == null -> RegionStatus.NOT_INSTALLED
        outdated.isNotEmpty() -> RegionStatus.UPDATE_AVAILABLE
        else -> RegionStatus.INSTALLED
    }
    // I civici possono mancare dal manifest: la riga c'e' se il manifest li offre o se sono installati.
    val (shown, unavailable) = PackageKind.entries.partition { it in remote.availableKinds || local?.versionOf(it) != null }
    val packages = shown.map { kind ->
        PackageUiState(
            kind = kind,
            status = when {
                local?.versionOf(kind) == null -> RegionStatus.NOT_INSTALLED
                kind in outdated -> RegionStatus.UPDATE_AVAILABLE
                else -> RegionStatus.INSTALLED
            },
            downloadBytes = remote.downloadBytes(setOf(kind)),
            installedBytes = local?.let { installedBytes(it, kind) },
        )
    }
    val sizeBytes = when (status) {
        RegionStatus.NOT_INSTALLED -> remote.downloadBytes(remote.availableKinds)
        RegionStatus.UPDATE_AVAILABLE -> remote.downloadBytes(outdated)
        RegionStatus.INSTALLED -> local!!.sizeBytes
    }
    return RegionUiItem(remote.regionId, remote.displayName, sizeBytes, status, remote.continent, remote.countryCode, packages, unavailable)
}

// Ricerca senza distinzione di maiuscole e accenti ("cina" trova "Cina", "sao" trova "São Tomé").
internal fun matchesQuery(displayName: String, query: String): Boolean {
    if (query.isBlank()) return true
    return displayName.foldForSearch().contains(query.trim().foldForSearch())
}

private fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
