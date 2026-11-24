package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.AddressResult
import com.pockettravel.core.data.AddressSearchRepository
import com.pockettravel.core.data.DefaultDispatcher
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.NearbyPois
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.TransitJourney
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.displayName
import com.pockettravel.core.data.parseAddressQuery
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.data.staleRoutingDate
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.label
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** Quale campo della scheda si sta scegliendo con la ricerca; [STOP] e' una tappa nuova («Aggiungi tappa»). */
enum class PlannerField { FROM, TO, WAYPOINT }

/** Tappe al massimo fra partenza e destinazione (utente, 2026-11-17). */
const val MAX_WAYPOINTS = 3

/**
 * Un risultato della ricerca: il posto, il suo tipo (stringa di PoiTypes) e la regione se non e' quella aperta.
 * [isAddress]: un indirizzo (via e civico) e non un punto di interesse.
 */
data class PlannerResult(
    val place: NavigationPlace, val typeLabel: Int?, val otherRegionName: String?, val distanceMeters: Double?,
    val isAddress: Boolean = false,
)

// Posti da visitare: in una ricerca per nome vengono prima di hotel, negozi e parcheggi che ne portano il nome.
private val SIGHTS = setOf(
    PoiCategory.MUSEI_ARTE, PoiCategory.LUOGHI_STORICI, PoiCategory.ATTRAZIONI, PoiCategory.LUOGHI_DI_CULTO,
    PoiCategory.PARCHI_DIVERTIMENTO, PoiCategory.ZOO, PoiCategory.PARCHI_ACQUATICI,
)

/** 0 il piu' pertinente: posto da visitare col nome uguale alla ricerca, poi posto da visitare, poi nome uguale, poi il resto. */
internal fun searchRelevance(poi: Poi, query: String): Int = searchRelevanceFolded(poi, foldForSearch(query))

// Come searchRelevance, con la ricerca gia' ripulita da maiuscole e accenti: si piega una volta per ricerca, non per posto.
internal fun searchRelevanceFolded(poi: Poi, wanted: String): Int {
    val exact = listOfNotNull(poi.name, poi.nameIt, poi.nameEn).any { foldForSearch(it) == wanted }
    return (if (poi.poiCategory() in SIGHTS) 0 else 2) + (if (exact) 0 else 1)
}

/**
 * I risultati per [this] ordinati: prima la regione aperta, poi la pertinenza, poi la distanza. La pertinenza
 * ([relevance]) si calcola una volta per posto, non a ogni confronto dell'ordinamento.
 */
internal fun List<Poi>.rankedResults(relevance: (Poi) -> Int, toResult: (Poi) -> PlannerResult): List<PlannerResult> =
    map { poi -> relevance(poi) to toResult(poi) }
        .sortedWith(
            compareBy<Pair<Int, PlannerResult>> { it.second.otherRegionName != null }
                .thenBy { it.first }
                .thenBy { it.second.distanceMeters ?: Double.MAX_VALUE },
        )
        .map { it.second }

/**
 * La destinazione da cercare per una richiesta dell'assistente: quella col momento del giorno ("bar stasera") se e' il nome di
 * un posto ([hasPois]), altrimenti quella senza ("bar"). Solo i POI: un indirizzo non finisce con "domani".
 */
internal suspend fun destinationQuery(destination: String, destinationWithTime: String?, hasPois: suspend (String) -> Boolean): String =
    if (destinationWithTime != null && hasPois(destinationWithTime)) destinationWithTime else destination

/**
 * [name] contiene [words] come parole intere, senza badare a maiuscole e accenti: "Il Bar Stasera" contiene "bar stasera",
 * "Station Nowy Świat" non contiene "station now" (la ricerca per nome trova anche le parti di parola).
 */
internal fun containsWords(name: String, words: String): Boolean {
    val wanted = foldForSearch(words)
    return wanted.isNotEmpty() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(wanted)}(?![\\p{L}\\p{N}])").containsMatchIn(foldForSearch(name))
}

// Senza maiuscole, accenti e spazi in piu': "tour  eiffel" e' uguale a "Tour Eiffel".
private fun foldForSearch(text: String): String =
    Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "").replace(SPACES, " ")

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")

/** Una regione del catalogo senza rete stradale tra partenza e arrivo, col peso della rete stradale: il Navigatore ne propone il download. */
// carOnly: c'e' gia' la rete stradale solo auto, manca quella completa per il modo scelto.
data class MissingRegion(val regionId: String, val name: String, val routingBytes: Long, val carOnly: Boolean = false)

/** Gli altri percorsi dell'anteprima: si calcolano solo a richiesta, perche' ognuno costa quanto il principale. */
sealed interface OtherRoutes {
    data object NotAsked : OtherRoutes

    data object Calculating : OtherRoutes

    /** [routes]: il percorso principale e poi le alternative; con il solo principale non c'e' un altro percorso. */
    data class Found(val routes: List<Route>) : OtherRoutes
}

/** Anteprima del percorso prima di partire. */
sealed interface PlannerPreview {
    /** Manca la destinazione (o la partenza scelta). */
    data object Idle : PlannerPreview

    /** Partenza "La mia posizione": serve il permesso di posizione. */
    data object NeedsPermission : PlannerPreview

    /** Permesso dato ma il GPS e' spento o non trova la posizione. */
    data object NoLocation : PlannerPreview

    data class Calculating(val progress: Double) : PlannerPreview

    /** [route]: il percorso scelto, quello che parte con la navigazione; [others]: gli altri percorsi, chiesti con «Altri percorsi». */
    data class Ready(val route: Route, val regionIds: List<String>, val others: OtherRoutes = OtherRoutes.NotAsked) : PlannerPreview

    data class Unavailable(val result: RouteResult) : PlannerPreview

    /** Modo «Mezzi»: gli itinerari con i mezzi pubblici (RAPTOR), dal meno cambi al piu' veloce; vuoto se non ce ne sono. */
    data class Transit(val journeys: List<TransitJourney>) : PlannerPreview
}

/**
 * Lo stato della scheda Navigatore prima della navigazione. [from] null = la propria posizione; [query] e [results] hanno
 * un valore solo con la ricerca aperta ([searching]); [routing] e' il profilo BRouter del modo scelto; [mapRegionIds]
 * le regioni della mappa; [idlePosition] la propria posizione sulla mappa senza destinazione, con i [nearbyPois] attorno;
 * [routingInstalled] dice se la regione aperta ha la rete stradale e [routingRegionIds] quali regioni ce l'hanno utile
 * al modo; [arriveBy] e' l'ora a cui si vuole arrivare (null = si parte subito) e [reminder] l'ora di partenza con un
 * avviso programmato. [toCatch]: la corsa da prendere con "Raggiungi la fermata", alla cui partenza [arriveBy] e' fissata.
 * [waypoints]: le tappe fra partenza e destinazione, in ordine.
 */
data class PlannerUiState(
    val from: NavigationPlace? = null,
    val to: NavigationPlace? = null,
    val waypoints: List<NavigationPlace> = emptyList(),
    val searching: PlannerField? = null,
    val query: String = "",
    val results: List<PlannerResult> = emptyList(),
    val preview: PlannerPreview = PlannerPreview.Idle,
    val routeProfile: RouteProfile,
    val routing: BRouterProfile,
    val allowSteps: Boolean = false,
    val avoid: Set<RouteAvoid> = emptySet(),
    val recents: List<NavigationPlace> = emptyList(),
    val favorites: List<NavigationPlace> = emptyList(),
    val mapRegionIds: List<String> = emptyList(),
    val idlePosition: RoutePoint? = null,
    val nearbyPois: NearbyPois? = null,
    val routingInstalled: Boolean = true,
    val routingRegionIds: Set<String> = emptySet(),
    val arriveBy: LocalDateTime? = null,
    val reminder: LocalDateTime? = null,
    val toCatch: TripToCatch? = null,
    val oldRoutingDate: LocalDate? = null,
    // Modo «Mezzi» scelto al posto di [routeProfile]: l'anteprima e' PlannerPreview.Transit.
    val transit: Boolean = false,
)

/**
 * Tab Navigazione dell'hub: partenza (la propria posizione o un posto), destinazione cercata per nome
 * tra i punti di interesse delle regioni installate, modo e anteprima del percorso. La navigazione passo
 * passo e' NavigationInstructions, nella stessa scheda: "Avvia" la fa partire (NavigationViewModel) quando
 * si parte dalla propria posizione. Partenza, arrivo, modo, ora di arrivo e avviso di partenza stanno
 * in [SavedStateHandle]: se il sistema chiude l'app, tornando si ritrova lo stesso Navigatore.
 */
@HiltViewModel
class NavigationPlannerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val departureReminder: DepartureReminderScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val poiRepository: PoiRepository,
    private val addressSearchRepository: AddressSearchRepository,
    private val regionRepository: RegionRepository,
    private val routeEngineFactory: RouteEngineFactory,
    private val transitRepository: TransitRepository,
    private val gps: GpsLocationSource,
    private val recentDestinations: RecentDestinations,
    private val favoriteDestinations: FavoriteDestinations,
    private val usageModePreferences: UsageModePreferences,
    private val navigationPreferences: NavigationPreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    /** Per la mappa dell'anteprima del percorso: lo stesso stile della scheda Mappa. */
    val tileSource: OfflineTileSource,
) : ViewModel() {
    private val regionId = MutableStateFlow<String?>(null)

    /** Regioni installate (id -> nome): la ricerca le copre tutte, la aperta per prima. */
    private val installed: StateFlow<Map<String, String>> = regionRepository.observeInstalled()
        .map { regions -> regions.associate { it.regionId to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // null = la propria posizione.
    private val _from = MutableStateFlow(savedStateHandle.savedPlace(KEY_FROM))
    private val _to = MutableStateFlow(savedStateHandle.savedPlace(KEY_TO))
    private val waypointList = MutableStateFlow(RecentDestinations.decode(savedStateHandle.get<String>(KEY_WAYPOINTS)))

    private val _searching = MutableStateFlow<PlannerField?>(null)
    private val _query = MutableStateFlow("")

    private val _routeProfile = MutableStateFlow(
        savedStateHandle.get<String>(KEY_MODE)?.let(RouteProfile::valueOf) ?: RouteProfile.from(usageModePreferences.modes.value),
    )

    // Cosa evitare in questo percorso: parte dalle Impostazioni e torna a quelle con un'altra destinazione.
    private val routeAvoid = MutableStateFlow(savedStateHandle.get<String>(KEY_AVOID)?.let(RouteAvoid::decode) ?: navigationPreferences.avoid.value)
    private val routing: StateFlow<BRouterProfile> = combine(_routeProfile, usageModePreferences.accessible, usageModePreferences.allowSteps, routeAvoid, ::brouterProfile)
        .stateIn(viewModelScope, SharingStarted.Eagerly, brouterProfile(_routeProfile.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value, routeAvoid.value))

    private val _preview = MutableStateFlow<PlannerPreview>(PlannerPreview.Idle)
    private val transitMode = MutableStateFlow(savedStateHandle.get<Boolean>(KEY_TRANSIT) ?: false)
    private var previewJob: Job? = null
    private var alternativesJob: Job? = null

    // Partenza dell'ultima anteprima: con «La mia posizione», il fix usato, cosi' le alternative partono dallo stesso punto.
    private var previewStart: RoutePoint? = null

    /** Posizione nota (dall'ultimo calcolo da "La mia posizione"): ordina i risultati per distanza. */
    private val lastPosition = MutableStateFlow<RoutePoint?>(null)

    /**
     * Le regioni della mappa prima del percorso: quella della scheda, poi quelle di partenza e arrivo (una destinazione
     * a Riga dalla pagina della regione di San Marino mostra Riga). Senza nessuna delle tre (Navigatore della barra, nessuna
     * destinazione) tutte quelle installate.
     */
    private val mapRegionIds: StateFlow<List<String>> = combine(regionId, _from, _to, waypointList, installed) { id, from, to, waypoints, regions ->
        (listOfNotNull(id, from?.regionId, to?.regionId) + waypoints.map { it.regionId }).filter { it in regions }.distinct().ifEmpty { regions.keys.toList() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Permesso di posizione, riletto quando l'utente risponde (onPermissionResult).
    private val locationAllowed = MutableStateFlow(gps.hasPermission())

    /**
     * Senza destinazione, con permesso e GPS acceso: la propria posizione sulla mappa, null altrimenti. Il GPS si ascolta solo
     * mentre la scheda e' aperta (WhileSubscribed).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val idlePosition: StateFlow<RoutePoint?> = combine(_to, locationAllowed, gps.gpsEnabled()) { to, allowed, enabled -> to == null && allowed && enabled }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) gps.fixes().map { RoutePoint(it.latitude, it.longitude) } else flowOf(null) }
        .onEach { position -> if (position != null) lastPosition.value = position }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** I POI attorno alla posizione senza destinazione (PoiRepository.nearby), ricercati dopo ogni spostamento di 50 m. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val nearbyPois: StateFlow<NearbyPois?> = combine(idlePosition, installed) { position, regions -> position to regions.keys.toList() }
        .distinctUntilChanged { old, new ->
            old.second == new.second && old.first?.let { a -> new.first?.let { b -> NavigationTracker.distanceMeters(a, b) < NEARBY_REFRESH_METERS } } ?: (old.first == new.first)
        }
        .mapLatest { (position, regions) -> position?.let { poiRepository.nearby(regions, it.latitude, it.longitude) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Recenti delle regioni installate. */
    private val recents: StateFlow<List<NavigationPlace>> = combine(recentDestinations.places, installed) { places, regions ->
        places.filter { it.regionId in regions }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Preferiti delle regioni installate. */
    private val favorites: StateFlow<List<NavigationPlace>> = combine(favoriteDestinations.places, installed) { places, regions ->
        places.filter { it.regionId in regions }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val results: StateFlow<List<PlannerResult>> = combine(_query.debounce(SEARCH_DEBOUNCE_MILLIS), regionId, installed, searchPosition()) { text, current, regions, _ ->
        SearchInput(text, current, regions)
    }.mapLatest { (text, current, regions) -> search(text, current, regions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // La posizione che rifa' la ricerca: la prima nota o uno spostamento di 200 m, non ogni fix del GPS.
    private fun searchPosition() = lastPosition.distinctUntilChanged { old, new ->
        old != null && new != null && NavigationTracker.distanceMeters(old, new) < SEARCH_REFRESH_METERS
    }

    /**
     * La regione aperta ha la rete stradale installata. Se mancano, "nessun dato di percorso" vuol dire
     * "scaricali"; se ci sono, partenza o arrivo sono fuori dalle zone scaricate e riscaricare non serve.
     */
    private val routingInstalled: StateFlow<Boolean> = combine(usableRouting(), regionId) { ids, id -> id == null || id in ids }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Le regioni con la rete stradale installata utile al modo scelto (usableRoutingRegions), di qualunque regione: serve a
     * rileggere quale regione manca.
     */
    private val routingRegionIds: StateFlow<Set<String>> = usableRouting().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private fun usableRouting() = combine(regionRepository.observeInstalled(), routingVariantPreferences.installedCarOnly, _routeProfile) { regions, carOnly, mode ->
        usableRoutingRegions(regions.filter { it.routingVersion != null }.map { it.regionId }.toSet(), carOnly, mode)
    }

    /** La partenza del percorso: il posto scelto o l'ultima posizione nota, null se ancora ignota. */
    fun startPoint(): RoutePoint? = _from.value?.point ?: lastPosition.value

    private val _arriveBy = MutableStateFlow(savedStateHandle.get<Long>(KEY_ARRIVE_BY)?.let(::minuteToDateTime))

    private val _reminder = MutableStateFlow(savedStateHandle.get<Long>(KEY_REMINDER)?.let(::minuteToDateTime))

    // Non si salva con lo stato: dopo la chiusura del processo resta l'ora d'arrivo, senza la linea.
    private val toCatch = MutableStateFlow<TripToCatch?>(null)

    // La rete stradale piu' vecchia tra le regioni dell'anteprima del percorso, se ha piu' di ROUTING_STALE_MONTHS mesi.
    private val oldRoutingDate = combine(_preview, regionRepository.observeInstalled()) { preview, installed ->
        val ids = (preview as? PlannerPreview.Ready)?.regionIds.orEmpty()
        val today = LocalDate.now()
        installed.filter { it.regionId in ids }.mapNotNull { staleRoutingDate(it.routingVersion, today) }.minOrNull()
    }

    // Il percorso scelto, la ricerca e la mappa, a gruppi di cinque flussi: ognuno parte dal precedente.
    private val routeState = combine(_from, _to, _routeProfile, routing, _preview) { from, to, mode, profile, preview ->
        PlannerUiState(from = from, to = to, routeProfile = mode, routing = profile, preview = preview)
    }
    private val searchState = combine(routeState, _searching, _query, results, combine(recents, favorites, ::Pair)) { state, searching, query, results, (recents, favorites) ->
        state.copy(searching = searching, query = query, results = results, recents = recents, favorites = favorites)
    }
    private val mapState = combine(searchState, mapRegionIds, idlePosition, nearbyPois, routingInstalled) { state, regionIds, position, nearby, installed ->
        state.copy(mapRegionIds = regionIds, idlePosition = position, nearbyPois = nearby, routingInstalled = installed)
    }

    /** Tutto quello che la scheda Navigatore legge del percorso, in un solo stato. */
    val uiState: StateFlow<PlannerUiState> = combine(
        mapState, routingRegionIds, combine(usageModePreferences.allowSteps, routeAvoid, ::Pair), combine(_arriveBy, toCatch, ::Pair),
        combine(combine(_reminder, oldRoutingDate, ::Pair), combine(waypointList, transitMode, ::Pair), ::Pair),
    ) { state, routingRegionIds, (allowSteps, avoid), (arriveBy, trip), (reminderAndRouting, waypointsAndTransit) ->
        state.copy(
            routingRegionIds = routingRegionIds, allowSteps = allowSteps, avoid = avoid, arriveBy = arriveBy, reminder = reminderAndRouting.first,
            toCatch = trip, oldRoutingDate = reminderAndRouting.second, waypoints = waypointsAndTransit.first, transit = waypointsAndTransit.second,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlannerUiState(
            from = _from.value,
            to = _to.value,
            waypoints = waypointList.value,
            preview = _preview.value,
            routeProfile = _routeProfile.value,
            routing = routing.value,
            allowSteps = usageModePreferences.allowSteps.value,
            avoid = routeAvoid.value,
            routingInstalled = routingInstalled.value,
            routingRegionIds = routingRegionIds.value,
            arriveBy = _arriveBy.value,
            reminder = _reminder.value,
        ),
    )

    init {
        _from.onEach { savedStateHandle[KEY_FROM] = it?.toSavedState() }.launchIn(viewModelScope)
        _to.onEach { savedStateHandle[KEY_TO] = it?.toSavedState() }.launchIn(viewModelScope)
        waypointList.onEach { savedStateHandle[KEY_WAYPOINTS] = RecentDestinations.encode(it) }.launchIn(viewModelScope)
        _routeProfile.onEach { savedStateHandle[KEY_MODE] = it.name }.launchIn(viewModelScope)
        _arriveBy.onEach { savedStateHandle[KEY_ARRIVE_BY] = it?.let(::dateTimeToMinute) }.launchIn(viewModelScope)
        _reminder.onEach { savedStateHandle[KEY_REMINDER] = it?.let(::dateTimeToMinute) }.launchIn(viewModelScope)
        // "Raggiungi la fermata": con la durata del percorso l'ora di arrivo va alla prima partenza che si fa in tempo a
        // prendere; se nessuna, resta la scelta e il riquadro mostra il ritardo.
        combine(_preview, toCatch, ::Pair).onEach { (preview, trip) ->
            val route = (preview as? PlannerPreview.Ready)?.route ?: return@onEach
            val catchable = trip?.departures?.firstOrNull { minutesLate(it, route.durationSeconds) <= 0 } ?: return@onEach
            if (catchable != _arriveBy.value) {
                cancelReminder()
                _arriveBy.value = catchable
            }
        }.launchIn(viewModelScope)
        // Rete stradale appena installata dopo "Scarica la rete stradale" (della regione aperta o di un'altra che copre
        // partenza o arrivo): l'anteprima del percorso si ricalcola da sola.
        viewModelScope.launch {
            var hadRouting: Set<String>? = null
            usableRouting().collect { has ->
                val before = hadRouting
                if (before != null && (has - before).isNotEmpty()) refreshPreview()
                hadRouting = has
            }
        }
        // Ripreso dopo la chiusura del processo: l'anteprima del percorso si ricalcola dalla destinazione salvata.
        if (_to.value != null) refreshPreview()
    }

    fun load(regionId: String?) {
        this.regionId.value = regionId
    }

    /** Destinazione raggiunta: si torna alla ricerca, partendo di nuovo dalla propria posizione. */
    fun clearDestination() {
        cancelReminder()
        setTo(null)
        setFrom(null)
        waypointList.value = emptyList()
        _arriveBy.value = null
        refreshPreview()
    }

    // L'avviso e' per un percorso preciso: se cambia la partenza, l'arrivo, il modo o l'ora si toglie.
    private fun setFrom(place: NavigationPlace?) {
        if (place != _from.value) cancelReminder()
        _from.value = place
    }

    // Con un'altra destinazione o un'altra ora la corsa da prendere non vale piu'.
    private fun setTo(place: NavigationPlace?) {
        if (place != _to.value) {
            cancelReminder()
            toCatch.value = null
            saveAvoid(navigationPreferences.avoid.value)
        }
        _to.value = place
    }

    /** Cambia l'ora di arrivo (null la toglie); l'avviso della partenza precedente non vale piu'. */
    fun setArriveBy(time: LocalDateTime?) {
        if (time == _arriveBy.value) return
        cancelReminder()
        toCatch.value = null
        _arriveBy.value = time
    }

    /** "Raggiungi la fermata": a piedi fino alla fermata di [trip], per arrivarci all'ora della sua partenza. */
    fun reachStop(trip: TripToCatch, regionId: String) {
        setRouteProfile(RouteProfile.WALK)
        setDestination(NavigationPlace(trip.stopName, trip.latitude, trip.longitude, regionId))
        setArriveBy(trip.departures.first())
        toCatch.value = trip
    }

    /** "Avvisami quando partire": una notifica all'ora di [departure], che sostituisce l'eventuale precedente. */
    fun setReminder(departure: LocalDateTime) {
        departureReminder.schedule(departure, _to.value?.name.orEmpty())
        _reminder.value = departure
    }

    /** Toglie l'avviso programmato: all'avvio della navigazione, all'arrivo e a ogni cambio del percorso. */
    fun cancelReminder() {
        if (_reminder.value == null) return
        departureReminder.cancel()
        _reminder.value = null
    }

    /** "Percorso" dal riquadro di un punto della mappa: destinazione pronta, partenza dalla propria posizione, nessuna tappa. */
    fun setDestination(place: NavigationPlace) {
        setTo(place)
        setFrom(null)
        waypointList.value = emptyList()
        closeSearch()
        recentDestinations.add(place)
        refreshPreview()
    }

    // La ricerca chiusa scarta il testo: i risultati non si ricalcolano piu' per una ricerca che nessuno vede.
    private fun closeSearch() {
        _searching.value = null
        _query.value = ""
    }

    // Richiesta di posizione della ricerca in corso: aprendo e chiudendo la ricerca non se ne accumulano altre.
    private var positionJob: Job? = null

    fun startSearch(field: PlannerField) {
        destinationJob?.cancel()
        _query.value = ""
        _searching.value = field
        // Una posizione per ordinare i risultati per distanza, se il permesso c'e' gia': senza, in ordine di regione.
        if (lastPosition.value == null && gps.hasPermission() && positionJob?.isActive != true) {
            positionJob = viewModelScope.launch {
                withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { gps.fixes().first() }
                    ?.let { lastPosition.value = RoutePoint(it.latitude, it.longitude) }
            }
        }
    }

    /**
     * "Portami al Colosseo" dall'assistente: ricerca della destinazione gia' compilata, la scelta resta all'utente.
     * [textWithTime] ("bar stasera" per "bar") prende il posto di [text] se e' il nome di un posto (destinationQuery): si
     * controlla dopo, senza far aspettare [text], perche' senza risultati la ricerca legge tutti i POI installati (secondi
     * su un milione). Se intanto l'utente ha cambiato la ricerca, resta la sua.
     */
    fun searchDestination(text: String, textWithTime: String? = null) {
        startSearch(PlannerField.TO)
        _query.value = text
        if (textWithTime == null) return
        val ids = installed.value.keys.toList()
        destinationJob = viewModelScope.launch {
            val chosen = destinationQuery(text, textWithTime) { candidate ->
                withContext(ioDispatcher) { poiRepository.searchByName(ids, candidate, DESTINATION_CHECK_LIMIT) }
                    .any { poi -> listOfNotNull(poi.name, poi.nameIt, poi.nameEn).any { containsWords(it, candidate) } }
            }
            if (_query.value == text) _query.value = chosen
        }
    }

    // Controllo della destinazione col momento del giorno ancora in corso: una nuova ricerca lo annulla, cosi' non la sovrascrive.
    private var destinationJob: Job? = null

    fun cancelSearch() {
        closeSearch()
    }

    fun setQuery(text: String) {
        destinationJob?.cancel()
        _query.value = text
    }

    /** Scelta dalla ricerca o dai recenti per il campo che si sta modificando. */
    fun choose(place: NavigationPlace) {
        when (_searching.value ?: PlannerField.TO) {
            PlannerField.FROM -> setFrom(place)
            PlannerField.TO -> {
                setTo(place)
                recentDestinations.add(place)
            }
            PlannerField.WAYPOINT -> {
                if (waypointList.value.size < MAX_WAYPOINTS) setWaypoints(waypointList.value + place)
                recentDestinations.add(place)
            }
        }
        closeSearch()
        refreshPreview()
    }

    /** Toglie la tappa in posizione [index]. */
    fun removeWaypoint(index: Int) {
        if (index !in waypointList.value.indices) return
        setWaypoints(waypointList.value.filterIndexed { i, _ -> i != index })
        refreshPreview()
    }

    /** Sposta la tappa [index] di un posto prima ([earlier]) o dopo. */
    fun moveWaypoint(index: Int, earlier: Boolean) {
        val target = if (earlier) index - 1 else index + 1
        val waypoints = waypointList.value
        if (index !in waypoints.indices || target !in waypoints.indices) return
        setWaypoints(waypoints.toMutableList().apply { add(target, removeAt(index)) })
        refreshPreview()
    }

    // Le tappe cambiano la durata del percorso, e con lei l'ora di partenza.
    private fun setWaypoints(waypoints: List<NavigationPlace>) {
        if (waypoints != waypointList.value) cancelReminder()
        waypointList.value = waypoints
    }

    /** "La mia posizione" come partenza. */
    fun useMyPosition() {
        setFrom(null)
        closeSearch()
        refreshPreview()
    }

    /** Scambia partenza e arrivo; con la propria posizione come arrivo non si puo' (resterebbe senza destinazione). */
    fun swap() {
        val from = _from.value ?: return
        setFrom(_to.value)
        setTo(from)
        setWaypoints(waypointList.value.reversed())
        refreshPreview()
    }

    fun removeRecent(place: NavigationPlace) = recentDestinations.remove(place)

    fun clearRecents() = recentDestinations.clear()

    /** Salva [place] tra i preferiti col nome [name] (libero: "Alloggio", "Da Marco"); la destinazione prende quel nome. */
    fun saveFavorite(place: NavigationPlace, name: String) {
        favoriteDestinations.save(place, name)
        val saved = favoriteDestinations.places.value.firstOrNull { it.isSamePoint(place) } ?: return
        // Solo il nome: stesso punto, percorso e promemoria restano.
        _to.value?.takeIf { it.isSamePoint(place) }?.let { _to.value = it.copy(name = saved.name) }
    }

    fun removeFavorite(place: NavigationPlace) = favoriteDestinations.remove(place)

    /**
     * Un posto da un'altra app (URI `geo:` o testo condiviso): se si legge diventa la destinazione e torna null; altrimenti
     * torna la stringa del messaggio da mostrare. Un `geo:0,0?q=testo` apre la ricerca con quel testo.
     */
    suspend fun openSharedPlace(text: String): Int? {
        val ids = regionRepository.observeInstalled().first().map { it.regionId }
        return when (val reading = readPlace(text)) {
            is PlaceReading.Coordinates -> if (ids.isEmpty()) {
                R.string.shared_place_no_regions
            } else {
                val name = reading.name ?: coordinatesText(reading.latitude, reading.longitude)
                setDestination(NavigationPlace(name, reading.latitude, reading.longitude, regionFor(reading.point, regionId.value, ids)))
                null
            }
            is PlaceReading.Search -> {
                searchDestination(reading.text)
                null
            }
            PlaceReading.ShortLink -> R.string.shared_place_short_link
            PlaceReading.Unreadable -> R.string.shared_place_unreadable
        }
    }

    fun setRouteProfile(mode: RouteProfile) {
        if (mode == _routeProfile.value && !transitMode.value) return
        cancelReminder()
        _routeProfile.value = mode
        setTransitValue(false)
        refreshPreview()
    }

    /** Modo «Mezzi»: al posto del percorso, gli itinerari con i mezzi pubblici della regione. */
    fun setTransit() {
        if (transitMode.value) return
        cancelReminder()
        setTransitValue(true)
        refreshPreview()
    }

    private fun setTransitValue(on: Boolean) {
        transitMode.value = on
        savedStateHandle[KEY_TRANSIT] = on
    }

    fun setAllowSteps(allow: Boolean) {
        if (allow == usageModePreferences.allowSteps.value) return
        // Le scale cambiano la durata del percorso, e con lei l'ora di partenza.
        cancelReminder()
        usageModePreferences.setAllowSteps(allow)
        refreshPreview()
    }

    /** Un'opzione "Evita" dell'anteprima, solo per questo percorso. */
    fun setAvoid(option: RouteAvoid, on: Boolean) {
        val avoid = if (on) routeAvoid.value + option else routeAvoid.value - option
        if (avoid == routeAvoid.value) return
        // Come le scale: cambia la durata del percorso, e con lei l'ora di partenza.
        cancelReminder()
        saveAvoid(avoid)
        refreshPreview()
    }

    private fun saveAvoid(avoid: Set<RouteAvoid>) {
        routeAvoid.value = avoid
        savedStateHandle[KEY_AVOID] = RouteAvoid.encode(avoid)
    }

    fun onPermissionResult(granted: Boolean) {
        locationAllowed.value = granted || gps.hasPermission()
        if (granted || gps.hasPermission()) refreshPreview()
    }

    /** Ricalcola l'anteprima del percorso: dopo ogni cambio di partenza, arrivo o modo, o con "Riprova". */
    fun refreshPreview() {
        previewJob?.cancel()
        alternativesJob?.cancel()
        val destination = _to.value ?: run { _preview.value = PlannerPreview.Idle; return }
        val origin = _from.value
        val via = waypointList.value.map { it.point }
        val current = regionId.value ?: destination.regionId
        // Dai valori di partenza, non da [routing]: quello si aggiorna solo al giro successivo della sua coroutine, e
        // dopo un cambio fatto da dentro una coroutine l'anteprima del percorso userebbe il modo di prima.
        val choice = brouterProfile(_routeProfile.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value, routeAvoid.value)
        // Assegnato prima di partire: un dispatcher che esegue subito chiamerebbe onProgress con previewJob ancora vecchio.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            val start = if (origin != null) {
                origin.point
            } else {
                if (!gps.hasPermission()) { _preview.value = PlannerPreview.NeedsPermission; return@launch }
                _preview.value = PlannerPreview.Calculating(0.0)
                val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { gps.fixes().first() }
                    ?: run { _preview.value = PlannerPreview.NoLocation; return@launch }
                RoutePoint(fix.latitude, fix.longitude).also { lastPosition.value = it }
            }
            previewStart = start
            _preview.value = PlannerPreview.Calculating(0.0)
            // Mezzi pubblici: una rete della regione, senza tappe (RAPTOR va dalla partenza all'arrivo).
            if (transitMode.value) {
                _preview.value = PlannerPreview.Transit(
                    transitRepository.journeys(current, start.latitude, start.longitude, destination.point.latitude, destination.point.longitude),
                )
                return@launch
            }
            val candidates = withContext(ioDispatcher) {
                routingRegions(regionRepository.observeInstalled().first(), routingVariantPreferences.installedCarOnly.value, _routeProfile.value, tileSource::regionBounds)
            }
            val regionIds = navigationRegionIds(current, candidates, start, destination.point, via)
            // Nazioni in mezzo senza rete stradale: "manca la rete stradale" subito, invece di un errore generico dopo il calcolo.
            val result = if (leavesRoutingRegions(candidates, start, destination.point, via)) {
                RouteResult.NoRoutingData
            } else {
                routeEngineFactory.create(regionIds).route(start, destination.point, choice.profile, choice.params, via = via) { progress ->
                    // Un avanzamento in ritardo di un calcolo annullato non sovrascrive l'anteprima di quello nuovo.
                    if (previewJob === self && self?.isActive == true) _preview.value = PlannerPreview.Calculating(progress)
                }
            }
            _preview.value = if (result is RouteResult.Found) PlannerPreview.Ready(result.route, regionIds) else PlannerPreview.Unavailable(result)
        }
        previewJob = job
        job.start()
    }

    /** «Altri percorsi»: fino a [ALTERNATIVE_ROUTES] alternative al percorso dell'anteprima, con le stesse opzioni. */
    fun findAlternatives() {
        val ready = _preview.value as? PlannerPreview.Ready
        val start = previewStart
        val destination = _to.value
        if (ready == null || start == null || destination == null) return
        val choice = brouterProfile(_routeProfile.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value, routeAvoid.value)
        alternativesJob?.cancel()
        _preview.value = ready.copy(others = OtherRoutes.Calculating)
        alternativesJob = viewModelScope.launch {
            val result = routeEngineFactory.create(ready.regionIds)
                .route(start, destination.point, choice.profile, choice.params, alternatives = ALTERNATIVE_ROUTES, via = waypointList.value.map { it.point })
            val alternatives = (result as? RouteResult.Found)?.alternatives.orEmpty().filter { it.points != ready.route.points }
            _preview.value = ready.copy(others = OtherRoutes.Found(listOf(ready.route) + alternatives))
        }
    }

    /** Sceglie uno dei percorsi trovati da [findAlternatives]: e' quello che parte con la navigazione. */
    fun selectRoute(route: Route) {
        val ready = _preview.value as? PlannerPreview.Ready ?: return
        if (route in (ready.others as? OtherRoutes.Found)?.routes.orEmpty()) _preview.value = ready.copy(route = route)
    }

    // Un testo che si legge come coordinate o link da' solo la riga del punto, subito: la ricerca per nome non parte.
    private suspend fun search(text: String, current: String?, regions: Map<String, String>): List<PlannerResult> {
        if (text.trim().length < 2 || regions.isEmpty()) return emptyList()
        val reading = readPlace(text)
        return when {
            reading is PlaceReading.Coordinates -> listOf(reading.toResult(current, regions))
            looksLikeLink(text) -> emptyList()
            else -> searchPlaces(text, current, regions)
        }
    }

    private suspend fun PlaceReading.Coordinates.toResult(current: String?, regions: Map<String, String>): PlannerResult {
        val regionId = regionFor(point, current, regions.keys)
        return PlannerResult(
            place = NavigationPlace(name ?: coordinatesText(latitude, longitude), latitude, longitude, regionId),
            typeLabel = R.string.planner_type_coordinates,
            otherRegionName = regions[regionId]?.takeIf { regionId != current },
            distanceMeters = (lastPosition.value ?: _from.value?.point)?.let { NavigationTracker.distanceMeters(it, point) },
        )
    }

    // La regione installata il cui rettangolo contiene il punto (la aperta per prima), altrimenti la aperta o la prima installata.
    private suspend fun regionFor(point: RoutePoint, current: String?, ids: Collection<String>): String {
        val ordered = listOfNotNull(current?.takeIf { it in ids }) + (ids - setOfNotNull(current))
        val containing = withContext(ioDispatcher) {
            ordered.firstOrNull { id ->
                tileSource.regionBounds(id)?.let { point.longitude in it.minLon..it.maxLon && point.latitude in it.minLat..it.maxLat } == true
            }
        }
        return containing ?: ordered.first()
    }

    private suspend fun searchPlaces(text: String, current: String?, regions: Map<String, String>): List<PlannerResult> {
        // La regione aperta per prima: con il limite, i suoi risultati non restano fuori per quelli delle altre.
        val ids = listOfNotNull(current?.takeIf { it in regions }) + (regions.keys - setOfNotNull(current))
        val language = java.util.Locale.getDefault().language
        val reference = lastPosition.value ?: _from.value?.point
        val pois = withContext(ioDispatcher) { poiRepository.searchByName(ids, text, SEARCH_LIMIT) }
        // Indirizzi solo dove sono installati i database di ricerca dei civici: altrimenti nessun risultato in piu'.
        val addresses = addressSearchRepository.search(ids, text)
        val byProximity = compareBy<PlannerResult> { it.otherRegionName != null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE }
        val addressResults = addresses.map { it.toResult(current, regions, reference) }.sortedWith(byProximity).take(ADDRESS_RESULTS_SHOWN)
        // Tra i punti di interesse prima i posti da visitare e i nomi uguali alla ricerca: "Louvre" trova il museo, non
        // gli hotel col suo nome piu' vicini.
        val wanted = foldForSearch(text)
        val poiResults = withContext(defaultDispatcher) {
            pois.rankedResults({ searchRelevanceFolded(it, wanted) }) { it.toResult(language, current, regions, reference) }
        }.take(RESULTS_SHOWN - addressResults.size)
        // Con un civico nella ricerca gli indirizzi esatti vengono per primi, altrimenti i punti di interesse.
        return if (parseAddressQuery(text).number != null) addressResults + poiResults else poiResults + addressResults
    }

    private fun AddressResult.toResult(current: String?, regions: Map<String, String>, reference: RoutePoint?) = PlannerResult(
        place = NavigationPlace(displayName, latitude, longitude, regionId),
        typeLabel = R.string.planner_type_address,
        otherRegionName = regions[regionId]?.takeIf { regionId != current },
        distanceMeters = reference?.let { NavigationTracker.distanceMeters(it, RoutePoint(latitude, longitude)) },
        isAddress = true,
    )

    private fun Poi.toResult(language: String, current: String?, regions: Map<String, String>, reference: RoutePoint?) = PlannerResult(
        place = NavigationPlace(displayName(language), latitude, longitude, regionId),
        // Senza un tipo preciso (stazioni, autostazioni) il nome della categoria della mappa.
        typeLabel = poiTypeLabel(osmTag) ?: poiCategory().label(),
        otherRegionName = regions[regionId]?.takeIf { regionId != current },
        distanceMeters = reference?.let { NavigationTracker.distanceMeters(it, RoutePoint(latitude, longitude)) },
    )

    private companion object {
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_WAYPOINTS = "waypoints"
        const val KEY_MODE = "mode"
        const val KEY_ARRIVE_BY = "arriveBy"
        const val KEY_REMINDER = "reminder"
        const val KEY_AVOID = "avoid"
        const val KEY_TRANSIT = "transit"
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val SEARCH_LIMIT = 200
        // Abbastanza per trovare il nome a parole intere tra quelli che contengono la destinazione solo come parte
        // di parola.
        const val DESTINATION_CHECK_LIMIT = 50
        const val RESULTS_SHOWN = 30
        const val ADDRESS_RESULTS_SHOWN = 10
        const val LOCATION_TIMEOUT_MILLIS = 20_000L
        const val ALTERNATIVE_ROUTES = 2
        const val NEARBY_REFRESH_METERS = 50.0
        const val SEARCH_REFRESH_METERS = 200.0
    }
}

private data class SearchInput(val text: String, val current: String?, val regions: Map<String, String>)
