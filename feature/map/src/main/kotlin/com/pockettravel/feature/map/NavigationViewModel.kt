package com.pockettravel.feature.map

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.RegionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Navigazione passo passo dentro la scheda Navigatore: "Avvia" chiama [start] con la destinazione e il modo
 * scelti, "Termina" (o l'arrivo, chiuso dal Navigatore) [stop]. GPS, tracker, ricalcolo e voce stanno nel
 * [NavigationController], che continua con l'app chiusa: questo ViewModel ne mostra lo stato e la ritrova riaprendo l'app.
 * Destinazione, modo e ora di arrivo stanno anche in [SavedStateHandle]: se il sistema chiude il processo, tornando il
 * Navigatore chiede se riprendere la navigazione ([NavigationUiState.resumeOffer]) invece di ripartire da solo.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val controller: NavigationController,
    private val regionRepository: RegionRepository,
    private val gps: GpsLocationSource,
    /** Per la mappa della navigazione: lo stesso stile della scheda Mappa, con una sorgente per regione. */
    val tileSource: OfflineTileSource,
    /** Ponte con il servizio in primo piano e la sua notifica (vedi [NavigationService]). */
    session: NavigationSession,
    private val navigationPreferences: NavigationPreferences,
    nationalityPreferences: NationalityPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    clock: Clock,
) : ViewModel() {
    // La regione da cui si e' partiti (la scheda aperta), anche per riprendere una navigazione interrotta.
    private val regionId: String get() = controller.regionId.ifEmpty { savedStateHandle[KEY_REGION] ?: "" }

    /**
     * Se una navigazione e' in corso. Per chi reagisce a un evento (la notifica toccata) senza osservare lo stato: lo stato
     * di schermata si aggiorna solo mentre qualcuno lo raccoglie.
     */
    val isNavigating: Boolean get() = controller.isNavigating

    // Navigazione interrotta: dallo stato salvato da Android (app riaperta dalle recenti) o dal disco (riaperta dall'icona).
    // Nessuna proposta se la navigazione e' ancora in corso (app chiusa con il servizio vivo, poi riaperta).
    private val savedNavigation = if (controller.isNavigating) null else navigationPreferences.activeNavigation(clock.millis())
    private val resumeOffer = MutableStateFlow(if (controller.isNavigating) null else savedStateHandle.savedPlace(KEY_DESTINATION) ?: savedNavigation?.place)
    private val savedArrival = MutableStateFlow(savedStateHandle.get<Long>(KEY_ARRIVE_BY)?.let { LocalDateTime.ofEpochSecond(it * SECONDS_PER_MINUTE, 0, ZoneOffset.UTC) })
    private val savedMode = savedStateHandle.get<String>(KEY_MODE)?.let(RouteProfile::valueOf)

    /**
     * Il lato di guida da ricordare in auto: quello delle nazioni del percorso, se diverso da quello del paese di
     * chi viaggia (nazionalita'); null se e' lo stesso.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val drivingSideWarning: StateFlow<DrivingSide?> = combine(controller.regionIds, nationalityPreferences.nationality, ::Pair)
        .mapLatest { (ids, home) ->
            val countries = withContext(ioDispatcher) { ids.mapNotNull { regionRepository.installed(it)?.countryCode } }
            drivingSideWarning(countries, home)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Ticchetta ogni secondo, sull'orologio monotono delle posizioni (GpsFix.timeMillis): una posizione che invecchia deve far passare a "segnale perso"
    // anche se il GPS non ne manda di nuove.
    private val ticks = flow {
        while (true) {
            emit(gps.elapsedRealtimeMillis())
            delay(1_000)
        }
    }

    private val navigationStatus: StateFlow<NavigationStatus> = combine(
        combine(controller.permissionGranted, controller.gpsEnabled, controller.lastFix, ::Triple),
        combine(controller.calculating, controller.calculationStartedMillis, controller.calculationProgress, controller.routeResult, controller.arrived) { isCalculating, started, progress, result, hasArrived ->
            CalculationInputs(isCalculating, started, progress, result, hasArrived)
        },
        ticks,
    ) { (permission, enabled, fix), calc, now ->
        navigationUiState(permission, enabled, fix, now, calc.calculating, calc.result, calc.arrived, calc.startedMillis, calc.progress, controller::trackerFor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NavigationStatus.WaitingForFix)

    /** Tutto quello che la scheda Navigatore legge della navigazione, in un solo stato. */
    val uiState: StateFlow<NavigationUiState> = combine(
        combine(
            navigationStatus, controller.destination, resumeOffer, controller.routeProfile,
            combine(navigationPreferences.voiceGuidance, controller.voiceUnavailable, ::Pair),
        ) { status, target, offer, mode, (voice, noVoice) ->
            NavigationUiState(status = status, target = target, resumeOffer = offer, routeProfile = if (offer != null) savedMode ?: mode else mode, voiceGuidance = voice, voiceUnavailable = noVoice)
        },
        combine(controller.arrival, controller.destination, savedArrival) { arriveBy, target, saved -> if (target != null) arriveBy else saved },
        controller.regionIds, drivingSideWarning, controller.arrivedAtMillis,
    ) { state, arriveBy, ids, drivingSide, arrivedAt ->
        state.copy(arriveBy = arriveBy, regionIds = regionIdsOrTab(ids), drivingSideWarning = drivingSide, arrivedAtMillis = arrivedAt)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NavigationUiState(
            status = NavigationStatus.WaitingForFix,
            target = controller.destination.value,
            resumeOffer = resumeOffer.value,
            routeProfile = if (resumeOffer.value != null) savedMode ?: controller.routeProfile.value else controller.routeProfile.value,
            voiceGuidance = navigationPreferences.voiceGuidance.value,
            arriveBy = if (controller.isNavigating) controller.arrival.value else savedArrival.value,
            regionIds = regionIdsOrTab(controller.regionIds.value),
        ),
    )

    init {
        controller.onPermissionResult()
    }

    // Senza navigazione in corso la mappa usa la regione della scheda.
    private fun regionIdsOrTab(ids: List<String>): List<String> = ids.ifEmpty { listOfNotNull(regionId.takeIf { it.isNotEmpty() }) }

    /** Il pulsante della voce durante la navigazione: lo stesso interruttore delle Impostazioni. */
    fun setVoiceGuidance(enabled: Boolean) = navigationPreferences.setVoiceGuidance(enabled)

    /** "Riprendi": la navigazione interrotta riparte con la stessa destinazione, modo e ora di arrivo. */
    fun resume() {
        val place = resumeOffer.value ?: return
        resumeOffer.value = null
        // Dal disco: regione, modo e ora di arrivo vengono da li' se lo stato di Android non c'e'.
        val fromDisk = savedNavigation?.takeIf { savedStateHandle.savedPlace(KEY_DESTINATION) == null }
        start(
            regionId = fromDisk?.regionId ?: regionId,
            place = place,
            mode = fromDisk?.mode ?: savedMode ?: controller.routeProfile.value,
            arriveBy = fromDisk?.arriveByEpochMinute?.let { LocalDateTime.ofEpochSecond(it * SECONDS_PER_MINUTE, 0, ZoneOffset.UTC) } ?: savedArrival.value,
        )
    }

    /** "No": la navigazione interrotta si dimentica. */
    fun dismissResume() {
        resumeOffer.value = null
        savedStateHandle.remove<String>(KEY_DESTINATION)
        savedStateHandle.remove<Long>(KEY_ARRIVE_BY)
        savedArrival.value = null
        navigationPreferences.clearActiveNavigation()
    }

    /** Notifica della navigazione toccata con l'app aperta: chi mostra l'hub porta in primo piano il Navigatore. */
    val openNavigatorRequests: SharedFlow<Unit> = session.openRequests

    /** Nomi di strada trovati dalla mappa della navigazione, per la notifica (accumulati, come nella navigazione a schermo). */
    fun onStreetNames(found: Map<Int, String>) = controller.onStreetNames(found)

    /**
     * Parte la navigazione verso [place] con il modo scelto nel Navigatore: il percorso si ricalcola dalla posizione GPS.
     * Con [preview] (il percorso gia' calcolato nell'anteprima del percorso) si riparte da quello, se la prima posizione e' sul percorso.
     */
    fun start(regionId: String, place: NavigationPlace, mode: RouteProfile, arriveBy: LocalDateTime? = null, preview: PlannerPreview.Ready? = null) {
        resumeOffer.value = null
        controller.start(regionId, place, mode, arriveBy, preview)
        savedStateHandle[KEY_REGION] = regionId
        savedStateHandle[KEY_MODE] = mode.name
        savedStateHandle[KEY_ARRIVE_BY] = arriveBy?.let { it.toEpochSecond(ZoneOffset.UTC) / SECONDS_PER_MINUTE }
        savedStateHandle[KEY_DESTINATION] = place.toSavedState()
    }

    /** "Termina": la navigazione si chiude e il GPS non si ascolta piu'. */
    fun stop() {
        controller.stop()
        savedStateHandle.remove<String>(KEY_DESTINATION)
        savedStateHandle.remove<Long>(KEY_ARRIVE_BY)
        savedArrival.value = null
    }

    fun onPermissionResult(granted: Boolean) = controller.onPermissionResult(granted)

    /** Dopo un errore: si ricalcola dall'ultima posizione. */
    fun retry() = controller.retry()

    private companion object {
        const val KEY_REGION = "regionId"
        const val KEY_DESTINATION = "destination"
        const val KEY_MODE = "mode"
        const val KEY_ARRIVE_BY = "arriveBy"
        const val SECONDS_PER_MINUTE = 60
    }

    private data class CalculationInputs(val calculating: Boolean, val startedMillis: Long, val progress: Double, val result: RouteResult?, val arrived: Boolean)
}

/**
 * Lo stato della scheda Navigatore durante la navigazione. [target] e' la destinazione della navigazione in corso (null se non si sta
 * navigando); [resumeOffer] la navigazione interrotta perche' il sistema ha chiuso l'app, da riprendere o dimenticare;
 * [voiceGuidance] la guida vocale accesa e [voiceUnavailable] senza una voce installata nella lingua dell'app; [arriveBy] l'ora a cui si vuole
 * arrivare, per mostrare il ritardo; [regionIds] le regioni del percorso, una sorgente di mappa per ognuna;
 * [drivingSideWarning] il lato di guida da ricordare in auto; [arrivedAtMillis] quando e' stata raggiunta la destinazione, per
 * distinguere un arrivo appena avvenuto da uno di cui nessuno si e' accorto.
 */
data class NavigationUiState(
    val status: NavigationStatus,
    val target: NavigationPlace? = null,
    val resumeOffer: NavigationPlace? = null,
    val routeProfile: RouteProfile,
    val voiceGuidance: Boolean,
    val voiceUnavailable: Boolean = false,
    val arriveBy: LocalDateTime? = null,
    val regionIds: List<String> = emptyList(),
    val drivingSideWarning: DrivingSide? = null,
    val arrivedAtMillis: Long? = null,
)

/**
 * Solo nelle build di debug: i punti del percorso nel log (tag [SIMULATION_LOG_TAG]), letti dallo script
 * che sull'emulatore muove il GPS lungo il percorso con "adb emu geo fix" per provare la navigazione. Logcat tronca le righe
 * oltre circa 4 KB: i punti vanno su piu' righe, "route <metri> <secondi> <parte>/<parti> lon,lat;lon,lat;...".
 */
internal fun logRouteForSimulation(route: Route) {
    if (!BuildConfig.DEBUG) return
    for (line in routeLogLines(route)) Log.d(SIMULATION_LOG_TAG, line)
}

internal fun routeLogLines(route: Route): List<String> {
    val parts = route.points.chunked(SIMULATION_LOG_POINTS_PER_LINE)
    return parts.mapIndexed { index, part ->
        val points = part.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.ROOT, it.longitude, it.latitude) }
        "route ${route.distanceMeters.toInt()} ${route.durationSeconds.toInt()} ${index + 1}/${parts.size} $points"
    }
}

internal const val SIMULATION_LOG_TAG = "PocketTravelRoute"

// Un punto occupa al piu' 23 caratteri: 150 stanno in circa 3,5 KB.
private const val SIMULATION_LOG_POINTS_PER_LINE = 150
