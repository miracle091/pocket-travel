package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.FavoriteStop
import com.pockettravel.core.data.FavoriteStopsRepository
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.NearbyStop
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.TransitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Da dove sono cercate le fermate vicine: dalla posizione del telefono o dal centro della mappa. */
enum class NearbyOrigin { POSITION, MAP_CENTER }

/** Una fermata dell'elenco; [favorite] se e' tra quelle salvate. */
data class NearbyStopRow(val stop: NearbyStop, val favorite: Boolean)

/**
 * Lo stato dell'elenco "Fermate vicine": [rows] sono tutte le fermate (le salvate per prime), [mode] il filtro scelto
 * (null = tutti i mezzi), [favorites] le fermate salvate della regione, anche con l'elenco chiuso (le legge la stella del tabellone).
 */
data class NearbyStopsUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val origin: NearbyOrigin = NearbyOrigin.MAP_CENTER,
    val rows: List<NearbyStopRow> = emptyList(),
    val mode: TransitMode? = null,
    val favorites: List<FavoriteStop> = emptyList(),
) {
    /** I mezzi presenti tra le fermate, per i filtri. */
    val modes: List<TransitMode> get() = rows.flatMap { it.stop.modes }.distinct().sortedBy { it.ordinal }

    /** Le righe del mezzo scelto; tutte se quel mezzo non c'e' piu' dopo un aggiornamento. */
    val visibleRows: List<NearbyStopRow>
        get() = if (mode == null || mode !in modes) rows else rows.filter { mode in it.stop.modes }
}

/**
 * Le fermate dei mezzi pubblici vicino alla posizione del telefono (se e' dentro la mappa della regione) o al centro della
 * mappa, con le prossime partenze, riletta ogni minuto finche' l'elenco e' aperto. Le fermate salvate stanno in cima
 * anche se piu' lontane del raggio. Solo gli orari: il tempo reale e' nel tabellone di una fermata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NearbyStopsViewModel @Inject constructor(
    private val transitRepository: TransitRepository,
    private val favoriteStops: FavoriteStopsRepository,
    private val lastKnownPosition: LastKnownPosition,
    private val tileSource: OfflineTileSource,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow(NearbyStopsUi())
    val state: StateFlow<NearbyStopsUi> = _state.asStateFlow()

    private val regionId = MutableStateFlow<String?>(null)
    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            regionId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else favoriteStops.observe(id) }
                .collect { favorites -> _state.update { it.copy(favorites = favorites) } }
        }
    }

    /** La regione aperta nella scheda Mappa: un'altra regione chiude l'elenco. */
    fun setRegion(id: String) {
        if (regionId.value == id) return
        close()
        regionId.value = id
    }

    /** Apre l'elenco: cerca dalla posizione del telefono se e' dentro la mappa della regione, se no da ([centerLatitude], [centerLongitude]). */
    fun open(centerLatitude: Double, centerLongitude: Double) {
        val region = regionId.value ?: return
        listJob?.cancel()
        _state.update { it.copy(open = true, loading = true, rows = emptyList(), mode = null) }
        listJob = viewModelScope.launch {
            val bounds = withContext(ioDispatcher) { tileSource.regionBounds(region) }
            val position = lastKnownPosition.get(POSITION_MAX_AGE_MILLIS)?.takeIf { (lat, lon) -> bounds?.contains(lat, lon) == true }
            val (latitude, longitude) = position ?: (centerLatitude to centerLongitude)
            _state.update { it.copy(origin = if (position != null) NearbyOrigin.POSITION else NearbyOrigin.MAP_CENTER) }
            // Le fermate salvate cambiano (stella): si rilegge subito, poi ogni minuto.
            _state.map { it.favorites }.distinctUntilChanged().collectLatest { favorites ->
                while (true) {
                    val rows = load(region, latitude, longitude, favorites)
                    _state.update { it.copy(rows = rows, loading = false) }
                    delay(REFRESH_MILLIS)
                }
            }
        }
    }

    fun close() {
        listJob?.cancel()
        _state.update { it.copy(open = false, loading = false, rows = emptyList(), mode = null) }
    }

    fun setMode(mode: TransitMode?) {
        _state.update { it.copy(mode = mode) }
    }

    /** Salva la fermata, o la toglie dalle salvate se lo e' gia'. */
    fun toggleFavorite(name: String, latitude: Double, longitude: Double) {
        val region = regionId.value ?: return
        viewModelScope.launch {
            val saved = _state.value.favorites.firstOrNull { it.matches(name, latitude, longitude) }
            if (saved != null) favoriteStops.remove(region, saved) else favoriteStops.add(region, FavoriteStop(name, latitude, longitude))
        }
    }

    private suspend fun load(region: String, latitude: Double, longitude: Double, favorites: List<FavoriteStop>): List<NearbyStopRow> {
        val near = transitRepository.nearbyStops(region, latitude, longitude)
        val saved = favorites.mapNotNull { favorite ->
            val found = near.firstOrNull { favorite.matches(it.name, it.latitude, it.longitude) }
                ?: transitRepository.nearbyStops(region, favorite.latitude, favorite.longitude, radiusMeters = FAVORITE_RADIUS_M).let { around ->
                    around.firstOrNull { it.name == favorite.name } ?: around.firstOrNull()
                }
            // La distanza delle salvate piu' lontane e' misurata dalla fermata salvata: va rifatta da qui.
            found?.let { it.copy(distanceMeters = distanceMeters(latitude, longitude, it.latitude, it.longitude)) }
        }.distinctBy { it.name }.sortedBy { it.distanceMeters }
        val others = near.filter { stop -> saved.none { it.name == stop.name } }
        return saved.map { NearbyStopRow(it, favorite = true) } + others.map { NearbyStopRow(it, favorite = false) }
    }
}

/** La fermata salvata e' questa: stesso nome (senza badare alle maiuscole) e a non piu' di [FAVORITE_RADIUS_M]. */
internal fun FavoriteStop.matches(name: String, latitude: Double, longitude: Double): Boolean =
    this.name.equals(name, ignoreCase = true) && distanceMeters(this.latitude, this.longitude, latitude, longitude) <= FAVORITE_RADIUS_M

/** La fermata aperta (nome e posizione) e' tra le [favorites]. */
internal fun isFavoriteStop(favorites: List<FavoriteStop>, name: String, latitude: Double, longitude: Double): Boolean =
    favorites.any { it.matches(name, latitude, longitude) }

// Distanza sulla sfera (haversine): per le fermate salvate basta all'ordine del metro.
private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

// Una posizione piu' vecchia di cosi' potrebbe essere di un altro posto.
private const val POSITION_MAX_AGE_MILLIS = 5 * 60_000L

// Ogni quanto si rilegge l'elenco con il pannello aperto (le partenze passano).
private const val REFRESH_MILLIS = 60_000L

// Raggio della ricerca di una fermata salvata attorno alle sue coordinate, e tolleranza per riconoscerla.
private const val FAVORITE_RADIUS_M = 150.0
private const val EARTH_RADIUS_M = 6_371_000.0
