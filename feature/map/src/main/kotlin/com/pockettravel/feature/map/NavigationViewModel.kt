package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    routeEngineFactory: RouteEngineFactory,
    private val gps: GpsLocationSource,
    /** Per la mappa della schermata: lo stesso stile della scheda Mappa. */
    val tileSource: OfflineTileSource,
    private val usageModePreferences: UsageModePreferences,
) : ViewModel() {
    val regionId: String = checkNotNull(savedStateHandle[ARG_REGION_ID])
    private val destination = RoutePoint(
        checkNotNull(savedStateHandle.get<String>(ARG_LATITUDE)).toDouble(),
        checkNotNull(savedStateHandle.get<String>(ARG_LONGITUDE)).toDouble(),
    )
    val destinationName: String = savedStateHandle[ARG_NAME] ?: ""

    private val engine by lazy { routeEngineFactory.create(regionId) }

    private val permissionGranted = MutableStateFlow(gps.hasPermission())
    private val gpsEnabled = gps.gpsEnabled().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val lastFix = MutableStateFlow<GpsFix?>(null)
    private val calculating = MutableStateFlow(false)
    private val calculationStartedMillis = MutableStateFlow(0L)
    private val calculationProgress = MutableStateFlow(0.0)
    private var calculationJob: Job? = null
    private val _travelMode = MutableStateFlow(TravelMode.from(usageModePreferences.mode.value))
    val travelMode: StateFlow<TravelMode> = _travelMode

    /** Profilo del percorso: a piedi con "Con disabilita'" quello in sedia a rotelle (routingChoice). */
    val routing: StateFlow<RoutingChoice> = combine(_travelMode, usageModePreferences.accessible, usageModePreferences.allowSteps, ::routingChoice)
        .stateIn(viewModelScope, SharingStarted.Eagerly, routingChoice(_travelMode.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value))
    val allowSteps: StateFlow<Boolean> = usageModePreferences.allowSteps
    private val routeResult = MutableStateFlow<RouteResult?>(null)
    private val arrived = MutableStateFlow(false)
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

    init {
        // Posizioni raccolte solo con permesso e GPS acceso: spegnendo il GPS il flusso si chiude e
        // la navigazione si ferma finche' non torna.
        @OptIn(ExperimentalCoroutinesApi::class)
        combine(permissionGranted, gpsEnabled) { permission, enabled -> permission && enabled }
            .flatMapLatest { active -> if (active) gps.fixes() else emptyFlow() }
            .onEach(::onFix)
            .launchIn(viewModelScope)
    }

    fun onPermissionResult(granted: Boolean) {
        permissionGranted.value = granted || gps.hasPermission()
    }

    /** Dopo un errore o con il pacchetto Percorsi appena scaricato. */
    fun retry() {
        if (calculating.value) return
        routeResult.value = null
        lastFix.value?.let { calculate(it) }
    }

    /** Cambia mezzo: il calcolo in corso (anche lungo, in auto) si annulla e riparte col nuovo profilo. */
    fun setTravelMode(mode: TravelMode) {
        if (mode == _travelMode.value) return
        _travelMode.value = mode
        restartCalculation()
    }

    /** "Accetto qualche gradino" (solo con il profilo in sedia a rotelle): ricalcola come un cambio di mezzo. */
    fun setAllowSteps(allow: Boolean) {
        if (allow == usageModePreferences.allowSteps.value) return
        usageModePreferences.setAllowSteps(allow)
        restartCalculation()
    }

    private fun restartCalculation() {
        calculationJob?.cancel()
        calculating.value = false
        routeResult.value = null
        arrived.value = false
        lastFix.value?.let { calculate(it) }
    }

    private fun onFix(fix: GpsFix) {
        lastFix.value = fix
        if (arrived.value) return
        when (val result = routeResult.value) {
            null -> if (!calculating.value) calculate(fix)
            is RouteResult.Found -> {
                val progress = NavigationTracker(result.route).progress(RoutePoint(fix.latitude, fix.longitude))
                if (progress.arrived) {
                    arrived.value = true
                } else if (shouldRecalculate(progress, calculating.value, lastCalculationMillis, fix.timeMillis)) {
                    calculate(fix)
                }
            }
            else -> Unit
        }
    }

    // Un calcolo alla volta: fuori percorso non si interrompe quello in corso, il successivo parte
    // comunque dalla posizione piu' recente. Solo il cambio di mezzo lo annulla (setTravelMode): per
    // questo il flag lo rimette a false solo il calcolo ancora corrente.
    private fun calculate(from: GpsFix) {
        if (calculating.value) return
        lastCalculationMillis = from.timeMillis
        calculationStartedMillis.value = System.currentTimeMillis()
        calculationProgress.value = 0.0
        calculating.value = true
        val choice = routingChoice(_travelMode.value, usageModePreferences.accessible.value, usageModePreferences.allowSteps.value)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                routeResult.value = engine.route(RoutePoint(from.latitude, from.longitude), destination, choice.profile, choice.params) { progress ->
                    if (calculationJob === self) calculationProgress.value = progress
                }
            } finally {
                if (calculationJob === self) calculating.value = false
            }
        }
        calculationJob = job
        job.start()
    }

    private data class CalculationInputs(val calculating: Boolean, val startedMillis: Long, val progress: Double, val result: RouteResult?, val arrived: Boolean)

    companion object {
        const val ARG_REGION_ID = "regionId"
        const val ARG_LATITUDE = "lat"
        const val ARG_LONGITUDE = "lon"
        const val ARG_NAME = "name"
    }
}
