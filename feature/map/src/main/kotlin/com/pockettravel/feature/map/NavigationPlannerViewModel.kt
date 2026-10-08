package com.pockettravel.feature.map

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.AddressResult
import com.pockettravel.core.data.AddressSearchRepository
import com.pockettravel.core.data.NearbyPois
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.parseAddressQuery
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.displayName
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.label
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sqrt

/** Quale dei due campi della tab si sta scegliendo con la ricerca. */
enum class PlannerField { FROM, TO }

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
internal fun searchRelevance(poi: Poi, query: String): Int {
    val wanted = foldForSearch(query)
    val exact = listOfNotNull(poi.name, poi.nameIt, poi.nameEn).any { foldForSearch(it) == wanted }
    return (if (poi.poiCategory() in SIGHTS) 0 else 2) + (if (exact) 0 else 1)
}

/**
 * La meta da cercare per una richiesta dell'assistente: quella col momento del giorno ("bar stasera") se e' il nome di
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
// carOnly: c'e' gia' la rete stradale solo auto, manca quella completa per il mezzo scelto.
data class MissingRegion(val regionId: String, val name: String, val routingBytes: Long, val carOnly: Boolean = false)

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
 * passo e' NavigationInstructions, nella stessa scheda: "Avvia" la fa partire (NavigationViewModel) quando
 * si parte dalla propria posizione. Partenza, arrivo, mezzo, ora di arrivo e avviso di partenza stanno
 * in [SavedStateHandle]: se il sistema chiude l'app, tornando si ritrova lo stesso Navigatore.
 */
@HiltViewModel
class NavigationPlannerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val poiRepository: PoiRepository,
    private val addressSearchRepository: AddressSearchRepository,
    private val regionRepository: RegionRepository,
    private val routeEngineFactory: RouteEngineFactory,
    private val gps: GpsLocationSource,
    private val recentDestinations: RecentDestinations,
    private val usageModePreferences: UsageModePreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    /** Per la mappa dell'anteprima: lo stesso stile della scheda Mappa. */
    val tileSource: OfflineTileSource,
) : ViewModel() {
    private val regionId = MutableStateFlow<String?>(null)

    /** Regioni installate (id -> nome): la ricerca le copre tutte, la aperta per prima. */
    private val installed: StateFlow<Map<String, String>> = regionRepository.observeInstalled()
        .map { regions -> regions.associate { it.regionId to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _from = MutableStateFlow(savedStateHandle.get<Bundle>(KEY_FROM)?.toPlace())
    /** null = la propria posizione. */
    val from: StateFlow<NavigationPlace?> = _from.asStateFlow()
    private val _to = MutableStateFlow(savedStateHandle.get<Bundle>(KEY_TO)?.toPlace())
    val to: StateFlow<NavigationPlace?> = _to.asStateFlow()

    private val _searching = MutableStateFlow<PlannerField?>(null)
    val searching: StateFlow<PlannerField?> = _searching.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _routeProfile = MutableStateFlow(
        savedStateHandle.get<String>(KEY_MODE)?.let(RouteProfile::valueOf) ?: RouteProfile.from(usageModePreferences.modes.value),
    )
    val routeProfile: StateFlow<RouteProfile> = _routeProfile.asStateFlow()
    val routing: StateFlow<BRouterProfile> = combine(_routeProfile, usageModePreferences.accessible, usageModePreferences.allowSteps, ::brouterProfile)
        .stateIn(viewModelScope, SharingStarted.Eagerly, brouterProfile(_routeProfile.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value))
    val allowSteps: StateFlow<Boolean> = usageModePreferences.allowSteps

    private val _preview = MutableStateFlow<PlannerPreview>(PlannerPreview.Idle)
    val preview: StateFlow<PlannerPreview> = _preview.asStateFlow()
    private var previewJob: Job? = null

    /** Posizione nota (dall'ultimo calcolo da "La mia posizione"): ordina i risultati per distanza. */
    private val lastPosition = MutableStateFlow<RoutePoint?>(null)

    /**
     * Le regioni della mappa prima del percorso: quella della tab, poi quelle di partenza e arrivo (una meta a Riga
     * dalla tab di San Marino mostra Riga). Senza nessuna delle tre (Navigatore della barra, nessuna meta) tutte quelle
     * installate.
     */
    val mapRegionIds: StateFlow<List<String>> = combine(regionId, _from, _to, installed) { id, from, to, regions ->
        listOfNotNull(id, from?.regionId, to?.regionId).filter { it in regions }.distinct().ifEmpty { regions.keys.toList() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Permesso di posizione, riletto quando l'utente risponde (onPermissionResult).
    private val locationAllowed = MutableStateFlow(gps.hasPermission())

    /**
     * Senza meta, con permesso e GPS acceso: la propria posizione sulla mappa, null altrimenti. Il GPS si ascolta solo
     * mentre la scheda e' aperta (WhileSubscribed).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val idlePosition: StateFlow<RoutePoint?> = combine(_to, locationAllowed, gps.gpsEnabled()) { to, allowed, enabled -> to == null && allowed && enabled }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) gps.fixes().map { RoutePoint(it.latitude, it.longitude) } else flowOf(null) }
        .onEach { position -> if (position != null) lastPosition.value = position }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** I POI attorno alla posizione senza meta (PoiRepository.nearby), ricercati dopo ogni spostamento di 50 m. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val nearbyPois: StateFlow<NearbyPois?> = combine(idlePosition, installed) { position, regions -> position to regions.keys.toList() }
        .distinctUntilChanged { old, new ->
            old.second == new.second && old.first?.let { a -> new.first?.let { b -> NavigationTracker.distanceMeters(a, b) < NEARBY_REFRESH_METERS } } ?: (old.first == new.first)
        }
        .mapLatest { (position, regions) -> position?.let { poiRepository.nearby(regions, it.latitude, it.longitude) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Recenti delle regioni installate. */
    val recents: StateFlow<List<NavigationPlace>> = combine(recentDestinations.places, installed) { places, regions ->
        places.filter { it.regionId in regions }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<PlannerResult>> = combine(_query.debounce(SEARCH_DEBOUNCE_MILLIS), regionId, installed, lastPosition) { text, current, regions, _ ->
        SearchInput(text, current, regions)
    }.mapLatest { (text, current, regions) -> search(text, current, regions) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * La regione aperta ha la rete stradale installata. Se mancano, "nessun dato di percorso" vuol dire
     * "scaricali"; se ci sono, partenza o arrivo sono fuori dalle zone scaricate e riscaricare non serve.
     */
    val routingInstalled: StateFlow<Boolean> = combine(usableRouting(), regionId) { ids, id -> id == null || id in ids }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Le regioni con la rete stradale installata utile al mezzo scelto (usableRoutingRegions), di qualunque regione: serve a
     * rileggere quale regione manca.
     */
    val routingRegionIds: StateFlow<Set<String>> = usableRouting().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private fun usableRouting() = combine(regionRepository.observeInstalled(), routingVariantPreferences.installedCarOnly, _routeProfile) { regions, carOnly, mode ->
        usableRoutingRegions(regions.filter { it.routingVersion != null }.map { it.regionId }.toSet(), carOnly, mode)
    }

    /** La partenza del percorso: il posto scelto o l'ultima posizione nota, null se ancora ignota. */
    fun startPoint(): RoutePoint? = _from.value?.point ?: lastPosition.value

    private val _arriveBy = MutableStateFlow(savedStateHandle.get<Long>(KEY_ARRIVE_BY)?.let(::minuteToDateTime))
    /** L'ora a cui si vuole arrivare ("Arriva alle..."), null se si parte subito. */
    val arriveBy: StateFlow<LocalDateTime?> = _arriveBy.asStateFlow()

    private val _reminder = MutableStateFlow(savedStateHandle.get<Long>(KEY_REMINDER)?.let(::minuteToDateTime))
    /** L'ora di partenza per cui c'e' un avviso programmato, null se nessuno. */
    val reminder: StateFlow<LocalDateTime?> = _reminder.asStateFlow()

    init {
        _from.onEach { savedStateHandle[KEY_FROM] = it?.toBundle() }.launchIn(viewModelScope)
        _to.onEach { savedStateHandle[KEY_TO] = it?.toBundle() }.launchIn(viewModelScope)
        _routeProfile.onEach { savedStateHandle[KEY_MODE] = it.name }.launchIn(viewModelScope)
        _arriveBy.onEach { savedStateHandle[KEY_ARRIVE_BY] = it?.let(::dateTimeToMinute) }.launchIn(viewModelScope)
        _reminder.onEach { savedStateHandle[KEY_REMINDER] = it?.let(::dateTimeToMinute) }.launchIn(viewModelScope)
        // Rete stradale appena installata dopo "Scarica la rete stradale" (della regione aperta o di un'altra che copre
        // partenza o arrivo): l'anteprima si ricalcola da sola.
        viewModelScope.launch {
            var hadRouting: Set<String>? = null
            usableRouting().collect { has ->
                val before = hadRouting
                if (before != null && (has - before).isNotEmpty()) refreshPreview()
                hadRouting = has
            }
        }
        // Ripreso dopo la chiusura del processo: l'anteprima si ricalcola dalla meta salvata.
        if (_to.value != null) refreshPreview()
    }

    fun load(regionId: String?) {
        this.regionId.value = regionId
    }

    /** Meta raggiunta: si torna alla ricerca, partendo di nuovo dalla propria posizione. */
    fun clearDestination() {
        cancelReminder()
        setTo(null)
        setFrom(null)
        _arriveBy.value = null
        refreshPreview()
    }

    // L'avviso e' per un percorso preciso: se cambia la partenza, l'arrivo, il mezzo o l'ora si toglie.
    private fun setFrom(place: NavigationPlace?) {
        if (place != _from.value) cancelReminder()
        _from.value = place
    }

    private fun setTo(place: NavigationPlace?) {
        if (place != _to.value) cancelReminder()
        _to.value = place
    }

    /** Cambia l'ora di arrivo (null la toglie); l'avviso della partenza precedente non vale piu'. */
    fun setArriveBy(time: LocalDateTime?) {
        if (time == _arriveBy.value) return
        cancelReminder()
        _arriveBy.value = time
    }

    /** "Avvisami quando partire": una notifica all'ora di [departure], che sostituisce l'eventuale precedente. */
    fun setReminder(departure: LocalDateTime) {
        DepartureReminder.schedule(context, departure, _to.value?.name.orEmpty())
        _reminder.value = departure
    }

    /** Toglie l'avviso programmato: all'avvio della guida, all'arrivo e a ogni cambio del percorso. */
    fun cancelReminder() {
        if (_reminder.value == null) return
        DepartureReminder.cancel(context)
        _reminder.value = null
    }

    /** "Indicazioni" dal riquadro di un punto della mappa: destinazione pronta, partenza dalla propria posizione. */
    fun setDestination(place: NavigationPlace) {
        setTo(place)
        setFrom(null)
        _searching.value = null
        recentDestinations.add(place)
        refreshPreview()
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
     * "Portami al Colosseo" dall'assistente: ricerca della meta gia' compilata, la scelta resta all'utente.
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
                withContext(Dispatchers.IO) { poiRepository.searchByName(ids, candidate, DESTINATION_CHECK_LIMIT) }
                    .any { poi -> listOfNotNull(poi.name, poi.nameIt, poi.nameEn).any { containsWords(it, candidate) } }
            }
            if (_query.value == text) _query.value = chosen
        }
    }

    // Controllo della meta col momento del giorno ancora in corso: una nuova ricerca lo annulla, cosi' non la sovrascrive.
    private var destinationJob: Job? = null

    fun cancelSearch() {
        _searching.value = null
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
        }
        _searching.value = null
        refreshPreview()
    }

    /** "La mia posizione" come partenza. */
    fun useMyPosition() {
        setFrom(null)
        _searching.value = null
        refreshPreview()
    }

    /** Scambia partenza e arrivo; con la propria posizione come arrivo non si puo' (resterebbe senza meta). */
    fun swap() {
        val from = _from.value ?: return
        setFrom(_to.value)
        setTo(from)
        refreshPreview()
    }

    fun removeRecent(place: NavigationPlace) = recentDestinations.remove(place)

    fun clearRecents() = recentDestinations.clear()

    fun setRouteProfile(mode: RouteProfile) {
        if (mode == _routeProfile.value) return
        cancelReminder()
        _routeProfile.value = mode
        refreshPreview()
    }

    fun setAllowSteps(allow: Boolean) {
        if (allow == usageModePreferences.allowSteps.value) return
        // Le scale cambiano la durata del percorso, e con lei l'ora di partenza.
        cancelReminder()
        usageModePreferences.setAllowSteps(allow)
        refreshPreview()
    }

    fun onPermissionResult(granted: Boolean) {
        locationAllowed.value = granted || gps.hasPermission()
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
            val candidates = routingRegions()
            val regionIds = navigationRegionIds(current, candidates, start, destination.point)
            // Nazioni in mezzo senza rete stradale: "manca la rete stradale" subito, invece di un errore generico dopo il calcolo.
            val result = if (leavesRoutingRegions(candidates, start, destination.point)) {
                RouteResult.NoRoutingData
            } else {
                routeEngineFactory.create(regionIds).route(start, destination.point, choice.profile, choice.params) { progress ->
                    _preview.value = PlannerPreview.Calculating(progress)
                }
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
        // Indirizzi solo dove sono installati i database di ricerca dei civici: altrimenti nessun risultato in piu'.
        val addresses = addressSearchRepository.search(ids, text)
        val byProximity = compareBy<PlannerResult> { it.otherRegionName != null }.thenBy { it.distanceMeters ?: Double.MAX_VALUE }
        val addressResults = addresses.map { it.toResult(current, regions, reference) }.sortedWith(byProximity).take(ADDRESS_RESULTS_SHOWN)
        // Tra i punti di interesse prima i posti da visitare e i nomi uguali alla ricerca: "Louvre" trova il museo, non
        // gli hotel col suo nome piu' vicini.
        val poiResults = pois.map { poi -> poi to poi.toResult(language, current, regions, reference) }
            .sortedWith(
                compareBy<Pair<Poi, PlannerResult>> { it.second.otherRegionName != null }
                    .thenBy { searchRelevance(it.first, text) }
                    .thenBy { it.second.distanceMeters ?: Double.MAX_VALUE },
            )
            .map { it.second }
            .take(RESULTS_SHOWN - addressResults.size)
        // Con un civico nella ricerca gli indirizzi esatti vengono per primi, altrimenti i punti di interesse.
        return if (parseAddressQuery(text).number != null) addressResults + poiResults else poiResults + addressResults
    }

    private fun AddressResult.toResult(current: String?, regions: Map<String, String>, reference: RoutePoint?) = PlannerResult(
        place = NavigationPlace(displayName, latitude, longitude, regionId),
        typeLabel = R.string.planner_type_address,
        otherRegionName = regions[regionId]?.takeIf { regionId != current },
        distanceMeters = reference?.let { approximateDistance(it, RoutePoint(latitude, longitude)) },
        isAddress = true,
    )

    private fun Poi.toResult(language: String, current: String?, regions: Map<String, String>, reference: RoutePoint?) = PlannerResult(
        place = NavigationPlace(displayName(language), latitude, longitude, regionId),
        // Senza un tipo preciso (stazioni, autostazioni) il nome della categoria della mappa.
        typeLabel = poiTypeLabel(osmTag) ?: poiCategory().label(),
        otherRegionName = regions[regionId]?.takeIf { regionId != current },
        distanceMeters = reference?.let { approximateDistance(it, RoutePoint(latitude, longitude)) },
    )

    // Regioni installate con la rete stradale utile al mezzo scelto e riquadro della loro mappa, come in NavigationViewModel.
    private suspend fun routingRegions(): List<RoutingRegion> = withContext(Dispatchers.IO) {
        val usable = usableRouting().first()
        regionRepository.observeInstalled().first()
            .filter { it.regionId in usable }
            .map { RoutingRegion(it.regionId, tileSource.regionBounds(it.regionId)) }
    }

    private companion object {
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_MODE = "mode"
        const val KEY_ARRIVE_BY = "arriveBy"
        const val KEY_REMINDER = "reminder"
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val SEARCH_LIMIT = 200
        // Abbastanza per trovare il nome a parole intere tra quelli che contengono la meta solo come parte di parola.
        const val DESTINATION_CHECK_LIMIT = 50
        const val RESULTS_SHOWN = 30
        const val ADDRESS_RESULTS_SHOWN = 10
        const val LOCATION_TIMEOUT_MILLIS = 20_000L
        const val NEARBY_REFRESH_METERS = 50.0
    }
}

private data class SearchInput(val text: String, val current: String?, val regions: Map<String, String>)

// Distanza in linea d'aria per ordinare i risultati: l'approssimazione equirettangolare basta a pochi km.
private fun approximateDistance(a: RoutePoint, b: RoutePoint): Double {
    val x = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    val y = Math.toRadians(b.latitude - a.latitude)
    return sqrt(x * x + y * y) * 6_371_000.0
}

// Gli orari si salvano come minuti dall'epoca (locale): un Long entra nello stato salvato senza altro.
private fun minuteToDateTime(minute: Long): LocalDateTime = LocalDateTime.ofEpochSecond(minute * 60, 0, ZoneOffset.UTC)

private fun dateTimeToMinute(time: LocalDateTime): Long = time.toEpochSecond(ZoneOffset.UTC) / 60
