package com.pockettravel.feature.map

data class RoutePoint(val latitude: Double, val longitude: Double)

enum class TurnType {
    CONTINUE,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT,
    EXIT_LEFT, EXIT_RIGHT,
    U_TURN,
    ROUNDABOUT,
    /** Rotonda percorsa in senso orario: nazioni con guida a sinistra. */
    ROUNDABOUT_LEFT,
    ARRIVE,
}

val TurnType.isRoundabout: Boolean get() = this == TurnType.ROUNDABOUT || this == TurnType.ROUNDABOUT_LEFT

/**
 * Un'indicazione del percorso. [distanceToNextMeters]: metri fino all'indicazione successiva;
 * [pointIndex]: indice in [Route.points] del punto della svolta, per evidenziare il tratto;
 * [roundaboutExit]: numero dell'uscita (solo per le rotonde, altrimenti 0).
 * I segmenti .rd5 non contengono i nomi delle strade: niente "in via ...".
 */
data class TurnInstruction(
    val type: TurnType,
    val distanceToNextMeters: Double,
    val pointIndex: Int,
    val roundaboutExit: Int = 0,
)

data class Route(
    val points: List<RoutePoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val instructions: List<TurnInstruction> = emptyList(),
)

sealed interface RouteResult {
    data class Found(val route: Route) : RouteResult

    /** Mancano i segmenti della rete stradale della regione, o partenza/arrivo sono fuori da quelli scaricati. */
    data object NoRoutingData : RouteResult

    /** Dati presenti ma nessun percorso tra i due punti con questo profilo. */
    data object NotFound : RouteResult

    /** Calcolo troppo lungo per il telefono (tempo massimo superato): destinazione troppo lontana. */
    data object TimedOut : RouteResult

    /** Altro errore del motore. */
    data class Failed(val message: String) : RouteResult
}

interface RouteEngine {
    /**
     * Calcola il percorso fuori dal thread principale; annullando la coroutine si ferma il calcolo.
     * [profile]: nome del profilo BRouter (vedi [UsageMode.ROUTING_PROFILES]); null = quello del
     * motore, cioe' del modo quando e' stato creato. [profileParams]: variabili del
     * profilo (in BRouter "profile:<nome>=<valore>"), per esempio allow_steps del profilo wheelchair.
     * [onProgress]: stima 0..1 dell'avanzamento, chiamata piu' volte al secondo durante il calcolo
     * (da un altro thread).
     */
    suspend fun route(
        from: RoutePoint,
        to: RoutePoint,
        profile: String? = null,
        profileParams: Map<String, String> = emptyMap(),
        onProgress: (Double) -> Unit = {},
    ): RouteResult
}
