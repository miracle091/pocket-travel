package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.displayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sqrt

/** Quale dei due campi della tab si sta scegliendo con la ricerca. */
enum class PlannerField { FROM, TO }

/** Un risultato della ricerca: il posto, il suo tipo (stringa di PoiTypes) e la regione se non e' quella aperta. */
data class PlannerResult(val place: NavigationPlace, val typeLabel: Int?, val otherRegionName: String?, val distanceMeters: Double?)

/** Anteprima del percorso prima di partire. */
sealed interface PlannerPreview {
    /** Manca la destinazione (o la partenza scelta). */
    data object Idle : PlannerPreview

    /** Partenza "La mia posizione": serve il permesso di posizione. */
    data object NeedsPermission : PlannerPreview

    /** Permesso dato ma il GPS e' spento o non trova la posizione. */
    data object NoLocation : PlannerPreview

    data class Calculating(val progress: Double) : PlannerPreview

    data class Ready(val route: Route, val regionIds: List<String>) : PlannerPreview

    data class Unavailable(val result: RouteResult) : PlannerPreview
}

/**
 * Tab Navigazione dell'hub: partenza (la propria posizione o un posto), destinazione cercata per nome
 * tra i punti di interesse delle regioni installate, mezzo e anteprima del percorso. La guida passo
 * passo resta NavigationScreen, aperta da "Avvia" quando si parte dalla propria posizione.
 */
@HiltViewModel
class NavigationPlannerViewModel @Inject constructor(
    private val poiRepository: PoiRepository,
    private val regionRepository: RegionRepository,
    private val routeEngineFactory: RouteEngineFactory,
    private val gps: GpsLocationSource,
    private val recentDestinations: RecentDestinations,
    private val usageModePreferences: UsageModePreferences,
    /** Per la mappa dell'anteprima: lo stesso stile della scheda Mappa. */
    val tileSource: OfflineTileSource,
) : ViewModel() {
    private val regionId = MutableStateFlow<String?>(null)

    /** Regioni installate (id -> nome): la ricerca le copre tutte, la aperta per prima. */
    private val installed: StateFlow<Map<String, String>> = regionRepository.observeInstalled()
        .map { regions -> regions.associate { it.regionId to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _from = MutableStateFlow<NavigationPlace?>(null)
    /** null = la propria posizione. */
    val from: StateFlow<NavigationPlace?> = _from.asStateFlow()
    private val _to = MutableStateFlow<NavigationPlace?>(null)
    val to: StateFlow<NavigationPlace?> = _to.asStateFlow()

    private val _searching = MutableStateFlow<PlannerField?>(null)
    val searching: StateFlow<PlannerField?> = _searching.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _travelMode = MutableStateFlow(TravelMode.from(usageModePreferences.mode.value))
    val travelMode: StateFlow<TravelMode> = _travelMode.asStateFlow()
    val routing: StateFlow<RoutingChoice> = combine(_travelMode, usageModePreferences.accessible, usageModePreferences.allowSteps, ::routingChoice)
        .stateIn(viewModelScope, SharingStarted.Eagerly, routingChoice(_travelMode.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value))
    val allowSteps: StateFlow<Boolean> = usageModePreferences.allowSteps

    private val _preview = MutableStateFlow<PlannerPreview>(PlannerPreview.Idle)
    val preview: StateFlow<PlannerPreview> = _preview.asStateFlow()
    private var previewJob: Job? = null

    /** Posizione nota (dall'ultimo calcolo da "La mia posizione"): ordina i risultati per distanza. */
    private val lastPosition = MutableStateFlow<RoutePoint?>(null)

    /** Recenti delle regioni installate. */
    val recents: StateFlow<List<NavigationPlace>> = combine(recentDestinations.places, installed) { places, regions ->
        places.filter { it.regionId in regions }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<PlannerResult>> = combine(_query.debounce(SEARCH_DEBOUNCE_MILLIS), regionId, installed, ::Triple)
        .mapLatest { (text, current, regions) -> search(text, current, regions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun load(regionId: String) {
        this.regionId.value = regionId
    }

    /** "Indicazioni" dalla scheda di un punto della mappa: destinazione pronta, partenza dalla propria posizione. */
    fun setDestination(place: NavigationPlace) {
        _to.value = place
        _from.value = null
        _searching.value = null
        recentDestinations.add(place)
        refreshPreview()
    }

    fun startSearch(field: PlannerField) {
        _query.value = ""
        _searching.value = field
    }

    fun cancelSearch() {
        _searching.value = null
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    /** Scelta dalla ricerca o dai recenti per il campo che si sta modificando. */
    fun choose(place: NavigationPlace) {
        when (_searching.value ?: PlannerField.TO) {
            PlannerField.FROM -> _from.value = place
            PlannerField.TO -> {
                _to.value = place
                recentDestinations.add(place)
            }
        }
        _searching.value = null
        refreshPreview()
    }

    /** "La mia posizione" come partenza. */
    fun useMyPosition() {
        _from.value = null
        _searching.value = null
        refreshPreview()
    }

    /** Scambia partenza e arrivo; con la propria posizione come arrivo non si puo' (resterebbe senza meta). */
    fun swap() {
        val from = _from.value ?: return
        _from.value = _to.value
        _to.value = from
        refreshPreview()
    }

    fun removeRecent(place: NavigationPlace) = recentDestinations.remove(place)

    fun setTravelMode(mode: TravelMode) {
        if (mode == _travelMode.value) return
        _travelMode.value = mode
        refreshPreview()
    }

    fun setAllowSteps(allow: Boolean) {
        if (allow == usageModePreferences.allowSteps.value) return
        usageModePreferences.setAllowSteps(allow)
        refreshPreview()
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted || gps.hasPermission()) refreshPreview()
    }

    /** Ricalcola l'anteprima: dopo ogni cambio di partenza, arrivo o mezzo, o con "Riprova". */
    fun refreshPreview() {
        previewJob?.cancel()
        val destination = _to.value ?: run { _preview.value = PlannerPreview.Idle; return }
        val origin = _from.value
        val current = regionId.value ?: destination.regionId
        val choice = routing.value
        previewJob = viewModelScope.launch {
            val start = if (origin != null) {
                origin.point
            } else {
                if (!gps.hasPermission()) { _preview.value = PlannerPreview.NeedsPermission; return@launch }
                _preview.value = PlannerPreview.Calculating(0.0)
                val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { gps.fixes().first() }
                    ?: run { _preview.value = PlannerPreview.NoLocation; return@launch }
                RoutePoint(fix.latitude, fix.longitude).also { lastPosition.value = it }
            }
            _preview.value = PlannerPreview.Calculating(0.0)
            val regionIds = navigationRegionIds(current, routingRegions(), start, destination.point)
            val result = routeEngineFactory.create(regionIds).route(start, destination.point, choice.profile, choice.params) { progress ->
                _preview.value = PlannerPreview.Calculating(progress)
            }
            _preview.value = if (result is RouteResult.Found) PlannerPreview.Ready(result.route, regionIds) else PlannerPreview.Unavailable(result)
        }
    }

    private suspend fun search(text: String, current: String?, regions: Map<String, String>): List<PlannerResult> {
        if (text.trim().length < 2 || regions.isEmpty()) return emptyList()
        // La regione aperta per prima: con il limite, i suoi risultati non restano fuori per quelli delle altre.
        val ids = listOfNotNull(current?.takeIf { it in regions }) + (regions.keys - setOfNotNull(current))
        val language = java.util.Locale.getDefault().language
        val reference = lastPosition.value ?: _from.value?.point
        val pois = withContext(Dispatchers.IO) { poiRepository.searchByName(ids, text, SEARCH_LIMIT) }
        return pois.map { poi -> poi.toResult(language, current, regions, reference) }
            .sortedWith(compareBy<PlannerResult> { it.otherRegionName != null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE })
            .take(RESULTS_SHOWN)
    }

    private fun Poi.toResult(language: String, current: String?, regions: Map<String, String>, reference: RoutePoint?) = PlannerResult(
        place = NavigationPlace(displayName(language), latitude, longitude, regionId),
        typeLabel = poiTypeLabel(osmTag),
        otherRegionName = regions[regionId]?.takeIf { regionId != current },
        distanceMeters = reference?.let { approximateDistance(it, RoutePoint(latitude, longitude)) },
    )

    // Regioni installate con i Percorsi e riquadro della loro mappa, come in NavigationViewModel.
    private suspend fun routingRegions(): List<RoutingRegion> = withContext(Dispatchers.IO) {
        regionRepository.observeInstalled().first()
            .filter { it.routingVersion != null }
            .map { RoutingRegion(it.regionId, tileSource.regionBounds(it.regionId)) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val SEARCH_LIMIT = 200
        const val RESULTS_SHOWN = 30
        const val LOCATION_TIMEOUT_MILLIS = 20_000L
    }
}

// Distanza in linea d'aria per ordinare i risultati: l'approssimazione equirettangolare basta a pochi km.
private fun approximateDistance(a: RoutePoint, b: RoutePoint): Double {
    val x = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    val y = Math.toRadians(b.latitude - a.latitude)
    return sqrt(x * x + y * y) * 6_371_000.0
}
