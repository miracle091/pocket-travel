package com.pockettravel.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.TransportCount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.LocalDateTime

/** Dispatcher principale dei test dei ViewModel: `viewModelScope` e `runTest` condividono lo stesso orologio virtuale. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Tiene acceso per tutto il test uno stato `WhileSubscribed`: senza chi lo raccoglie il suo `value` non si aggiorna. */
fun TestScope.keepCollecting(state: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { } }
}

/** Il valore di uno stato dopo che le coroutine pronte hanno finito: gli stati combinati arrivano con qualche passaggio di ritardo. */
fun <T> TestScope.settled(state: StateFlow<T>): T {
    testScheduler.runCurrent()
    return state.value
}

/** Un ViewModel dentro uno `ViewModelStore`: [clear] lo chiude come fa il sistema (`onCleared`, `viewModelScope` annullato). */
class ViewModelHost<T : ViewModel>(private val modelClass: Class<T>, private val create: () -> T) {
    private val store = ViewModelStore()
    val viewModel: T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        },
    )[modelClass]

    fun clear() = store.clear()
}

fun installedRegion(
    id: String,
    name: String = id,
    routing: Boolean = true,
    map: Boolean = true,
    countryCode: String? = null,
) = InstalledRegion(
    regionId = id, displayName = name, countryCode = countryCode,
    mapVersion = if (map) "m1" else null, routingVersion = if (routing) "r1" else null,
    poiVersion = "p1", poiExtraVersion = null, addressesVersion = null,
    poiSizeBytes = 1L, poiExtraSizeBytes = null, sizeBytes = 1L,
)

fun poiRow(
    id: Long,
    name: String,
    regionId: String = "r1",
    category: String = "restaurant",
    osmTag: String = "amenity=restaurant",
    lat: Double = 45.5,
    lon: Double = 9.5,
    extra: Boolean = false,
    nameIt: String? = null,
    nameEn: String? = null,
) = PoiEntity(
    id = id, regionId = regionId, name = name, category = category, lat = lat, lon = lon, osmTag = osmTag,
    nameIt = nameIt, nameEn = nameEn, extra = extra,
)

/** [PoiDao] in memoria: filtra [rows] per regione, riquadro e categorie escluse, e registra le richieste. */
class FakePoiDao : PoiDao {
    class InBoundsCall(
        val regionId: String, val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double,
        val excluded: List<String>, val accessibility: Int, val limit: Int,
    )

    val rows = mutableListOf<PoiEntity>()
    val inBoundsCalls = mutableListOf<InBoundsCall>()
    var categoryTagReads = 0
        private set

    /** Se c'e' un blocco per la regione, `poisInBounds` aspetta che lo si sblocchi. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val searches = mutableListOf<Pair<List<String>, String>>()
    val nearestRows = mutableListOf<PoiEntity>()

    override suspend fun poisInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        excluded: List<String>, accessibility: Int, limit: Int,
    ): List<PoiEntity> {
        inBoundsCalls += InBoundsCall(regionId, minLat, maxLat, minLon, maxLon, excluded, accessibility, limit)
        gates[regionId]?.await()
        return rows.filter {
            it.regionId == regionId && it.lat in minLat..maxLat && it.lon in minLon..maxLon && "${it.category}|${it.osmTag}" !in excluded
        }.take(limit)
    }

    override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> {
        searches += regionIds to pattern
        val matcher = globToRegex(pattern)
        return rows.filter { poi ->
            poi.regionId in regionIds && !poi.extra && listOfNotNull(poi.name, poi.nameIt, poi.nameEn).any { matcher.matches(it) }
        }.take(limit)
    }

    override suspend fun nearest(
        regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
    ): List<PoiEntity> = nearestRows.filter { it.regionId in regionIds }.take(limit)

    override suspend fun categoryTagsInRegion(regionId: String): List<CategoryTag> {
        categoryTagReads++
        return rows.filter { it.regionId == regionId }.map { CategoryTag(it.category, it.osmTag) }.distinct()
    }

    override suspend fun insertAll(pois: List<PoiRow>) = Unit
    override suspend fun poisForRegion(regionId: String): List<PoiEntity> = rows.filter { it.regionId == regionId }
    override suspend fun spreadInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double,
        excluded: List<String>, accessibility: Int,
    ): List<PoiEntity> = emptyList()

    override suspend fun spreadInWideBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double,
        excluded: List<String>, accessibility: Int,
    ): List<PoiEntity> = emptyList()

    override suspend fun transportCounts(regionId: String): List<TransportCount> = emptyList()
    override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> = emptyList()
    override suspend fun deleteForRegion(regionId: String, extra: Boolean?) = Unit

    // GLOB di SQLite: '*' qualsiasi testo, '[...]' un carattere del gruppo.
    private fun globToRegex(glob: String): Regex {
        val out = StringBuilder()
        var inGroup = false
        for (c in glob) {
            when {
                inGroup -> if (c == ']') {
                    out.append(']')
                    inGroup = false
                } else {
                    out.append(if (c.isLetterOrDigit()) c.toString() else "\\" + c)
                }
                c == '[' -> { out.append('['); inGroup = true }
                c == '*' -> out.append(".*")
                else -> out.append(Regex.escape(c.toString()))
            }
        }
        return Regex(out.toString())
    }
}

/** [GpsLocationSource] pilotabile: il permesso e il GPS si cambiano da fuori, le posizioni si emettono con [emit]. */
class FakeGpsLocationSource(var permission: Boolean = true) : GpsLocationSource {
    val enabled = MutableStateFlow(true)
    private val fixes = MutableSharedFlow<GpsFix>(extraBufferCapacity = 16)

    /** Quanti raccolgono le posizioni in questo momento. */
    val listeners: Int get() = fixes.subscriptionCount.value

    override fun hasPermission(): Boolean = permission
    override fun gpsEnabled(): Flow<Boolean> = enabled
    override fun fixes(): Flow<GpsFix> = fixes

    suspend fun emit(fix: GpsFix) = fixes.emit(fix)
}

/** [OfflineTileSource] senza file: i riquadri e le sorgenti si impostano per regione. */
class FakeOfflineTileSource : OfflineTileSource {
    val bounds = mutableMapOf<String, MapBounds>()
    val kinds = mutableMapOf<String, MapSourceKind>()

    override fun styleJson(regionId: String, dark: Boolean, language: String, worldFallback: Boolean): String = "{}"
    override fun navigationStyleJson(regionIds: List<String>, dark: Boolean, language: String): String = "{}"
    override fun sourceKind(regionId: String): MapSourceKind = kinds[regionId] ?: MapSourceKind.NONE
    override fun regionBounds(regionId: String): MapBounds? = bounds[regionId]
}

/** [ConnectivityObserver] pilotabile: ogni cambio di [online] e' una notifica di rete. */
class FakeConnectivityObserver : ConnectivityObserver {
    val online = MutableStateFlow(true)

    override fun changes(): Flow<Boolean> = online
}

class RecordingGuidanceServiceStarter : GuidanceServiceStarter {
    var starts = 0
        private set

    override fun start() {
        starts++
    }
}

class FakeDepartureReminderScheduler : DepartureReminderScheduler {
    val scheduled = mutableListOf<Pair<LocalDateTime, String>>()
    var cancellations = 0
        private set

    override fun schedule(departure: LocalDateTime, destinationName: String) {
        scheduled += departure to destinationName
    }

    override fun cancel() {
        cancellations++
    }
}

class RecordingRouteSimulationLog : RouteSimulationLog {
    val routes = mutableListOf<Route>()

    override fun log(route: Route) {
        routes += route
    }
}

/**
 * [RouteEngineFactory] con risposte decise dal test: [results] in ordine (l'ultima si ripete), poi [defaultResult].
 * Registra le regioni di ogni motore creato e le richieste di percorso. Con [gate] il calcolo aspetta lo sblocco.
 */
class ScriptedRouteEngineFactory(var defaultResult: RouteResult = RouteResult.NotFound) : RouteEngineFactory {
    class Request(val from: RoutePoint, val to: RoutePoint, val profile: String?, val params: Map<String, String>)

    val results = ArrayDeque<RouteResult>()
    val created = mutableListOf<List<String>>()
    val requests = mutableListOf<Request>()
    var gate: CompletableDeferred<Unit>? = null

    /** Avanzamenti che il motore segnala prima di rispondere. */
    var progressSteps: List<Double> = emptyList()

    override suspend fun create(regionIds: List<String>): RouteEngine {
        created += regionIds
        return object : RouteEngine {
            override suspend fun route(
                from: RoutePoint, to: RoutePoint, profile: String?, profileParams: Map<String, String>, onProgress: (Double) -> Unit,
            ): RouteResult {
                requests += Request(from, to, profile, profileParams)
                progressSteps.forEach(onProgress)
                gate?.await()
                return if (results.size > 1) results.removeFirst() else results.firstOrNull() ?: defaultResult
            }
        }
    }
}
