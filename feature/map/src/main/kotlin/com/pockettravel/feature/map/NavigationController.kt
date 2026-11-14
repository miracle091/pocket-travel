package com.pockettravel.feature.map

import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.speech.ReadAloudResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Lo scope della navigazione in corso: vive quanto il processo, non quanto l'activity. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NavigationScope

/**
 * La navigazione in corso: GPS, tracker, ricalcolo, voce e notifica. Singleton e non ViewModel, cosi' continua con
 * l'app chiusa dalle recenti finche' vive il servizio in primo piano ([NavigationService]), che la tiene in vita e la
 * chiude con "Termina". Il [NavigationViewModel] la avvia, la ferma e ne legge lo stato; un ViewModel nuovo (app
 * riaperta) ritrova la navigazione gia' in corso.
 */
@Singleton
class NavigationController @Inject constructor(
    private val routeEngineFactory: RouteEngineFactory,
    private val regionRepository: RegionRepository,
    private val gps: GpsLocationSource,
    private val tileSource: OfflineTileSource,
    private val usageModePreferences: UsageModePreferences,
    private val session: NavigationSession,
    private val navigationPreferences: NavigationPreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    private val serviceStarter: GuidanceServiceStarter,
    private val simulationLog: RouteSimulationLog,
    private val announcer: NavigationAnnouncer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    @NavigationScope private val scope: CoroutineScope,
) {
    // La regione da cui si e' partiti (la scheda aperta) e la destinazione; null = non si sta navigando.
    var regionId: String = ""
        private set
    private val _destination = MutableStateFlow<NavigationPlace?>(null)
    val destination: StateFlow<NavigationPlace?> = _destination.asStateFlow()
    private val _arrival = MutableStateFlow<LocalDateTime?>(null)
    val arrival: StateFlow<LocalDateTime?> = _arrival.asStateFlow()

    // "Raggiungi la fermata": la corsa da prendere; [arrival] e' la sua prima partenza che si fa ancora in tempo a prendere.
    private val _toCatch = MutableStateFlow<TripToCatch?>(null)
    val toCatch: StateFlow<TripToCatch?> = _toCatch.asStateFlow()
    private val _routeProfile = MutableStateFlow(RouteProfile.from(usageModePreferences.modes.value))
    val routeProfile: StateFlow<RouteProfile> = _routeProfile.asStateFlow()

    // Regioni del percorso in corso (navigationRegionIds), aggiornate a ogni calcolo: la mappa ha una sorgente per ognuna.
    private val _regionIds = MutableStateFlow<List<String>>(emptyList())
    val regionIds: StateFlow<List<String>> = _regionIds.asStateFlow()

    // Guida vocale accesa ma senza voce installata nella lingua dell'app: la scheda lo dice.
    private val _voiceUnavailable = MutableStateFlow(false)
    val voiceUnavailable: StateFlow<Boolean> = _voiceUnavailable.asStateFlow()

    private val _permissionGranted = MutableStateFlow(gps.hasPermission())
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    // Ascoltato solo navigando o con la scheda aperta: il singleton vive quanto il processo.
    val gpsEnabled: StateFlow<Boolean> = gps.gpsEnabled().stateIn(scope, SharingStarted.WhileSubscribed(), false)
    private val _lastFix = MutableStateFlow<GpsFix?>(null)
    val lastFix: StateFlow<GpsFix?> = _lastFix.asStateFlow()

    private val _calculating = MutableStateFlow(false)
    val calculating: StateFlow<Boolean> = _calculating.asStateFlow()
    private val _calculationStartedMillis = MutableStateFlow(0L)
    val calculationStartedMillis: StateFlow<Long> = _calculationStartedMillis.asStateFlow()
    private val _calculationProgress = MutableStateFlow(0.0)
    val calculationProgress: StateFlow<Double> = _calculationProgress.asStateFlow()
    private var calculationJob: Job? = null
    private val _routeResult = MutableStateFlow<RouteResult?>(null)
    val routeResult: StateFlow<RouteResult?> = _routeResult.asStateFlow()
    private val _arrived = MutableStateFlow(false)
    val arrived: StateFlow<Boolean> = _arrived.asStateFlow()
    private val _arrivedAtMillis = MutableStateFlow<Long?>(null)
    val arrivedAtMillis: StateFlow<Long?> = _arrivedAtMillis.asStateFlow()
    private var lastCalculationMillis = 0L

    /** Se una navigazione e' in corso. */
    val isNavigating: Boolean get() = _destination.value != null

    // Un tracker per percorso (costruirlo e' O(punti), e tiene la posizione piu' avanti raggiunta): lo usano sia
    // le posizioni GPS sia il calcolo dello stato ogni secondo. Il percorso si riconosce per identita'.
    private var tracker: NavigationTracker? = null
    private var trackerRoute: Route? = null

    fun trackerFor(route: Route): NavigationTracker =
        tracker?.takeIf { trackerRoute === route } ?: NavigationTracker(route, _routeProfile.value).also { tracker = it; trackerRoute = route }

    // Strade del percorso in corso, per punto della svolta: le trova la mappa della navigazione, che le passa a onStreetNames.
    private val streetNames = mutableMapOf<Int, String>()
    private var lastProgress: Pair<Route, NavigationProgress>? = null

    // L'anteprima del percorso del Navigatore da cui e' partita la navigazione: si usa alla prima posizione, se e' sul percorso.
    private var previewToUse: PlannerPreview.Ready? = null

    init {
        // Posizioni raccolte solo navigando, con permesso e GPS acceso: spegnendo il GPS il flusso si
        // chiude e la navigazione si ferma finche' non torna. Arrivati il GPS si spegne subito, senza aspettare
        // che la scheda chiuda la navigazione.
        @OptIn(ExperimentalCoroutinesApi::class)
        _destination
            .flatMapLatest { target ->
                if (target == null) flowOf(false) else combine(_permissionGranted, gpsEnabled, _arrived) { permission, enabled, hasArrived -> permission && enabled && !hasArrived }
            }
            .distinctUntilChanged()
            .flatMapLatest { active -> if (active) gps.fixes() else emptyFlow() }
            .onEach(::onFix)
            .launchIn(scope)
        // "Termina" dalla notifica, anche con l'app chiusa.
        session.stopRequests.onEach { if (_destination.value != null) stop() }.launchIn(scope)
        // La voce resta accesa per tutta la navigazione con "Guida vocale" attiva: accenderla a ogni frase costerebbe tempo.
        @OptIn(ExperimentalCoroutinesApi::class)
        combine(_destination, navigationPreferences.voiceGuidance) { target, voice -> target != null && voice }
            .distinctUntilChanged()
            .mapLatest { on ->
                if (on) {
                    _voiceUnavailable.value = announcer.startVoice() != ReadAloudResult.FINISHED
                } else {
                    announcer.stopVoice()
                    _voiceUnavailable.value = false
                }
            }
            .launchIn(scope)
    }

    /**
     * Parte la navigazione verso [place] con il modo scelto nel Navigatore: il percorso si ricalcola dalla posizione GPS.
     * Con [preview] (il percorso gia' calcolato nell'anteprima del percorso) si riparte da quello, se la prima posizione e' sul percorso.
     * Con [toCatch] l'ora di arrivo segue le partenze della corsa: persa una, passa alla successiva.
     */
    fun start(
        regionId: String,
        place: NavigationPlace,
        mode: RouteProfile,
        arriveBy: LocalDateTime?,
        preview: PlannerPreview.Ready?,
        toCatch: TripToCatch? = null,
    ) {
        this.regionId = regionId
        _regionIds.value = preview?.regionIds ?: listOf(regionId)
        previewToUse = preview
        // Anche la stessa anteprima del percorso riavviata riparte da capo: il tracker ricorda quanto si e' avanzati.
        tracker = null
        trackerRoute = null
        _routeProfile.value = mode
        calculationJob?.cancel()
        _calculating.value = false
        _routeResult.value = null
        _arrived.value = false
        _arrivedAtMillis.value = null
        _lastFix.value = null
        _permissionGranted.value = gps.hasPermission()
        _arrival.value = arriveBy
        _toCatch.value = toCatch
        _destination.value = place
        streetNames.clear()
        lastProgress = null
        announcer.begin(mode)
        beginSession(place)
        navigationPreferences.saveActiveNavigation(
            ActiveNavigation(regionId, place, mode, arriveBy?.let { it.toEpochSecond(ZoneOffset.UTC) / SECONDS_PER_MINUTE }, clock.millis()),
        )
    }

    /** "Termina": la navigazione si chiude e il GPS non si ascolta piu'. */
    fun stop() {
        calculationJob?.cancel()
        _calculating.value = false
        previewToUse = null
        _destination.value = null
        _arrival.value = null
        _toCatch.value = null
        _routeResult.value = null
        _arrived.value = false
        _arrivedAtMillis.value = null
        navigationPreferences.clearActiveNavigation()
        // Snapshot null: il servizio in primo piano si ferma da solo.
        session.clear()
        lastProgress = null
        announcer.end()
    }

    /**
     * Risposta alla richiesta di permesso; senza [granted] lo rilegge soltanto, perche' puo' cambiare nelle impostazioni
     * di sistema mentre il singleton vive (scheda riaperta). Riavviare il servizio gia' attivo non cambia nulla.
     */
    fun onPermissionResult(granted: Boolean = false) {
        _permissionGranted.value = granted || gps.hasPermission()
        if (_permissionGranted.value && _destination.value != null) serviceStarter.start()
    }

    /** Dopo un errore: si ricalcola dall'ultima posizione. */
    fun retry() {
        if (_calculating.value) return
        _routeResult.value = null
        _lastFix.value?.let { calculate(it) }
    }

    /** Nomi di strada trovati dalla mappa della navigazione, per la notifica (accumulati, come nella navigazione a schermo). */
    fun onStreetNames(found: Map<Int, String>) {
        streetNames += found
        lastProgress?.let { (route, progress) -> publish(route, progress) }
    }

    // Il servizio in primo piano parte con la navigazione (dal primo piano: da sfondo Android 12+ non puo'): senza,
    // a schermo spento le posizioni verrebbero limitate e chiusa l'app la navigazione finirebbe. Il permesso di
    // posizione serve gia' per partire (da Android 14 senza il servizio non parte), se manca arriva da onPermissionResult.
    private fun beginSession(place: NavigationPlace) {
        session.update(NavigationSnapshot(place.name, null, 0.0, 0.0, 0.0, waitingForFix = true))
        if (gps.hasPermission()) serviceStarter.start()
    }

    private fun publish(route: Route, progress: NavigationProgress) {
        val place = _destination.value ?: return
        lastProgress = route to progress
        session.update(
            NavigationSnapshot(
                destinationName = place.name,
                turn = progress.nextInstruction,
                distanceToNextMeters = progress.distanceToNextMeters,
                remainingMeters = progress.remainingMeters,
                remainingSeconds = progress.remainingSeconds,
                street = streetNames[progress.nextInstruction.pointIndex],
            ),
        )
        // "Raggiungi la fermata": persa la partenza scelta, l'ora di arrivo passa alla successiva che si prende, e la voce
        // lo dice. Se nessuna, resta quella e il Navigatore mostra il ritardo.
        val current = _arrival.value ?: return
        val departures = _toCatch.value?.departures.takeUnless { progress.arrived }
        nextDeparture(departures, current, progress.remainingSeconds, LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()))?.let { next ->
            _arrival.value = next
            announcer.onMissedDeparture(current, next)
        }
    }

    private fun onFix(fix: GpsFix) {
        _lastFix.value = fix
        if (_arrived.value) return
        when (val result = _routeResult.value) {
            null -> if (!_calculating.value) {
                // "Avvia" dall'anteprima del percorso: il percorso c'e' gia', si ricalcola solo se la posizione e' fuori percorso.
                val preview = previewToUse?.takeIf { !trackerFor(it.route).progress(RoutePoint(fix.latitude, fix.longitude), fix.timeMillis).offRoute }
                previewToUse = null
                if (preview != null) {
                    simulationLog.log(preview.route)
                    _routeResult.value = RouteResult.Found(preview.route)
                    onFix(fix)
                } else {
                    calculate(fix)
                }
            }
            is RouteResult.Found -> {
                val progress = trackerFor(result.route).progress(RoutePoint(fix.latitude, fix.longitude), fix.timeMillis)
                announcer.onProgress(result.route, progress, streetNames[progress.nextInstruction.pointIndex], fix.timeMillis)
                if (progress.arrived && navigationPreferences.stopGpsOnArrival.value) {
                    _arrivedAtMillis.value = clock.millis()
                    _arrived.value = true
                    // Detto l'arrivo la voce non serve piu', anche se la scheda chiude la navigazione solo quando torna visibile.
                    announcer.stopVoice()
                    session.clear()
                    navigationPreferences.clearActiveNavigation()
                } else {
                    // Con "Spegni il GPS all'arrivo" tolto la navigazione resta aperta: l'arrivo si segna una volta sola
                    // (per l'avviso), poi si continua a seguire la posizione finche' non si chiude.
                    if (progress.arrived && _arrivedAtMillis.value == null) _arrivedAtMillis.value = clock.millis()
                    publish(result.route, progress)
                    if (shouldRecalculate(progress, _calculating.value, lastCalculationMillis, fix.timeMillis, fix.accuracyMeters)) calculate(fix)
                }
            }
            else -> Unit
        }
    }

    // Un calcolo alla volta: fuori percorso non si interrompe quello in corso, il successivo parte
    // comunque dalla posizione piu' recente. Per questo il flag lo rimette a false solo il calcolo
    // ancora corrente.
    private fun calculate(from: GpsFix) {
        val target = _destination.value ?: return
        if (_calculating.value) return
        lastCalculationMillis = from.timeMillis
        _calculationStartedMillis.value = gps.elapsedRealtimeMillis()
        _calculationProgress.value = 0.0
        _calculating.value = true
        // Prima posizione arrivata: la notifica passa da "In attesa del segnale GPS…" a "Calcolo del percorso".
        if (_routeResult.value !is RouteResult.Found) session.update(NavigationSnapshot(target.name, null, 0.0, 0.0, 0.0))
        val choice = brouterProfile(_routeProfile.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val start = RoutePoint(from.latitude, from.longitude)
                val candidates = routingRegions()
                val regionIds = navigationRegionIds(regionId, candidates, start, target.point)
                _regionIds.value = regionIds
                // Fuori dalle regioni con la rete stradale (un ricalcolo dopo una deviazione oltre il confine) BRouter
                // cercherebbe fino al tempo massimo: "manca la rete stradale" subito, come nell'anteprima.
                val result = if (leavesRoutingRegions(candidates, start, target.point)) {
                    RouteResult.NoRoutingData
                } else {
                    routeEngineFactory.create(regionIds).route(start, target.point, choice.profile, choice.params) { progress ->
                        // Annullato ma non ancora sostituito: un avanzamento in ritardo non sporca quello del calcolo nuovo.
                        if (calculationJob === self && self?.isActive == true) _calculationProgress.value = progress
                    }
                }
                if (result is RouteResult.Found) simulationLog.log(result.route)
                // Un ricalcolo fuori percorso fallito (partenza in un parcheggio, tempo scaduto) non butta
                // via il percorso buono: si riprova al prossimo intervallo. "Riprova" azzera prima il
                // risultato, quindi li' arriva anche l'errore.
                if (result is RouteResult.Found || _routeResult.value !is RouteResult.Found) _routeResult.value = result
                if (result is RouteResult.Found) {
                    // Un percorso nuovo ha altre svolte: i nomi trovati per il vecchio non valgono.
                    streetNames.clear()
                    // La notifica mostra subito la prima svolta, invece di "Calcolo del percorso" fino alla posizione successiva.
                    val fix = _lastFix.value ?: from
                    publish(result.route, trackerFor(result.route).progress(RoutePoint(fix.latitude, fix.longitude), fix.timeMillis))
                }
            } finally {
                if (calculationJob === self) _calculating.value = false
            }
        }
        calculationJob = job
        job.start()
    }

    // Regioni installate con la rete stradale utile al modo (usableRoutingRegions) e rettangolo della loro mappa (127 byte di
    // header ognuna).
    private suspend fun routingRegions(): List<RoutingRegion> = withContext(ioDispatcher) {
        val regions = regionRepository.observeInstalled().first()
        val usable = usableRoutingRegions(
            regions.filter { it.routingVersion != null }.map { it.regionId }.toSet(),
            routingVariantPreferences.installedCarOnly.value,
            _routeProfile.value,
        )
        regions
            .filter { it.regionId in usable }
            .map { RoutingRegion(it.regionId, tileSource.regionBounds(it.regionId)) }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}
