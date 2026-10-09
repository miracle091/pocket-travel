package com.pockettravel.app.regions

import com.pockettravel.core.data.LastKnownPosition
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.core.sync.regionTransitFeeds
import com.pockettravel.feature.ai.AiAvailability
import com.pockettravel.feature.map.TransitPackageState
import com.pockettravel.feature.map.TransitUpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Stato della mappa della regione aperta: i pacchetti si installano separatamente, puo' mancare. */
// LOADING: stato non ancora noto, per non mostrare la mappa (o il suo stato vuoto) prima del primo valore.
enum class RegionMapState { LOADING, INSTALLED, MISSING, DOWNLOADING }

// Tra l'altro fornisce a RegionHubScreen il nome reale della regione per la TopAppBar, invece del
// regionId grezzo (es. "italia").
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

    // La scheda IA c'e' solo con un modello scaricato o una chiave API (si configurano nelle Impostazioni).
    val aiAvailable: StateFlow<Boolean> = aiAvailability.available

    // Dopo un cambio di lingua: i modelli addestrati valgono solo per la lingua delle guide.
    fun refreshAiAvailability() = aiAvailability.refresh()

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    // Regione di una nazione divisa ("Francia - Bretagna"): l'assistente dice "regione", altrimenti "nazione".
    private val _splitCountry = MutableStateFlow(false)
    val splitCountry: StateFlow<Boolean> = _splitCountry.asStateFlow()

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

    // La regione ha reti dei mezzi pubblici nel catalogo (letto solo se gli orari non sono ancora installati);
    // null finche' non si sa (offline, catalogo non letto).
    private val transitOffered = MutableStateFlow<Boolean?>(null)

    // L'ultimo tocco su "Aggiorna" o "Scarica" nel riquadro delle fermate della regione aperta, null se nessuno.
    private val transitUpdate = MutableStateFlow<TransitUpdate?>(null)

    // Lo stato del download accodato da quel tocco: null se nessuno (o non ancora registrato da WorkManager).
    private val transitUpdateWork: Flow<WorkInfo.State?> = transitUpdate.flatMapLatest { update ->
        if (update is TransitUpdate.Requested) update.work.map { it?.state } else flowOf(null)
    }

    /** Esito di "Aggiorna" quando non scarica nulla: nessun orario piu' recente nel catalogo, o errore. */
    val transitUpdateResult: StateFlow<TransitUpdateResult?> = combine(transitUpdate, transitUpdateWork, ::transitUpdateResultOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Orari dei mezzi pubblici per il riquadro delle fermate: installati, in scaricamento, scaricabili o non offerti.
    // Durante un aggiornamento chiesto dal riquadro risultano in scaricamento, anche se gia' installati.
    val transitState: StateFlow<TransitPackageState> = regionId.filterNotNull().flatMapLatest { id ->
        combine(
            regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == id }?.transitVersion != null },
            regionSyncScheduler.observeDownload(id),
            transitOffered,
            transitUpdate,
            transitUpdateWork,
        ) { installed, work, offered, update, updateWork ->
            when {
                isTransitUpdating(update, updateWork) -> TransitPackageState.DOWNLOADING
                installed -> TransitPackageState.INSTALLED
                work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> TransitPackageState.DOWNLOADING
                offered == true -> TransitPackageState.AVAILABLE
                offered == false -> TransitPackageState.NOT_OFFERED
                else -> TransitPackageState.UNKNOWN
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransitPackageState.UNKNOWN)

    fun load(regionId: String) {
        // Sui tablet lo stesso ViewModel serve tutte le regioni scelte nel pannello: rientrando nella stessa non si rifa'
        // nulla (niente manifest e transit.json riscaricati); cambiando, si riparte da uno stato vuoto.
        // (Se era risultata mancante si ricontrolla: nel frattempo puo' essere stata scaricata di nuovo.)
        if (this.regionId.value == regionId && !_regionMissing.value) return
        this.regionId.value = regionId
        _displayName.value = null
        _splitCountry.value = false
        _regionMissing.value = false
        transitOffered.value = null
        transitUpdate.value = null
        recentRegionPreferences.setLastRegionId(regionId)
        viewModelScope.launch {
            val region = regionRepository.installed(regionId)
            val name = region?.let { localizedInstalledName(it.displayName, it.countryCode, Locale.getDefault()) }
            // Nel frattempo il pannello puo' essere passato a un'altra regione.
            if (this@RegionHubViewModel.regionId.value != regionId) return@launch
            _displayName.value = name
            _splitCountry.value = region?.let { isSplitCountryName(it.displayName, it.countryCode) } == true
            _regionMissing.value = name == null
        }
        viewModelScope.launch {
            val installed = regionRepository.installed(regionId)?.transitVersion != null
            // Senza transit.json nel catalogo non si sa ancora: niente "non ci sono orari".
            val offered = if (installed) null else runCatchingCancellable {
                val manifest = manifestClient.recentManifest()
                manifest.transit?.let { regionTransitFeeds(transitClient.fetchIndex(it), regionId).isNotEmpty() }
            }.getOrNull()
            if (this@RegionHubViewModel.regionId.value == regionId) transitOffered.value = offered
        }
    }

    /**
     * Scarica o aggiorna gli orari dei mezzi pubblici della regione (riquadro delle fermate). Se il catalogo ha la
     * stessa versione di quelli installati non scarica nulla e lo dice; un catalogo non leggibile e' un errore.
     */
    fun downloadTransit() {
        val id = regionId.value ?: return
        transitUpdate.value = TransitUpdate.Checking
        viewModelScope.launch {
            val update = runCatchingCancellable {
                val entry = entryWithTransit(id)
                val latest = entry.versionOf(PackageKind.TRANSIT)
                if (latest == null || latest == regionRepository.installed(id)?.transitVersion) {
                    TransitUpdate.UpToDate
                } else {
                    transitNetworkPreferences.rememberChoice(entry, setOf(PackageKind.TRANSIT))
                    // null: l'app va aggiornata per leggere questi dati, e enqueueDownload lo ha gia' detto.
                    regionSyncScheduler.enqueueDownload(entry, setOf(PackageKind.TRANSIT))?.let { TransitUpdate.Requested(it) }
                }
            }.getOrElse { TransitUpdate.Failed }
            // Nel frattempo il pannello puo' essere passato a un'altra regione.
            if (regionId.value == id) transitUpdate.value = update
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

    private fun downloadPackage(kind: PackageKind) {
        val id = regionId.value ?: return
        viewModelScope.launch {
            runCatchingCancellable { manifestClient.fetchManifest().regions.first { it.regionId == id } }
                .onSuccess { regionSyncScheduler.enqueueDownload(it, setOf(kind)) }
        }
    }

    private fun mapState(hasMap: Boolean, work: WorkInfo?): RegionMapState = when {
        hasMap -> RegionMapState.INSTALLED
        work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> RegionMapState.DOWNLOADING
        else -> RegionMapState.MISSING
    }
}

/** Un tocco su "Aggiorna" o "Scarica" nel riquadro delle fermate. */
internal sealed interface TransitUpdate {
    /** Si legge il catalogo. */
    data object Checking : TransitUpdate

    /** Download accodato: [work] e' lo stato del suo lavoro in WorkManager. */
    class Requested(val work: Flow<WorkInfo?>) : TransitUpdate

    /** Il catalogo ha la stessa versione degli orari installati (o nessuna rete per la regione). */
    data object UpToDate : TransitUpdate

    /** Catalogo non leggibile (offline, server non raggiungibile). */
    data object Failed : TransitUpdate
}

// In scaricamento finche' si legge il catalogo e finche' il download accodato non finisce (null: non ancora registrato).
internal fun isTransitUpdating(update: TransitUpdate?, work: WorkInfo.State?): Boolean = when (update) {
    TransitUpdate.Checking -> true
    is TransitUpdate.Requested -> work?.isFinished != true
    TransitUpdate.UpToDate, TransitUpdate.Failed, null -> false
}

// Riuscito o annullato, il download non lascia messaggi: gli orari nuovi si vedono nel tabellone.
internal fun transitUpdateResultOf(update: TransitUpdate?, work: WorkInfo.State?): TransitUpdateResult? = when (update) {
    TransitUpdate.UpToDate -> TransitUpdateResult.UP_TO_DATE
    TransitUpdate.Failed -> TransitUpdateResult.FAILED
    is TransitUpdate.Requested -> TransitUpdateResult.FAILED.takeIf { work == WorkInfo.State.FAILED }
    TransitUpdate.Checking, null -> null
}
