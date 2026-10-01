package com.pockettravel.app.regions

import com.pockettravel.core.data.LastKnownPosition
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.core.sync.regionTransitFeeds
import com.pockettravel.feature.ai.AiAvailability
import com.pockettravel.feature.map.TransitPackageState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
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
    private val lastKnownPosition: LastKnownPosition,
    private val countryLocator: CountryLocator,
    private val aiAvailability: AiAvailability,
) : ViewModel() {

    // La tab IA c'e' solo con un modello scaricato o una chiave API (si configurano nelle Impostazioni).
    val aiAvailable: StateFlow<Boolean> = aiAvailability.available

    // Dopo un cambio di lingua: i modelli addestrati valgono solo per la lingua delle guide.
    fun refreshAiAvailability() = aiAvailability.refresh()

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

    /** Avanzamento 0..1 del download in corso della regione (per esempio i Percorsi dal Navigatore), null se nessuno. */
    val downloadProgress: StateFlow<Float?> = regionId.filterNotNull().flatMapLatest { id ->
        regionSyncScheduler.observeDownload(id).map { work ->
            if (work?.state != WorkInfo.State.RUNNING && work?.state != WorkInfo.State.ENQUEUED) return@map null
            val total = work.progress.getLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 0L)
            if (total > 0) work.progress.getLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 0L) / total.toFloat() else 0f
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Il catalogo non si e' potuto leggere nell'ultimo tentativo di scaricare un pacchetto (offline).
    private val manifestFailed = MutableStateFlow(false)

    /** L'ultimo download della regione e' finito in errore (lavoro fallito o catalogo non raggiungibile): si puo' riprovare. */
    val downloadFailed: StateFlow<Boolean> = regionId.filterNotNull().flatMapLatest { id ->
        combine(regionSyncScheduler.observeDownload(id), manifestFailed) { work, noManifest ->
            noManifest || work?.state == WorkInfo.State.FAILED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // La regione ha reti dei mezzi pubblici nel catalogo (letto solo se gli orari non sono ancora installati);
    // null finche' non si sa (offline, catalogo non letto).
    private val transitOffered = MutableStateFlow<Boolean?>(null)

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
                offered == true -> TransitPackageState.AVAILABLE
                offered == false -> TransitPackageState.NOT_OFFERED
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
            // Senza transit.json nel catalogo non si sa ancora: niente "non ci sono orari".
            transitOffered.value = if (installed) null else runCatching {
                val manifest = manifestClient.fetchManifest()
                manifest.transit?.let { regionTransitFeeds(transitClient.fetchIndex(it), regionId).isNotEmpty() }
            }.getOrNull()
        }
    }

    /** Scarica o aggiorna gli orari dei mezzi pubblici della regione (scheda delle fermate). */
    fun downloadTransit() {
        val id = regionId.value ?: return
        viewModelScope.launch {
            runCatching { entryWithTransit(id) }
                .onSuccess { entry ->
                    if (entry.transit != null) {
                        transitNetworkPreferences.rememberChoice(entry, setOf(PackageKind.TRANSIT))
                        regionSyncScheduler.enqueueDownload(entry, setOf(PackageKind.TRANSIT))
                    }
                }
        }
    }

    // La voce del manifest della regione con le sue reti (transit.json), come nell'elenco delle regioni.
    private suspend fun entryWithTransit(id: String): RegionManifestEntry {
        val manifest = manifestClient.fetchManifest()
        val index = manifest.transit?.let { transitClient.fetchIndex(it) }
        val regions = manifest.regions.filter { it.regionId == id }
        val place = withContext(Dispatchers.Default) {
            lastKnownPosition.get()?.let { (lat, lon) -> DevicePlace(lat, lon, countryLocator.countryAt(lat, lon)) }
        }
        val choices = effectiveTransitChoices(regions, index, transitNetworkPreferences.excluded.value, place)
        return attachTransitFeeds(regions, index, choices.excluded, choices.reasons).first()
    }

    fun downloadMap() = downloadPackage(PackageKind.MAP)

    /** "Scarica i percorsi" dal Navigatore, quando la regione non li ha. */
    fun downloadRouting() = downloadPackage(PackageKind.ROUTING)

    // Un errore del catalogo non si butta: il Navigatore lo mostra e lascia riprovare, invece di restare fermo.
    private fun downloadPackage(kind: PackageKind) {
        val id = regionId.value ?: return
        viewModelScope.launch {
            manifestFailed.value = false
            runCatching { manifestClient.fetchManifest().regions.first { it.regionId == id } }
                .onSuccess { regionSyncScheduler.enqueueDownload(it, setOf(kind)) }
                .onFailure { manifestFailed.value = true }
        }
    }

    private fun mapState(hasMap: Boolean, work: WorkInfo?): RegionMapState = when {
        hasMap -> RegionMapState.INSTALLED
        work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> RegionMapState.DOWNLOADING
        else -> RegionMapState.MISSING
    }
}
