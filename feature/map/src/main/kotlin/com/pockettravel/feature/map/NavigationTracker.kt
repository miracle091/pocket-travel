package com.pockettravel.feature.map

import kotlin.math.cos
import kotlin.math.hypot

/** Una strada della mappa con un nome: la sua linea (o un pezzo, se la strada e' divisa in piu' linee). */
data class NamedRoad(val name: String, val points: List<RoutePoint>)

/** Una posizione dal GPS: [timeMillis] e' l'ora in cui e' stata ricevuta (orologio di sistema). */
data class GpsFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timeMillis: Long)

/**
 * Dove si e' lungo il percorso. [nextInstruction]: la prossima indicazione davanti a noi (l'arrivo
 * se non ci sono piu' svolte); [offRoute]: la posizione e' lontana dal percorso, va ricalcolato.
 */
data class NavigationProgress(
    val nextInstruction: TurnInstruction,
    val nextInstructionIndex: Int,
    val distanceToNextMeters: Double,
    val remainingMeters: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** La posizione riportata sul percorso, e il tratto da li' fino alla prossima indicazione. */
    val positionOnRoute: RoutePoint,
    val pathToNext: List<RoutePoint>,
)

/**
 * Proietta la posizione GPS sul percorso: segmento piu' vicino, distanza percorsa, prossima svolta.
 * Le distanze sono calcolate qui sui punti del percorso (approssimazione piana locale, precisa
 * a pochi metri sulle distanze di una svolta), non prese da BRouter.
 */
class NavigationTracker(private val route: Route) {
    private val cumulative: DoubleArray = DoubleArray(route.points.size).also { acc ->
        for (i in 1 until route.points.size) acc[i] = acc[i - 1] + distanceMeters(route.points[i - 1], route.points[i])
    }
    private val totalMeters = cumulative.lastOrNull() ?: 0.0

    /** Metri lungo il percorso tra due punti (indici in [Route.points]), per le distanze tra le svolte. */
    fun distanceBetween(fromPointIndex: Int, toPointIndex: Int): Double =
        (cumulative.getOrElse(toPointIndex) { totalMeters } - cumulative.getOrElse(fromPointIndex) { totalMeters }).coerceAtLeast(0.0)

    fun progress(position: RoutePoint): NavigationProgress {
        val points = route.points
        var bestSegment = 0
        var bestFraction = 0.0
        var bestDistance = Double.MAX_VALUE
        if (points.size == 1) {
            bestDistance = distanceMeters(position, points[0])
        }
        for (i in 0 until points.size - 1) {
            val (fraction, distance) = projectOnSegment(position, points[i], points[i + 1])
            if (distance < bestDistance) {
                bestDistance = distance
                bestSegment = i
                bestFraction = fraction
            }
        }
        val travelled = if (points.size < 2) 0.0 else
            cumulative[bestSegment] + bestFraction * (cumulative[bestSegment + 1] - cumulative[bestSegment])
        val remaining = (totalMeters - travelled).coerceAtLeast(0.0)

        // Prossima indicazione: la prima il cui punto sta oltre la posizione proiettata.
        val instructions = route.instructions.ifEmpty { listOf(TurnInstruction(TurnType.ARRIVE, 0.0, points.lastIndex)) }
        val nextIndex = instructions.indexOfFirst { cumulative.getOrElse(it.pointIndex) { totalMeters } > travelled + PASSED_TURN_METERS }
            .let { if (it == -1) instructions.lastIndex else it }
        val next = instructions[nextIndex]
        val distanceToNext = (cumulative.getOrElse(next.pointIndex) { totalMeters } - travelled).coerceAtLeast(0.0)

        val positionOnRoute = if (points.size < 2) points.firstOrNull() ?: position else
            interpolate(points[bestSegment], points[bestSegment + 1], bestFraction)
        // Dal punto proiettato ai punti del tracciato fino a quello della prossima indicazione compreso.
        val pathToNext = listOf(positionOnRoute) +
            points.subList((bestSegment + 1).coerceAtMost(points.size), (next.pointIndex + 1).coerceIn(bestSegment + 1, points.size))

        // Se il punto piu' vicino e' l'inizio del percorso si sta ancora andando verso la strada da
        // cui parte (partenza in una zona pedonale, in un parcheggio, a piu' di 40 m dalla strada):
        // non e' un'uscita dal percorso, altrimenti si ricalcolerebbe di continuo.
        val approachingStart = bestSegment == 0 && bestFraction == 0.0
        val offRoute = bestDistance > OFF_ROUTE_METERS && !approachingStart
        return NavigationProgress(
            nextInstruction = next,
            nextInstructionIndex = nextIndex,
            distanceToNextMeters = distanceToNext,
            remainingMeters = remaining,
            offRoute = offRoute,
            arrived = !offRoute && remaining <= ARRIVAL_METERS,
            positionOnRoute = positionOnRoute,
            pathToNext = pathToNext,
        )
    }

    companion object {
        /** Oltre questa distanza dal percorso si e' fuori strada e si ricalcola. */
        const val OFF_ROUTE_METERS = 40.0

        /** A meno di questa distanza dalla fine del percorso si e' arrivati. */
        const val ARRIVAL_METERS = 20.0

        /** Una svolta superata da meno di questi metri conta gia' come passata (errore del GPS). */
        private const val PASSED_TURN_METERS = 5.0

        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Distanza massima dalla strada di una mappa perche' il suo nome valga per una svolta. */
        const val STREET_MATCH_METERS = 15.0

        /**
         * Il punto per cercare la strada in cui si entra con [instruction]: a meta' del tratto subito
         * dopo la svolta. null per l'arrivo o una svolta sull'ultimo punto.
         */
        fun streetProbe(route: Route, instruction: TurnInstruction): RoutePoint? {
            if (instruction.type == TurnType.ARRIVE || instruction.pointIndex + 1 >= route.points.size) return null
            return interpolate(route.points[instruction.pointIndex], route.points[instruction.pointIndex + 1], 0.5)
        }

        /** Il nome della strada di [roads] piu' vicina a [probe], se entro [maxMeters]. */
        fun nearestRoadName(probe: RoutePoint, roads: List<NamedRoad>, maxMeters: Double = STREET_MATCH_METERS): String? {
            var bestName: String? = null
            var bestDistance = maxMeters
            for (road in roads) {
                for (i in 0 until road.points.size - 1) {
                    val distance = projectOnSegment(probe, road.points[i], road.points[i + 1]).second
                    if (distance <= bestDistance) {
                        bestDistance = distance
                        bestName = road.name
                    }
                }
            }
            return bestName
        }

        fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
            val (x, y) = toLocalMeters(b, a)
            return hypot(x, y)
        }

        // Coordinate in metri di p rispetto all'origine, proiezione equirettangolare locale.
        private fun toLocalMeters(p: RoutePoint, origin: RoutePoint): Pair<Double, Double> {
            val x = Math.toRadians(p.longitude - origin.longitude) * EARTH_RADIUS_METERS * cos(Math.toRadians(origin.latitude))
            val y = Math.toRadians(p.latitude - origin.latitude) * EARTH_RADIUS_METERS
            return x to y
        }

        private fun interpolate(a: RoutePoint, b: RoutePoint, fraction: Double) =
            RoutePoint(a.latitude + (b.latitude - a.latitude) * fraction, a.longitude + (b.longitude - a.longitude) * fraction)

        // Frazione (0..1) del punto del segmento a-b piu' vicino a p, e la distanza di p da quel punto.
        private fun projectOnSegment(p: RoutePoint, a: RoutePoint, b: RoutePoint): Pair<Double, Double> {
            val (bx, by) = toLocalMeters(b, a)
            val (px, py) = toLocalMeters(p, a)
            val lengthSquared = bx * bx + by * by
            val fraction = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
            return fraction to hypot(px - fraction * bx, py - fraction * by)
        }
    }
}
