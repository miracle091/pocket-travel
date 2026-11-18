package com.pockettravel.feature.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Le tappe della navigazione in corso non ancora raggiunte, in ordine: il percorso passa da tutte, i ricalcoli anche.
 * [onChange] riceve l'elenco dopo ogni tappa tolta, per salvarlo con la navigazione attiva; [onSkip] ricalcola il
 * percorso dopo «Salta la tappa».
 */
internal class NavigationWaypoints(private val onChange: (List<NavigationPlace>) -> Unit, private val onSkip: () -> Unit) {
    private val _remaining = MutableStateFlow<List<NavigationPlace>>(emptyList())
    val remaining: StateFlow<List<NavigationPlace>> = _remaining.asStateFlow()

    // Quante tappe del percorso [reachedRoute] sono gia' raggiunte: indice in Route.viaPointIndexes della prossima.
    private var reachedRoute: Route? = null
    private var reachedOnRoute = 0

    fun reset(waypoints: List<NavigationPlace>) {
        _remaining.value = waypoints
        reachedRoute = null
    }

    /** «Salta la tappa»: la prossima si toglie e il percorso si ricalcola verso le altre. */
    fun skip() {
        if (_remaining.value.isEmpty()) return
        dropNext()
        onSkip()
    }

    private fun dropNext() {
        _remaining.value = _remaining.value.drop(1)
        onChange(_remaining.value)
    }

    /**
     * La tappa raggiunta con questa posizione, gia' tolta, o null. Raggiunta: entro [arrivalMeters] dal suo punto sul
     * percorso, o con la posizione gia' oltre quel punto. Il percorso resta quello: la prossima tappa e' il punto dopo
     * in Route.viaPointIndexes.
     */
    fun reached(route: Route, position: RoutePoint, progress: NavigationProgress, arrivalMeters: Double): NavigationPlace? {
        val next = _remaining.value.firstOrNull()
        val reached = if (reachedRoute === route) reachedOnRoute else 0
        val index = route.viaPointIndexes.getOrNull(reached)
        val arrived = next != null && index != null &&
            (NavigationTracker.distanceMeters(position, route.points[index]) <= arrivalMeters || progress.segmentIndex >= index)
        if (!arrived) return null
        reachedRoute = route
        reachedOnRoute = reached + 1
        dropNext()
        return next
    }
}
