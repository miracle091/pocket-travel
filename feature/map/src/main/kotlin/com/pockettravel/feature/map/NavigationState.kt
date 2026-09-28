package com.pockettravel.feature.map

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

/** Il pacchetto Percorsi della regione, per il pulsante "Scarica i percorsi" della navigazione. */
enum class RoutingPackageState { UNKNOWN, INSTALLED, MISSING, DOWNLOADING }

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
