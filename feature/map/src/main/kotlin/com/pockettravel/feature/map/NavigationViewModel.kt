package com.pockettravel.feature.map

import kotlinx.coroutines.flow.mapLatest
import com.pockettravel.core.data.NationalityPreferences
import android.content.Context
import android.util.Log
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Guida passo passo dentro la scheda Navigatore: "Avvia" chiama [start] con la meta e il mezzo
 * scelti, "Termina" (o l'arrivo, chiuso dal Navigatore) [stop]. Il GPS si ascolta solo mentre si naviga.
 * Meta, mezzo e ora di arrivo stanno in [SavedStateHandle]: se il sistema chiude l'app, tornando il
 * Navigatore chiede se riprendere la guida ([resumeOffer]) invece di ripartire da solo.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val routeEngineFactory: RouteEngineFactory,
    private val regionRepository: RegionRepository,
    private val gps: GpsLocationSource,
    /** Per la mappa della guida: lo stesso stile della scheda Mappa, con una sorgente per regione. */
    val tileSource: OfflineTileSource,
    private val usageModePreferences: UsageModePreferences,
    /** Ponte con il servizio in primo piano e la sua notifica (vedi [NavigationService]). */
    private val session: NavigationSession,
    private val navigationPreferences: NavigationPreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    nationalityPreferences: NationalityPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    // La regione da cui si e' partiti (la scheda aperta) e la meta; null = non si sta navigando.
    private var regionId: String = savedStateHandle[KEY_REGION] ?: ""
    private val destination = MutableStateFlow<NavigationPlace?>(null)
    /** La meta della guida in corso, null se non si sta navigando. */
    val target: StateFlow<NavigationPlace?> = destination.asStateFlow()
    // Guida interrotta: dallo stato salvato da Android (app riaperta dalle recenti) o dal disco (riaperta dall'icona).
    private val savedGuidance = navigationPreferences.activeGuidance()
    private val _resumeOffer = MutableStateFlow(savedStateHandle.get<Bundle>(KEY_DESTINATION)?.toPlace() ?: savedGuidance?.place)
    /** Guida interrotta perche' il sistema ha chiuso l'app: il Navigatore chiede se riprenderla, null altrimenti. */
    val resumeOffer: StateFlow<NavigationPlace?> = _resumeOffer.asStateFlow()

    /** Vibrazione alle svolte e all'arrivo nella guida a piedi (Impostazioni). */
    val walkingHaptics: StateFlow<Boolean> = navigationPreferences.walkingHaptics
    private val arrival = MutableStateFlow(savedStateHandle.get<Long>(KEY_ARRIVE_BY)?.let { LocalDateTime.ofEpochSecond(it * 60, 0, ZoneOffset.UTC) })
    /** L'ora a cui si vuole arrivare ("Arriva alle…" del Navigatore), null se non scelta: la guida mostra il ritardo. */
    val arriveBy: StateFlow<LocalDateTime?> = arrival.asStateFlow()

    // Regioni del percorso in corso (navigationRegionIds), aggiornate a ogni calcolo: la mappa ha una sorgente per ognuna.
    private val _regionIds = MutableStateFlow(listOfNotNull(regionId.takeIf { it.isNotEmpty() }))
    val regionIds: StateFlow<List<String>> = _regionIds

    /**
     * Il lato di guida da ricordare in auto: quello dei paesi del percorso, se diverso da quello del paese di
     * chi viaggia (nazionalita'); null se e' lo stesso.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val drivingSideWarning: StateFlow<DrivingSide?> = combine(_regionIds, nationalityPreferences.nationality, ::Pair)
        .mapLatest { (ids, home) ->
            val countries = withContext(Dispatchers.IO) { ids.mapNotNull { regionRepository.installed(it)?.countryCode } }
            drivingSideWarning(countries, home)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val permissionGranted = MutableStateFlow(gps.hasPermission())
    private val gpsEnabled = gps.gpsEnabled().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val lastFix = MutableStateFlow<GpsFix?>(null)

    private val calculating = MutableStateFlow(false)
    private val calculationStartedMillis = MutableStateFlow(0L)
    private val calculationProgress = MutableStateFlow(0.0)
    private var calculationJob: Job? = null
    private val _travelMode = MutableStateFlow(
        savedStateHandle.get<String>(KEY_MODE)?.let(TravelMode::valueOf) ?: TravelMode.from(usageModePreferences.mode.value),
    )
    val travelMode: StateFlow<TravelMode> = _travelMode
    private val routeResult = MutableStateFlow<RouteResult?>(null)
    private val arrived = MutableStateFlow(false)
    private val _arrivedAtMillis = MutableStateFlow<Long?>(null)
    /** Quando e' stata raggiunta la meta, null se non ancora: chi chiude la guida distingue un arrivo appena avvenuto da uno di cui nessuno si e' accorto. */
    val arrivedAtMillis: StateFlow<Long?> = _arrivedAtMillis.asStateFlow()
    private var lastCalculationMillis = 0L

    // Ticchetta ogni secondo: una posizione che invecchia deve far passare a "segnale perso"
    // anche se il GPS non ne manda di nuove.
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val uiState: StateFlow<NavigationUiState> = combine(
        combine(permissionGranted, gpsEnabled, lastFix, ::Triple),
        combine(calculating, calculationStartedMillis, calculationProgress, routeResult, arrived) { isCalculating, started, progress, result, hasArrived ->
            CalculationInputs(isCalculating, started, progress, result, hasArrived)
        },
        clock,
    ) { (permission, enabled, fix), calc, now ->
        navigationUiState(permission, enabled, fix, now, calc.calculating, calc.result, calc.arrived, calc.startedMillis, calc.progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationUiState.WaitingForFix)

    // Strade del percorso in corso, per punto della svolta: le trova la mappa della guida, che le passa a onStreetNames.
    private val streetNames = mutableMapOf<Int, String>()
    private var lastProgress: Pair<Route, NavigationProgress>? = null

    init {
        // Posizioni raccolte solo navigando, con permesso e GPS acceso: spegnendo il GPS il flusso si
        // chiude e la guida si ferma finche' non torna. Arrivati il GPS si spegne subito, senza aspettare
        // che la scheda chiuda la guida.
        @OptIn(ExperimentalCoroutinesApi::class)
        combine(permissionGranted, gpsEnabled, destination, arrived) { permission, enabled, target, hasArrived ->
            permission && enabled && target != null && !hasArrived
        }
            .flatMapLatest { active -> if (active) gps.fixes() else emptyFlow() }
            .onEach(::onFix)
            .launchIn(viewModelScope)
        // "Termina" dalla notifica, anche con l'app in secondo piano.
        session.stopRequests.onEach { if (destination.value != null) stop() }.launchIn(viewModelScope)
    }

    /** "Riprendi": la guida interrotta riparte con la stessa meta, mezzo e ora di arrivo. */
    fun resume() {
        val place = _resumeOffer.value ?: return
        _resumeOffer.value = null
        // Dal disco: regione, mezzo e ora di arrivo vengono da li' se lo stato di Android non c'e'.
        val fromDisk = savedGuidance?.takeIf { savedStateHandle.get<Bundle>(KEY_DESTINATION) == null }
        start(
            regionId = fromDisk?.regionId ?: regionId,
            place = place,
            mode = fromDisk?.mode ?: _travelMode.value,
            arriveBy = fromDisk?.arriveByEpochMinute?.let { LocalDateTime.ofEpochSecond(it * 60, 0, ZoneOffset.UTC) } ?: arrival.value,
        )
    }

    /** "No": la guida interrotta si dimentica. */
    fun dismissResume() {
        _resumeOffer.value = null
        arrival.value = null
        savedStateHandle.remove<Bundle>(KEY_DESTINATION)
        navigationPreferences.clearActiveGuidance()
    }

    // Il servizio in primo piano parte con la guida (dal primo piano: da sfondo Android 12+ non puo'): senza,
    // a schermo spento le posizioni verrebbero limitate. Il permesso di posizione serve gia' per partire (da
    // Android 14 senza il servizio non parte), se manca arriva da onPermissionResult.
    private fun beginSession(place: NavigationPlace) {
        session.update(NavigationSnapshot(place.name, null, 0.0, 0.0, 0.0, waitingForFix = true))
        if (gps.hasPermission()) NavigationService.start(context)
    }

    /** Notifica della guida toccata con l'app aperta: chi mostra l'hub porta in primo piano il Navigatore. */
    val openNavigatorRequests: SharedFlow<Unit> = session.openRequests

    /** Nomi di strada trovati dalla mappa della guida, per la notifica (accumulati, come nella guida a schermo). */
    fun onStreetNames(found: Map<Int, String>) {
        streetNames += found
        lastProgress?.let { (route, progress) -> publish(route, progress) }
    }

    private fun publish(route: Route, progress: NavigationProgress) {
        val place = destination.value ?: return
        lastProgress = route to progress
        session.update(
            NavigationSnapshot(
                destinationName = place.name,
                turn = progress.nextInstruction,
                distanceToNextMeters = progress.distanceToNextMeters,
                remainingMeters = progress.remainingMeters,
                remainingSeconds = remainingSeconds(route, progress.remainingMeters),
                street = streetNames[progress.nextInstruction.pointIndex],
            ),
        )
    }

    /** Parte la guida verso [place] con il mezzo scelto nel Navigatore: il percorso si ricalcola dalla posizione GPS. */
    fun start(regionId: String, place: NavigationPlace, mode: TravelMode, arriveBy: LocalDateTime? = null) {
        this.regionId = regionId
        _resumeOffer.value = null
        _regionIds.value = listOf(regionId)
        _travelMode.value = mode
        calculationJob?.cancel()
        calculating.value = false
        routeResult.value = null
        arrived.value = false
        _arrivedAtMillis.value = null
        lastFix.value = null
        permissionGranted.value = gps.hasPermission()
        arrival.value = arriveBy
        destination.value = place
        streetNames.clear()
        lastProgress = null
        beginSession(place)
        savedStateHandle[KEY_REGION] = regionId
        savedStateHandle[KEY_MODE] = mode.name
        savedStateHandle[KEY_ARRIVE_BY] = arriveBy?.let { it.toEpochSecond(ZoneOffset.UTC) / 60 }
        savedStateHandle[KEY_DESTINATION] = place.toBundle()
        navigationPreferences.saveActiveGuidance(
            ActiveGuidance(regionId, place, mode, arriveBy?.let { it.toEpochSecond(ZoneOffset.UTC) / 60 }, System.currentTimeMillis()),
        )
    }

    /** "Termina": la guida si chiude e il GPS non si ascolta piu'. */
    fun stop() {
        calculationJob?.cancel()
        calculating.value = false
        destination.value = null
        arrival.value = null
        routeResult.value = null
        arrived.value = false
        _arrivedAtMillis.value = null
        savedStateHandle.remove<Bundle>(KEY_DESTINATION)
        navigationPreferences.clearActiveGuidance()
        // Snapshot null: il servizio in primo piano si ferma da solo.
        session.clear()
        lastProgress = null
    }

    fun onPermissionResult(granted: Boolean) {
        permissionGranted.value = granted || gps.hasPermission()
        if (permissionGranted.value && destination.value != null) NavigationService.start(context)
    }

    // Activity chiusa (o hub chiuso): senza ViewModel nessuno ascolta il GPS, il servizio non ha piu' ragione di vivere.
    override fun onCleared() {
        if (destination.value != null) session.clear()
    }

    /** Dopo un errore: si ricalcola dall'ultima posizione. */
    fun retry() {
        if (calculating.value) return
        routeResult.value = null
        lastFix.value?.let { calculate(it) }
    }

    private fun onFix(fix: GpsFix) {
        lastFix.value = fix
        if (arrived.value) return
        when (val result = routeResult.value) {
            null -> if (!calculating.value) calculate(fix)
            is RouteResult.Found -> {
                val progress = NavigationTracker(result.route).progress(RoutePoint(fix.latitude, fix.longitude))
                if (progress.arrived && navigationPreferences.stopGpsOnArrival.value) {
                    _arrivedAtMillis.value = System.currentTimeMillis()
                    arrived.value = true
                    session.clear()
                    navigationPreferences.clearActiveGuidance()
                } else {
                    // Con "Spegni il GPS all'arrivo" tolto la guida resta aperta: l'arrivo si segna una volta sola
                    // (per l'avviso), poi si continua a seguire la posizione finche' non si chiude.
                    if (progress.arrived && _arrivedAtMillis.value == null) _arrivedAtMillis.value = System.currentTimeMillis()
                    publish(result.route, progress)
                    if (shouldRecalculate(progress, calculating.value, lastCalculationMillis, fix.timeMillis)) calculate(fix)
                }
            }
            else -> Unit
        }
    }

    // Un calcolo alla volta: fuori percorso non si interrompe quello in corso, il successivo parte
    // comunque dalla posizione piu' recente. Per questo il flag lo rimette a false solo il calcolo
    // ancora corrente.
    private fun calculate(from: GpsFix) {
        val target = destination.value ?: return
        if (calculating.value) return
        lastCalculationMillis = from.timeMillis
        calculationStartedMillis.value = System.currentTimeMillis()
        calculationProgress.value = 0.0
        calculating.value = true
        // Prima posizione arrivata: la notifica passa da "In attesa del segnale GPS…" a "Calcolo del percorso".
        if (routeResult.value !is RouteResult.Found) session.update(NavigationSnapshot(target.name, null, 0.0, 0.0, 0.0))
        val choice = routingChoice(_travelMode.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val start = RoutePoint(from.latitude, from.longitude)
                val regionIds = navigationRegionIds(regionId, routingRegions(), start, target.point)
                _regionIds.value = regionIds
                val result = routeEngineFactory.create(regionIds).route(start, target.point, choice.profile, choice.params) { progress ->
                    if (calculationJob === self) calculationProgress.value = progress
                }
                if (result is RouteResult.Found) logRouteForSimulation(result.route)
                // Un ricalcolo fuori percorso fallito (partenza in un parcheggio, tempo scaduto) non butta
                // via il percorso buono: si riprova al prossimo intervallo. "Riprova" azzera prima il
                // risultato, quindi li' arriva anche l'errore.
                if (result is RouteResult.Found || routeResult.value !is RouteResult.Found) routeResult.value = result
                // Un percorso nuovo ha altre svolte: i nomi trovati per il vecchio non valgono.
                if (result is RouteResult.Found) streetNames.clear()
            } finally {
                if (calculationJob === self) calculating.value = false
            }
        }
        calculationJob = job
        job.start()
    }

    // Regioni installate con i Percorsi utili al mezzo (usableRoutingRegions) e riquadro della loro mappa (127 byte di
    // header ognuna).
    private suspend fun routingRegions(): List<RoutingRegion> = withContext(Dispatchers.IO) {
        val regions = regionRepository.observeInstalled().first()
        val usable = usableRoutingRegions(
            regions.filter { it.routingVersion != null }.map { it.regionId }.toSet(),
            routingVariantPreferences.installedCarOnly.value,
            _travelMode.value,
        )
        regions
            .filter { it.regionId in usable }
            .map { RoutingRegion(it.regionId, tileSource.regionBounds(it.regionId)) }
    }

    private companion object {
        const val KEY_REGION = "regionId"
        const val KEY_DESTINATION = "destination"
        const val KEY_MODE = "mode"
        const val KEY_ARRIVE_BY = "arriveBy"
    }

    private data class CalculationInputs(val calculating: Boolean, val startedMillis: Long, val progress: Double, val result: RouteResult?, val arrived: Boolean)
}

/**
 * Solo nelle build di debug: i punti del percorso nel log (tag [SIMULATION_LOG_TAG]), letti dallo script
 * che sull'emulatore muove il GPS lungo il percorso con "adb emu geo fix" per provare la guida.
 */
private fun logRouteForSimulation(route: Route) {
    if (!BuildConfig.DEBUG) return
    val points = route.points.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.ROOT, it.longitude, it.latitude) }
    Log.d(SIMULATION_LOG_TAG, "route ${route.distanceMeters.toInt()} ${route.durationSeconds.toInt()} $points")
}

internal const val SIMULATION_LOG_TAG = "PocketTravelRoute"
