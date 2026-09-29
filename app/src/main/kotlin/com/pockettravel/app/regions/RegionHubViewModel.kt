package com.pockettravel.app.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.feature.map.TransitPackageState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Stato della mappa della regione aperta: i pacchetti si installano separatamente, puo' mancare. */
// LOADING: stato non ancora noto, per non mostrare la mappa (o il suo stato vuoto) prima del primo valore.
enum class RegionMapState { LOADING, INSTALLED, MISSING, DOWNLOADING }

// Prima di questo, RegionHubScreen mostrava il regionId grezzo (es. "italia") nella TopAppBar
// invece del nome regione reale.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RegionHubViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val recentRegionPreferences: RecentRegionPreferences,
    private val manifestClient: ManifestClient,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val transitClient: TransitClient,
    private val transitNetworkPreferences: TransitNetworkPreferences,
) : ViewModel() {

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    // true solo dopo che il caricamento ha escluso la regione dal database (mai installata, o
    // installata in una sessione precedente e poi eliminata) — RegionHubScreen ci naviga via in
    // automatico invece di mostrare guida/mappa vuote per un regionId ormai inesistente. Serve
    // soprattutto quando questa schermata e' la rotta di avvio dell'app (vedi StartDestinationViewModel,
    // basata sull'ultima regione aperta secondo RecentRegionPreferences, non su cio' che e' ancora
    // davvero installato).
    private val _regionMissing = MutableStateFlow(false)
    val regionMissing: StateFlow<Boolean> = _regionMissing.asStateFlow()

    private val regionId = MutableStateFlow<String?>(null)

    val mapState: StateFlow<RegionMapState> = regionId.filterNotNull().flatMapLatest { id ->
        combine(
            regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == id }?.mapVersion != null },
            regionSyncScheduler.observeDownload(id),
        ) { hasMap, work -> mapState(hasMap, work) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegionMapState.LOADING)

    // La regione ha reti dei mezzi pubblici nel catalogo (letto solo se gli orari non sono ancora installati).
    private val transitOffered = MutableStateFlow(false)

    // Orari dei mezzi pubblici per la scheda delle fermate: installati, in scaricamento, scaricabili o non offerti.
    val transitState: StateFlow<TransitPackageState> = regionId.filterNotNull().flatMapLatest { id ->
        combine(
            regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == id }?.transitVersion != null },
            regionSyncScheduler.observeDownload(id),
            transitOffered,
        ) { installed, work, offered ->
            when {
                installed -> TransitPackageState.INSTALLED
                work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> TransitPackageState.DOWNLOADING
                offered -> TransitPackageState.AVAILABLE
                else -> TransitPackageState.UNKNOWN
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransitPackageState.UNKNOWN)

    fun load(regionId: String) {
        this.regionId.value = regionId
        recentRegionPreferences.setLastRegionId(regionId)
        viewModelScope.launch {
            val region = regionRepository.installed(regionId)
            val name = region?.let { localizedInstalledName(it.displayName, it.countryCode, Locale.getDefault()) }
            _displayName.value = name
            _regionMissing.value = name == null
        }
        viewModelScope.launch {
            val installed = regionRepository.installed(regionId)?.transitVersion != null
            transitOffered.value = !installed && runCatching { entryWithTransit(regionId).transit != null }.getOrDefault(false)
        }
    }

    /** Scarica o aggiorna gli orari dei mezzi pubblici della regione (scheda delle fermate). */
    fun downloadTransit() {
        val id = regionId.value ?: return
        viewModelScope.launch {
            runCatching { entryWithTransit(id) }
                .onSuccess { if (it.transit != null) regionSyncScheduler.enqueueDownload(it, setOf(PackageKind.TRANSIT)) }
        }
    }

    // La voce del manifest della regione con le sue reti (transit.json), come nell'elenco delle regioni.
    private suspend fun entryWithTransit(id: String): RegionManifestEntry {
        val manifest = manifestClient.fetchManifest()
        val index = manifest.transit?.let { transitClient.fetchIndex(it) }
        return attachTransitFeeds(manifest.regions.filter { it.regionId == id }, index, transitNetworkPreferences.excluded.value).first()
    }

    fun downloadMap() {
        val id = regionId.value ?: return
        viewModelScope.launch {
            runCatching { manifestClient.fetchManifest().regions.first { it.regionId == id } }
                .onSuccess { regionSyncScheduler.enqueueDownload(it, setOf(PackageKind.MAP)) }
        }
    }

    private fun mapState(hasMap: Boolean, work: WorkInfo?): RegionMapState = when {
        hasMap -> RegionMapState.INSTALLED
        work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> RegionMapState.DOWNLOADING
        else -> RegionMapState.MISSING
    }
}
