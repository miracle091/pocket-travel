package com.pockettravel.feature.map

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Partenza o destinazione su strade non collegate al resto della rete del modo ("start/target island detected": piste
 * di un aeroporto, strade interne di un resort): si riprova con il punto spostato a [OFFSETS_METERS] di distanza, in
 * [DIRECTIONS] direzioni, prima quelle verso l'altro estremo, e si tiene il primo percorso trovato, con la distanza
 * dal punto chiesto in [Route.startOffsetMeters]/[Route.endOffsetMeters]. Se nessun punto va, [RouteResult.Isolated].
 * Un tentativo con il punto ancora su un'isola si ferma al controllo delle isole di BRouter (500 nodi): i 32
 * tentativi per lato costano poco anche dove la rete e' grande.
 */
class IslandRetryRouteEngine(private val inner: RouteEngine) : RouteEngine {

    override suspend fun route(
        from: RoutePoint,
        to: RoutePoint,
        profile: String?,
        profileParams: Map<String, String>,
        onProgress: (Double) -> Unit,
    ): RouteResult {
        // Ogni tentativo riparte da 0: la barra mostra solo il valore piu' alto.
        var shown = 0.0
        val progress: (Double) -> Unit = { value -> if (value > shown) shown = value.also(onProgress) }
        val route: suspend (RoutePoint, RoutePoint) -> RouteResult = { start, end -> inner.route(start, end, profile, profileParams, progress) }
        var attempt = Attempt(from, to, route(from, to))
        // Spostata la destinazione puo' risultare isolata la partenza (BRouter controlla prima la destinazione).
        repeat(2) { attempt = moveOffIsland(attempt, route) }
        return attempt.finish(from, to)
    }

    private data class Attempt(val start: RoutePoint, val end: RoutePoint, val result: RouteResult)

    // Il primo punto spostato che esce dall'isola, o [RouteResult.Isolated] se nessuno; senza isola, [attempt] com'e'.
    private suspend fun moveOffIsland(attempt: Attempt, route: suspend (RoutePoint, RoutePoint) -> RouteResult): Attempt {
        val atStart = attempt.result.islandAtStart() ?: return attempt
        val (start, end) = attempt
        return candidates(if (atStart) start else end, toward = if (atStart) end else start).firstNotNullOfOrNull { point ->
            val next = if (atStart) Attempt(point, end, route(point, end)) else Attempt(start, point, route(start, point))
            // Ancora sull'isola, o fuori dalla rete stradale scaricata (in mare, oltre il ritaglio): punto dopo.
            next.takeUnless { it.result.islandAtStart() == atStart || it.result == RouteResult.NoRoutingData }
        } ?: attempt.copy(result = RouteResult.Isolated(atStart))
    }

    private fun Attempt.finish(from: RoutePoint, to: RoutePoint): RouteResult {
        val island = result.islandAtStart()
        return when {
            island != null -> RouteResult.Isolated(island)
            result !is RouteResult.Found -> result
            else -> RouteResult.Found(
                result.route.copy(
                    startOffsetMeters = if (start == from) 0.0 else NavigationTracker.distanceMeters(from, result.route.points.first()),
                    endOffsetMeters = if (end == to) 0.0 else NavigationTracker.distanceMeters(to, result.route.points.last()),
                ),
            )
        }
    }

    companion object {
        /** Distanza massima del punto spostato (utente, 2026-11-05). */
        const val MAX_OFFSET_METERS = 2_000.0
        private val OFFSETS_METERS = listOf(250.0, 500.0, 1_000.0, MAX_OFFSET_METERS)
        private const val DIRECTIONS = 8
        private const val METERS_PER_DEGREE = 111_320.0
        private const val FULL_TURN = 2 * Math.PI

        // Messaggi di btools.router.RoutingEngine ("target island detected for section N") e di BRouterRouteEngine
        // ("start island detected (...)"): true partenza, false destinazione, null nessuna isola.
        private fun RouteResult.islandAtStart(): Boolean? {
            val message = (this as? RouteResult.Failed)?.message ?: return null
            return when {
                "start island detected" in message -> true
                "target island detected" in message -> false
                else -> null
            }
        }

        // Punti a distanze crescenti attorno a [point]; a ogni distanza prima le direzioni piu' vicine a [toward].
        internal fun candidates(point: RoutePoint, toward: RoutePoint): List<RoutePoint> {
            val cosLat = cos(Math.toRadians(point.latitude))
            val bearingToward = atan2((toward.longitude - point.longitude) * cosLat, toward.latitude - point.latitude)
            val bearings = (0 until DIRECTIONS).map { it * FULL_TURN / DIRECTIONS }
                .sortedBy { bearing -> abs(bearing - bearingToward).mod(FULL_TURN).let { minOf(it, FULL_TURN - it) } }
            return OFFSETS_METERS.flatMap { meters ->
                bearings.map { bearing ->
                    RoutePoint(
                        point.latitude + meters * cos(bearing) / METERS_PER_DEGREE,
                        point.longitude + meters * sin(bearing) / (METERS_PER_DEGREE * cosLat),
                    )
                }
            }
        }
    }
}
