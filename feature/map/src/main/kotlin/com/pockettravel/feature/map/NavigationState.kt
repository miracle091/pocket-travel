package com.pockettravel.feature.map

import kotlin.math.abs
import kotlin.math.ceil

/** Mezzo scelto nella schermata di navigazione: stessi profili BRouter delle modalita' d'uso. */
enum class TravelMode(val routingProfile: String) {
    WALK("shortest"),
    BIKE("trekking"),
    CAR("car-vario"),
    ;

    companion object {
        /** Il mezzo iniziale segue la modalita' d'uso: auto e camper in auto, bici in bici, il resto a piedi. */
        fun from(usageMode: UsageMode?): TravelMode = when (usageMode) {
            UsageMode.AUTO, UsageMode.CAMPER -> CAR
            UsageMode.BICI -> BIKE
            else -> WALK
        }
    }
}

/** Profilo BRouter e sue variabili (vedi RouteEngine.route) per un percorso. */
data class RoutingChoice(val profile: String, val params: Map<String, String> = emptyMap()) {
    val wheelchair: Boolean get() = profile == UsageMode.WHEELCHAIR_ROUTING_PROFILE
}

/**
 * A piedi con "Con disabilita'" si usa il profilo in sedia a rotelle: scale vietate, oppure molto
 * penalizzate se l'utente accetta qualche gradino ([allowSteps], variabile allow_steps del profilo).
 * Bici e auto restano quelle del mezzo.
 */
fun routingChoice(mode: TravelMode, accessible: Boolean, allowSteps: Boolean): RoutingChoice =
    if (mode == TravelMode.WALK && accessible) {
        RoutingChoice(UsageMode.WHEELCHAIR_ROUTING_PROFILE, if (allowSteps) mapOf("allow_steps" to "1") else emptyMap())
    } else {
        RoutingChoice(mode.routingProfile)
    }

/** Cosa mostra la schermata di navigazione. */
sealed interface NavigationUiState {
    /** Manca il permesso di posizione: senza, niente navigazione. */
    data object NeedsPermission : NavigationUiState

    /** GPS spento: la navigazione parte solo con il GPS attivo. */
    data object GpsDisabled : NavigationUiState

    /** GPS acceso ma nessuna posizione recente (appena acceso, al chiuso, segnale perso). */
    data object WaitingForFix : NavigationUiState

    /**
     * [elapsedSeconds]: da quanto dura il calcolo (in auto, su percorsi lunghi, anche minuti);
     * [progress]: stima 0..1 del motore, tarata sul tempo di un percorso lungo in auto.
     */
    data class Calculating(val elapsedSeconds: Long, val progress: Double = 0.0) : NavigationUiState

    /** [position]: l'ultima posizione GPS, per il punto sulla mappa. */
    data class Navigating(val route: Route, val progress: NavigationProgress, val position: RoutePoint) : NavigationUiState

    data object Arrived : NavigationUiState

    /** Nessun percorso: dati mancanti, strada non trovata o errore del motore. */
    data class Unavailable(val result: RouteResult) : NavigationUiState
}

/**
 * Stato della navigazione dagli ingressi, senza Android: il GPS e' obbligatorio. Senza permesso,
 * con il GPS spento o senza una posizione GPS recente non si naviga, anche con un percorso gia'
 * calcolato. [routeResult]: l'ultimo calcolo concluso (null se non c'e' ancora).
 */
fun navigationUiState(
    permissionGranted: Boolean,
    gpsEnabled: Boolean,
    lastFix: GpsFix?,
    nowMillis: Long,
    calculating: Boolean,
    routeResult: RouteResult?,
    arrived: Boolean,
    calculationStartedMillis: Long = nowMillis,
    calculationProgress: Double = 0.0,
): NavigationUiState {
    if (!permissionGranted) return NavigationUiState.NeedsPermission
    if (!gpsEnabled) return NavigationUiState.GpsDisabled
    if (arrived) return NavigationUiState.Arrived
    if (lastFix == null || nowMillis - lastFix.timeMillis > FIX_MAX_AGE_MILLIS) return NavigationUiState.WaitingForFix
    val found = routeResult as? RouteResult.Found
    if (found == null) {
        if (calculating || routeResult == null) {
            val elapsed = if (calculating) ((nowMillis - calculationStartedMillis) / 1_000).coerceAtLeast(0) else 0
            return NavigationUiState.Calculating(elapsed, if (calculating) calculationProgress else 0.0)
        }
        return NavigationUiState.Unavailable(routeResult)
    }
    val position = RoutePoint(lastFix.latitude, lastFix.longitude)
    val progress = NavigationTracker(found.route).progress(position)
    return if (progress.arrived) NavigationUiState.Arrived else NavigationUiState.Navigating(found.route, progress, position)
}

/**
 * Se ricalcolare il percorso: fuori strada, nessun calcolo in corso e l'ultimo avviato da almeno
 * [RECALCULATION_INTERVAL_MILLIS] (con un segnale che oscilla attorno alla soglia non si ricalcola
 * a ogni posizione).
 */
fun shouldRecalculate(progress: NavigationProgress, calculating: Boolean, lastCalculationMillis: Long, nowMillis: Long): Boolean =
    progress.offRoute && !calculating && nowMillis - lastCalculationMillis >= RECALCULATION_INTERVAL_MILLIS

/** Una posizione piu' vecchia di cosi' non vale: il segnale GPS e' perso. */
const val FIX_MAX_AGE_MILLIS = 10_000L

const val RECALCULATION_INTERVAL_MILLIS = 10_000L

/** Un arrivo piu' vecchio di cosi' non si annuncia piu' (snackbar, vibrazione): nessuno e' li' a sentirlo. */
const val RECENT_ARRIVAL_MILLIS = 2 * 60_000L

/** [arrivedAtMillis]: quando la guida ha visto la meta (null se mai): vero se e' successo da poco. */
fun isRecentArrival(arrivedAtMillis: Long?, nowMillis: Long): Boolean =
    arrivedAtMillis != null && nowMillis - arrivedAtMillis in 0..RECENT_ARRIVAL_MILLIS

/** Regione installata con i Percorsi e riquadro della sua mappa (null se non si legge). */
data class RoutingRegion(val regionId: String, val bounds: MapBounds?)

/** Attorno a partenza e arrivo, per le regioni vicine al confine: circa 5 km. */
private const val NAVIGATION_MARGIN_DEGREES = 0.05

/**
 * Le regioni della navigazione: quelle con i Percorsi installati il cui riquadro tocca il riquadro di
 * partenza e arrivo, allargato di qualche km. Con una sola il comportamento e' quello di sempre (nessuna
 * unione dei segmenti). La regione da cui si e' partiti ([regionId]) viene per prima, e resta l'unica
 * se nessun riquadro tocca: cosi' la mancanza dei dati si vede come prima ("Scarica i percorsi").
 */
fun navigationRegionIds(regionId: String, candidates: List<RoutingRegion>, from: RoutePoint, to: RoutePoint): List<String> {
    val minLon = minOf(from.longitude, to.longitude) - NAVIGATION_MARGIN_DEGREES
    val maxLon = maxOf(from.longitude, to.longitude) + NAVIGATION_MARGIN_DEGREES
    val minLat = minOf(from.latitude, to.latitude) - NAVIGATION_MARGIN_DEGREES
    val maxLat = maxOf(from.latitude, to.latitude) + NAVIGATION_MARGIN_DEGREES
    val touching = candidates
        .filter { region -> region.bounds?.let { it.minLon <= maxLon && it.maxLon >= minLon && it.minLat <= maxLat && it.maxLat >= minLat } == true }
        .map { it.regionId }
    // La regione di partenza con i percorsi ma senza riquadro leggibile (niente mappa ne' anteprima) resta.
    val startWithoutBounds = candidates.any { it.regionId == regionId && it.bounds == null }
    return (if (startWithoutBounds) touching + regionId else touching).ifEmpty { listOf(regionId) }.sortedBy { it != regionId }
}

/**
 * Punti sulla linea retta da [from] a [to], estremi compresi, a passi non piu' lunghi del margine (circa 5 km):
 * per trovare dove un percorso lungo esce dalle regioni scaricate e quale regione del catalogo ci sta in mezzo.
 */
fun straightLinePoints(from: RoutePoint, to: RoutePoint): List<RoutePoint> {
    val span = maxOf(abs(to.latitude - from.latitude), abs(to.longitude - from.longitude))
    val steps = ceil(span / NAVIGATION_MARGIN_DEGREES).toInt().coerceAtLeast(1)
    return (0..steps).map { i ->
        val t = i.toDouble() / steps
        RoutePoint(from.latitude + (to.latitude - from.latitude) * t, from.longitude + (to.longitude - from.longitude) * t)
    }
}

/**
 * La linea da partenza ad arrivo esce dai riquadri delle regioni con i Percorsi ([candidates], allargati del
 * margine): mancano i percorsi dei paesi in mezzo, e BRouter fallirebbe dopo un lungo calcolo con un errore
 * generico. Con una regione senza riquadro non si giudica (false) e decide BRouter.
 */
fun leavesRoutingRegions(candidates: List<RoutingRegion>, from: RoutePoint, to: RoutePoint): Boolean {
    val bounds = candidates.map { it.bounds ?: return false }
    return straightLinePoints(from, to).any { point ->
        bounds.none {
            point.longitude in it.minLon - NAVIGATION_MARGIN_DEGREES..it.maxLon + NAVIGATION_MARGIN_DEGREES &&
                point.latitude in it.minLat - NAVIGATION_MARGIN_DEGREES..it.maxLat + NAVIGATION_MARGIN_DEGREES
        }
    }
}
