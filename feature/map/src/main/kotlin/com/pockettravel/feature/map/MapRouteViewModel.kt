package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.AreaPois
import com.pockettravel.core.data.MapAccessibility
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.hasName
import com.pockettravel.core.data.isHiddenOnMap
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.poi.PoiCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    fun setOnlyAccessible(only: Boolean) {
        filterPreferences.setOnlyAccessible(only)
        reloadPins()
    }

    init {
        // "In sedia a rotelle" si cambia nelle Impostazioni: anche quello sceglie i segnalini a campione.
        viewModelScope.launch { accessible.drop(1).collect { reloadPins() } }
    }

    private val _pins = MutableStateFlow<List<MapPin>>(emptyList())
    val pins: StateFlow<List<MapPin>> = _pins.asStateFlow()

    // Categorie presenti nell'area, comprese quelle filtrate: il foglio dei filtri deve poterle riattivare.
    private val _presentCategories = MutableStateFlow<Set<PoiCategory>>(emptySet())
    val presentCategories: StateFlow<Set<PoiCategory>> = _presentCategories.asStateFlow()

    private val regionIdFlow = MutableStateFlow<String?>(null)

    // Richieste di ricaricare i segnalini (regione, area, filtri). Un solo raccoglitore, conflato: un caricamento
    // iniziato finisce sempre e poi si legge lo stato piu' recente. Annullarlo a ogni fermo della mappa, con una
    // nazione grande (query di secondi sul telefono), lasciava la mappa senza segnalini.
    private val pinRequests = MutableStateFlow(0L)

    // Le coppie categoria/tag della regione aperta, lette una volta (PoiRepository.categoryTags).
    private var regionTags: Pair<String, List<CategoryTag>>? = null

    init {
        viewModelScope.launch { pinRequests.collect { loadPinsNow() } }
    }

    // Partenze del POI di trasporto aperto nel riquadro: null finche' si leggono o senza POI.
    private val _transitBoard = MutableStateFlow<TransitBoard?>(null)
    val transitBoard: StateFlow<TransitBoard?> = _transitBoard.asStateFlow()
    private var transitJob: Job? = null

    /**
     * Legge le prossime partenze vicino a [pin] e le rilegge ogni minuto finche' il riquadro e' aperta (le partenze
     * passano); null (riquadro chiuso o orari non installati) le azzera.
     */
    fun showDepartures(pin: MapPin?) {
        transitJob?.cancel()
        _transitBoard.value = null
        val regionId = regionIdFlow.value
        if (pin == null || regionId == null) return
        transitJob = viewModelScope.launch {
            while (true) {
                _transitBoard.value = transitRepository.board(regionId, pin.latitude, pin.longitude)
                delay(DEPARTURES_REFRESH_MILLIS)
            }
        }
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
        // Riaperta la regione si rileggono le categorie: un aggiornamento dei POI puo' averle cambiate.
        regionTags = null
        reloadPins()
    }

    fun setViewport(bounds: MapBounds) {
        viewport = bounds
        reloadPins()
    }

    /**
     * Solo i POI dell'area inquadrata, dentro la zona scaricata se c'e', al massimo [MAX_PINS]: tutti quelli di una nazione
     * grande (Italia, ~70 MB) manderebbero l'app in OutOfMemoryError. Prima del primo fermo della mappa, quelli del
     * riquadro della mappa installata (o della zona).
     */
    private fun reloadPins() {
        pinRequests.value += 1
    }

    private suspend fun loadPinsNow() {
        val regionId = regionIdFlow.value ?: return
        val zone = regionZonePreferences.zone(regionId)
        val inView = viewport ?: zone?.let { MapBounds(it.minLon, it.minLat, it.maxLon, it.maxLat) }
            ?: withContext(Dispatchers.IO) { tileSource.regionBounds(regionId) } ?: WORLD
        val area = pinsArea(inView, zone)
        val inArea = area?.let {
            val tags = regionTags?.takeIf { cached -> cached.first == regionId }?.second
                ?: poiRepository.categoryTags(regionId).also { read -> regionTags = regionId to read }
            poiRepository.inBounds(
                regionId, it.minLat, it.maxLat, it.minLon, it.maxLon, MAX_PINS, filterPreferences.hiddenCategories.value, accessibility(), tags,
            )
        }
        // Regione cambiata durante il caricamento: questi segnalini non sono piu' suoi.
        if (regionIdFlow.value == regionId) publishPins(inArea)
    }

    private fun publishPins(inArea: AreaPois?) {
        if (inArea != null) _presentCategories.value = inArea.categories
        // I POI extra li ha scaricati l'utente apposta: si mostrano anche se di solito nascosti.
        _pins.value = inArea?.pois.orEmpty().filter { it.extra || !it.isHiddenOnMap() }.map { poi ->
            MapPin(
                poi.id.toString(), poi.name.takeIf { poi.hasName() }, poi.latitude, poi.longitude, poi.poiCategory(), poi.osmTag, poi.phone, poi.wheelchair,
                openingHours = poi.openingHours, address = poi.address, website = poi.website, email = poi.email,
                nameEn = poi.nameEn, nameIt = poi.nameIt,
                toiletsWheelchair = poi.toiletsWheelchair, capacityDisabled = poi.capacityDisabled,
            )
        }
    }

    // Il filtro "In sedia a rotelle" della mappa, come lo applica MapScreen ("solo accessibili" vale solo con quello acceso).
    private fun accessibility(): MapAccessibility = when {
        !accessible.value -> MapAccessibility.ALL
        onlyAccessible.value -> MapAccessibility.ONLY_ACCESSIBLE
        else -> MapAccessibility.NO_INACCESSIBLE
    }
}

/**
 * L'area dei segnalini: [inView] dentro la zona scaricata, null se non si sovrappongono. Una vista a cavallo dei 180 gradi
 * (Nuova Zelanda, Estremo Oriente russo, Alaska: ovest oltre est o fuori da -180..180) prende tutta la fascia di
 * latitudine invece di non mostrare nessun segnalino.
 */
internal fun pinsArea(inView: MapBounds, zone: RegionZone?): MapBounds? {
    val crossesDateLine = inView.minLon > inView.maxLon || inView.minLon < -180.0 || inView.maxLon > 180.0
    val area = if (crossesDateLine) inView.copy(minLon = -180.0, maxLon = 180.0) else inView
    val clamped = MapBounds(
        minLon = maxOf(area.minLon, zone?.minLon ?: -180.0),
        minLat = maxOf(area.minLat, zone?.minLat ?: -90.0),
        maxLon = minOf(area.maxLon, zone?.maxLon ?: 180.0),
        maxLat = minOf(area.maxLat, zone?.maxLat ?: 90.0),
    )
    return clamped.takeIf { it.minLon <= it.maxLon && it.minLat <= it.maxLat }
}

// Segnalini al massimo sulla mappa, sparsi sull'area se sono di piu': oltre si sovrappongono comunque (MapLibre nasconde
// quelli che si coprono).
private const val MAX_PINS = 3_000

// Ogni quanto si rilegge il tabellone delle partenze con la scheda aperta.
private const val DEPARTURES_REFRESH_MILLIS = 60_000L
private val WORLD = MapBounds(-180.0, -90.0, 180.0, 90.0)

/** Sorgente della mappa in uso e versioni dei pacchetti locali da cui dipende lo stile. */
data class MapSourceState(val kind: MapSourceKind, val installedVersions: List<String?>?)
