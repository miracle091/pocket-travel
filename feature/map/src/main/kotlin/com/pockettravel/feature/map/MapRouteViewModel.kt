package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.hasName
import com.pockettravel.core.data.isHiddenOnMap
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.poi.PoiCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Holder minimo per far arrivare OfflineTileSource (fornito da RouteEngineModule), i segnalini e i
// filtri salvati a MapScreen tramite hiltViewModel(), come ogni altra dipendenza di questo progetto
// raggiunge una composable — MapScreen stessa resta stateless.
@HiltViewModel
class MapRouteViewModel @Inject constructor(
    val tileSource: OfflineTileSource,
    private val poiRepository: PoiRepository,
    private val regionZonePreferences: RegionZonePreferences,
    regionRepository: RegionRepository,
    private val filterPreferences: MapFilterPreferences,
    private val transitRepository: TransitRepository,
    usageModePreferences: UsageModePreferences,
    connectivityObserver: ConnectivityObserver,
) : ViewModel() {
    val accessible: StateFlow<Boolean> = usageModePreferences.accessible
    val hiddenCategories: StateFlow<Set<PoiCategory>> = filterPreferences.hiddenCategories

    fun setHiddenCategories(categories: Set<PoiCategory>) {
        filterPreferences.setHidden(categories)
        // I segnalini a campione si scelgono senza le categorie nascoste: cambiate quelle, si rileggono.
        reloadPins()
    }
    val onlyAccessible: StateFlow<Boolean> = filterPreferences.onlyAccessible
    fun setOnlyAccessible(only: Boolean) = filterPreferences.setOnlyAccessible(only)

    private val _pins = MutableStateFlow<List<MapPin>>(emptyList())
    val pins: StateFlow<List<MapPin>> = _pins.asStateFlow()

    // Categorie presenti nell'area, comprese quelle filtrate: il foglio dei filtri deve poterle riattivare.
    private val _presentCategories = MutableStateFlow<Set<PoiCategory>>(emptySet())
    val presentCategories: StateFlow<Set<PoiCategory>> = _presentCategories.asStateFlow()

    private var loadPinsJob: Job? = null

    private val regionIdFlow = MutableStateFlow<String?>(null)

    // Partenze del POI di trasporto aperto nella scheda: null finche' si leggono o senza POI.
    private val _transitBoard = MutableStateFlow<TransitBoard?>(null)
    val transitBoard: StateFlow<TransitBoard?> = _transitBoard.asStateFlow()
    private var transitJob: Job? = null

    /** Legge le prossime partenze vicino a [pin]; null (scheda chiusa o orari non installati) le azzera. */
    fun showDepartures(pin: MapPin?) {
        transitJob?.cancel()
        _transitBoard.value = null
        val regionId = regionIdFlow.value
        if (pin == null || regionId == null) return
        transitJob = viewModelScope.launch { _transitBoard.value = transitRepository.board(regionId, pin.latitude, pin.longitude) }
    }

    // Versioni installate di mappa, anteprima e civici della regione corrente, dal database: cambiano
    // quando un pacchetto finisce di installarsi, anche in background con la scheda Mappa aperta.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val installedVersions = regionIdFlow.filterNotNull().flatMapLatest { regionId ->
        regionRepository.observeInstalled().map { installed ->
            installed.firstOrNull { it.regionId == regionId }?.let { listOf(it.mapVersion, it.previewVersion, it.addressesVersion) }
        }
    }.distinctUntilChanged()

    // Sorgente in uso per la regione corrente: il tipo serve a RegionHubScreen per la barra "Scarica
    // la mappa"; l'intero stato (tipo + versioni) e' la chiave con cui MapScreen ricostruisce lo stile,
    // cosi' anche un'anteprima o una mappa aggiornata (stesso tipo, file nuovo) viene ridisegnata.
    val mapSource: StateFlow<MapSourceState> = combine(
        regionIdFlow.filterNotNull(), connectivityObserver.changes(), installedVersions,
    ) { regionId, _, versions -> MapSourceState(tileSource.sourceKind(regionId), versions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapSourceState(MapSourceKind.NONE, null))

    // Area inquadrata dalla mappa (MapScreen la segnala a ogni fermo); null prima del primo fermo.
    private var viewport: MapBounds? = null

    fun loadPins(regionId: String) {
        regionIdFlow.value = regionId
        viewport = null
        reloadPins()
    }

    fun setViewport(bounds: MapBounds) {
        viewport = bounds
        reloadPins()
    }

    /**
     * Solo i POI dell'area inquadrata, dentro la zona scaricata se c'e', al massimo [MAX_PINS]: tutti quelli di un paese
     * grande (Italia, ~70 MB) manderebbero l'app in OutOfMemoryError. Prima del primo fermo della mappa, quelli del
     * riquadro della mappa installata (o della zona).
     */
    private fun reloadPins() {
        val regionId = regionIdFlow.value ?: return
        // Annulla il caricamento precedente: una regione o un'area lenta non deve sovrascrivere i segnalini.
        loadPinsJob?.cancel()
        loadPinsJob = viewModelScope.launch {
            val zone = regionZonePreferences.zone(regionId)
            val area = viewport ?: zone?.let { MapBounds(it.minLon, it.minLat, it.maxLon, it.maxLat) } ?: tileSource.regionBounds(regionId) ?: WORLD
            val minLon = maxOf(area.minLon, zone?.minLon ?: -180.0)
            val minLat = maxOf(area.minLat, zone?.minLat ?: -90.0)
            val maxLon = minOf(area.maxLon, zone?.maxLon ?: 180.0)
            val maxLat = minOf(area.maxLat, zone?.maxLat ?: 90.0)
            if (minLon > maxLon || minLat > maxLat) {
                _pins.value = emptyList()
                return@launch
            }
            // I POI extra li ha scaricati l'utente apposta: si mostrano anche se di solito nascosti.
            val inArea = poiRepository.inBounds(regionId, minLat, maxLat, minLon, maxLon, MAX_PINS, filterPreferences.hiddenCategories.value)
            _presentCategories.value = inArea.categories
            _pins.value = inArea.pois.filter { it.extra || !it.isHiddenOnMap() }.map { poi ->
                MapPin(
                    poi.id.toString(), poi.name.takeIf { poi.hasName() }, poi.latitude, poi.longitude, poi.poiCategory(), poi.osmTag, poi.phone, poi.wheelchair,
                    openingHours = poi.openingHours, address = poi.address, website = poi.website, email = poi.email,
                    nameEn = poi.nameEn, nameIt = poi.nameIt,
                    toiletsWheelchair = poi.toiletsWheelchair, capacityDisabled = poi.capacityDisabled,
                )
            }
        }
    }
}

// Segnalini al massimo sulla mappa, sparsi sull'area se sono di piu': oltre si sovrappongono comunque (MapLibre nasconde
// quelli che si coprono).
private const val MAX_PINS = 3_000
private val WORLD = MapBounds(-180.0, -90.0, 180.0, 90.0)

/** Sorgente della mappa in uso e versioni dei pacchetti locali da cui dipende lo stile. */
data class MapSourceState(val kind: MapSourceKind, val installedVersions: List<String?>?)
