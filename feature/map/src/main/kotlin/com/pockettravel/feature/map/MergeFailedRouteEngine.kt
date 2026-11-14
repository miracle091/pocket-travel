package com.pockettravel.feature.map

/**
 * Motore della sola prima regione, usato quando le reti stradali di piu' regioni non si sono unite (Rd5Merger).
 * Un percorso dentro la prima regione si trova lo stesso; "mancano i dati" invece vuol dire che il percorso esce dalla
 * prima regione, e diventa [RouteResult.MergeFailed]: la rete stradale delle altre e' installata, scaricarla non serve.
 */
class MergeFailedRouteEngine(private val firstRegion: RouteEngine) : RouteEngine {

    override suspend fun route(
        from: RoutePoint,
        to: RoutePoint,
        profile: String?,
        profileParams: Map<String, String>,
        onProgress: (Double) -> Unit,
    ): RouteResult = when (val result = firstRegion.route(from, to, profile, profileParams, onProgress)) {
        RouteResult.NoRoutingData -> RouteResult.MergeFailed
        else -> result
    }
}
