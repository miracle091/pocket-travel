package com.pockettravel.feature.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** Una strada della mappa con un nome: la sua linea (o un pezzo, se la strada e' divisa in piu' linee). */
data class NamedRoad(val name: String, val points: List<RoutePoint>)

/** Una posizione dal GPS: [timeMillis] e' l'ora in cui e' stata ricevuta (orologio monotono, vedi [GpsLocationSource.elapsedRealtimeMillis]). */
data class GpsFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timeMillis: Long)

/**
 * Dove si e' lungo il percorso. [nextInstruction]: la prossima indicazione davanti (l'arrivo
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
    /** Posizioni di fila con [offRoute] vero, questa compresa (0 sul percorso). */
    val offRouteFixes: Int,
    /** Secondi all'arrivo da [positionOnRoute], con i tempi dei punti di BRouter ([Route.pointSeconds]). */
    val remainingSeconds: Double,
    /** Il tratto di [positionOnRoute]: da [Route.points][segmentIndex] al punto dopo (il percorso gia' fatto e' prima). */
    val segmentIndex: Int,
    /** Direzione di marcia in gradi dal nord, in senso orario: verso il punto del percorso [NavigationTracker.BEARING_AHEAD_METERS] piu' avanti. */
    val bearingDegrees: Double,
)

/**
 * Proietta la posizione GPS sul percorso: segmento piu' vicino, distanza percorsa, prossima svolta.
 * Le distanze sono calcolate qui sui punti del percorso (approssimazione piana locale, precisa
 * a pochi metri sulle distanze di una svolta), non prese da BRouter.
 */
class NavigationTracker(private val route: Route, private val profile: RouteProfile = RouteProfile.CAR) {
    private val cumulative: DoubleArray = DoubleArray(route.points.size).also { acc ->
        for (i in 1 until route.points.size) acc[i] = acc[i - 1] + distanceMeters(route.points[i - 1], route.points[i])
    }
    private val totalMeters = cumulative.lastOrNull() ?: 0.0

    /** Metri lungo il percorso tra due punti (indici in [Route.points]), per le distanze tra le svolte. */
    fun distanceBetween(fromPointIndex: Int, toPointIndex: Int): Double =
        (cumulative.getOrElse(toPointIndex) { totalMeters } - cumulative.getOrElse(fromPointIndex) { totalMeters }).coerceAtLeast(0.0)

    // Il tratto e la distanza piu' avanti raggiunti finora: la ricerca riparte da li' (vedi progress).
    private var frontSegment = 0
    private var maxTravelled = 0.0

    private class Projection(val segment: Int, val fraction: Double, val distance: Double)

    // Il tratto piu' vicino a [position] da [from] in avanti, tra quelli con la posizione proiettata tra
    // [minTravelled] e [maxTravelled] metri dalla partenza (a parita' di distanza vince il primo); null se nessuno. La
    // scansione si ferma ai tratti che iniziano oltre [maxTravelled]. Le proiezioni non creano oggetti: l'unico
    // nuovo e' quello del tratto migliore.
    private fun nearestSegment(position: RoutePoint, from: Int, minTravelled: Double, maxTravelled: Double): Projection? {
        val points = route.points
        var bestSegment = -1
        var bestDistanceSquared = Double.MAX_VALUE
        var bestFraction = 0.0
        for (i in from until points.size - 1) {
            if (cumulative[i] > maxTravelled) break
            projectInto(position, points[i], points[i + 1], scratch)
            projectedSegments++
            val travelled = cumulative[i] + scratch[0] * (cumulative[i + 1] - cumulative[i])
            if (scratch[1] < bestDistanceSquared && travelled >= minTravelled && travelled <= maxTravelled) {
                bestDistanceSquared = scratch[1]
                bestFraction = scratch[0]
                bestSegment = i
            }
        }
        return if (bestSegment < 0) null else Projection(bestSegment, bestFraction, sqrt(bestDistanceSquared))
    }

    /** Tratti proiettati finora (per verificare che la ricerca resti nella finestra). */
    internal var projectedSegments = 0L
        private set

    // Ultima posizione calcolata e il suo risultato: stessa posizione, stesso progress, senza rifare la ricerca.
    private var lastPosition: RoutePoint? = null
    private var lastProgress: NavigationProgress? = null

    // Frazione e distanza al quadrato dell'ultima proiezione calcolata da nearestSegment.
    private val scratch = DoubleArray(2)

    // Il tratto di [position] (vedi progress), e aggiorna il punto piu' avanti raggiunto. Con un solo punto o senza tratti:
    // la distanza dall'unico punto, tratto 0.
    private fun locate(position: RoutePoint, lookaheadMeters: Double): Projection {
        val points = route.points
        if (points.size < 2) return Projection(0, 0.0, points.firstOrNull()?.let { distanceMeters(position, it) } ?: Double.MAX_VALUE)
        val ahead = nearestAroundFront(position, lookaheadMeters)
        val overall = if (ahead == null || ahead.distance > profile.offRouteMeters) {
            nearestSegment(position, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        } else {
            null
        }
        val restarted = overall != null && (ahead == null || overall.distance < ahead.distance)
        val best = (if (restarted) overall else ahead) ?: Projection(0, 0.0, Double.MAX_VALUE)
        val travelled = cumulative[best.segment] + best.fraction * (cumulative[best.segment + 1] - cumulative[best.segment])
        if (restarted || travelled >= maxTravelled) {
            maxTravelled = travelled
            frontSegment = best.segment
        }
        return best
    }

    // Il tratto piu' vicino nella finestra attorno al punto piu' avanti raggiunto: da [BACK_MARGIN_METERS] dietro a
    // [lookaheadMeters] davanti. L'errore del GPS puo' riportare la posizione indietro di piu' del margine (dopo una
    // posizione spostata in avanti, a passo d'uomo): allora il tratto giusto e' poco dietro la finestra e quello trovato
    // nella finestra e' un altro, piu' lontano. Il tratto poco dietro vince solo se e' piu' vicino di [TIE_METERS]: dopo
    // un'inversione a U andata e ritorno passano per gli stessi punti, e resta il ritorno.
    private fun nearestAroundFront(position: RoutePoint, lookaheadMeters: Double): Projection? {
        val window = nearestAhead(position, maxTravelled - BACK_MARGIN_METERS, maxTravelled + lookaheadMeters)
        val behind = nearestAhead(position, maxTravelled - GPS_MARGIN_METERS, maxTravelled - BACK_MARGIN_METERS)
        return if (behind != null && (window == null || behind.distance < window.distance - TIE_METERS)) behind else window
    }

    // Il tratto piu' vicino con la posizione proiettata tra [minTravelled] e [maxTravelled] metri dalla partenza.
    private fun nearestAhead(position: RoutePoint, minTravelled: Double, maxTravelled: Double): Projection? {
        var from = frontSegment
        while (from > 0 && cumulative[from] > minTravelled) from--
        return nearestSegment(position, from, minTravelled, maxTravelled)
    }

    /**
     * Un tracker segue un solo percorso con la sua posizione: la ricerca del tratto parte da quello piu' avanti
     * raggiunto, con un margine all'indietro ([BACK_MARGIN_METERS]), e passa a tutto il percorso solo se li' la
     * posizione e' lontana. Dopo un'inversione a U la posizione resta cosi' sul tratto di ritorno, non su quello
     * d'andata (che passa per gli stessi punti). In avanti la ricerca guarda al piu' [LOOKAHEAD_METERS], quindi il costo
     * per posizione non cresce con la lunghezza del percorso, tranne quando si e' fuori percorso.
     *
     * Con l'ora della posizione ([timeMillis], quella di [GpsFix]) la ricerca in avanti si ferma a quanto si puo' percorrere
     * dalla posizione precedente alla velocita' tenuta finora lungo il percorso (con un margine, e al piu' quella massima
     * del mezzo), piu' un margine per l'errore del GPS: su un tornante o all'incrocio di un percorso a forbice la
     * posizione non salta al passaggio successivo, che e' vicino ma molto piu' avanti lungo il percorso. Una finestra
     * troppo corta (un'accelerazione brusca) costa solo la ricerca su tutto il percorso. La stessa posizione con la
     * stessa ora (o senza ora) da' lo stesso risultato senza rifare la ricerca.
     */
    fun progress(position: RoutePoint, timeMillis: Long? = null): NavigationProgress {
        lastProgress?.let { if (position == lastPosition && (timeMillis == null || timeMillis == lastTimeMillis)) return it }
        val previousTime = lastTimeMillis
        val previousReached = maxTravelled
        val seconds = if (timeMillis == null || previousTime == null) null else (timeMillis - previousTime).coerceAtLeast(0) / MILLIS_PER_SECOND
        return computeProgress(position, seconds?.let(::reachableMeters) ?: LOOKAHEAD_METERS).also {
            lastPosition = position
            lastProgress = it
            if (timeMillis != null) lastTimeMillis = timeMillis
            if (seconds != null && seconds > 0) updateSpeed((maxTravelled - previousReached) / seconds)
        }
    }

    // Quanto avanti lungo il percorso puo' stare una posizione [seconds] dopo la precedente.
    private fun reachableMeters(seconds: Double): Double {
        val speed = speedMps?.let { (it * SPEED_FACTOR + SPEED_SLACK_MPS).coerceAtMost(profile.maxSpeedMps) } ?: profile.maxSpeedMps
        return (speed * seconds + GPS_MARGIN_METERS).coerceAtMost(LOOKAHEAD_METERS)
    }

    // Media mobile della velocita' lungo il percorso, con [observedMps] dell'ultima posizione (negativa dopo una ripartenza).
    private fun updateSpeed(observedMps: Double) {
        val observed = observedMps.coerceIn(0.0, profile.maxSpeedMps)
        speedMps = speedMps?.let { previous -> previous + (observed - previous) * SPEED_SMOOTHING } ?: observed
    }

    // Ora dell'ultima posizione calcolata con un'ora, per la finestra in avanti; velocita' media lungo il percorso
    // (null prima di due posizioni con l'ora); posizioni fuori strada di fila.
    private var lastTimeMillis: Long? = null
    private var speedMps: Double? = null
    private var offRouteFixes = 0

    private fun computeProgress(position: RoutePoint, lookaheadMeters: Double): NavigationProgress {
        val points = route.points
        val best = locate(position, lookaheadMeters)
        val bestSegment = best.segment
        val bestFraction = best.fraction
        val bestDistance = best.distance
        val travelled = if (points.size < 2) 0.0 else
            cumulative[bestSegment] + bestFraction * (cumulative[bestSegment + 1] - cumulative[bestSegment])
        val remaining = (totalMeters - travelled).coerceAtLeast(0.0)

        // Prossima indicazione: la prima il cui punto sta oltre il punto piu' avanti raggiunto, che la posizione (con
        // l'errore del GPS indietro di qualche metro) non fa tornare a una svolta gia' superata.
        val instructions = route.instructions.ifEmpty { listOf(TurnInstruction(TurnType.ARRIVE, 0.0, points.lastIndex)) }
        val reached = maxOf(travelled, maxTravelled)
        val nextIndex = instructions.indexOfFirst { cumulative.getOrElse(it.pointIndex) { totalMeters } > reached + PASSED_TURN_METERS }
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
        // non e' un'uscita dal percorso, altrimenti si ricalcolerebbe di continuo. Solo entro
        // [APPROACH_START_MAX_METERS]: partendo nella direzione sbagliata si e' fuori strada e si ricalcola.
        val approachingStart = bestSegment == 0 && bestFraction == 0.0 && bestDistance <= APPROACH_START_MAX_METERS
        val offRoute = bestDistance > profile.offRouteMeters && !approachingStart
        offRouteFixes = if (offRoute) offRouteFixes + 1 else 0
        return NavigationProgress(
            nextInstruction = next,
            nextInstructionIndex = nextIndex,
            distanceToNextMeters = distanceToNext,
            remainingMeters = remaining,
            offRoute = offRoute,
            arrived = !offRoute && remaining <= profile.arrivalMeters,
            positionOnRoute = positionOnRoute,
            pathToNext = pathToNext,
            offRouteFixes = offRouteFixes,
            remainingSeconds = remainingSecondsAt(bestSegment, bestFraction, remaining),
            segmentIndex = bestSegment,
            bearingDegrees = bearingAhead(positionOnRoute, bestSegment, travelled),
        )
    }

    // Dalla posizione sul percorso al punto [BEARING_AHEAD_METERS] piu' avanti: guardando un po' avanti la direzione non
    // scatta a ogni nodo di una curva, e prima di una svolta ruota gia' verso la strada dopo. 0 (nord) senza tratti.
    private fun bearingAhead(position: RoutePoint, segment: Int, travelled: Double): Double {
        val points = route.points
        if (points.size < 2) return 0.0
        val target = travelled + BEARING_AHEAD_METERS
        var i = segment
        while (i < points.size - 2 && cumulative[i + 1] < target) i++
        val length = cumulative[i + 1] - cumulative[i]
        val ahead = if (length == 0.0) points[i + 1] else interpolate(points[i], points[i + 1], ((target - cumulative[i]) / length).coerceIn(0.0, 1.0))
        val (x, y) = toLocalMeters(ahead, position)
        // Sulla fine del percorso il punto avanti coincide con la posizione: la direzione dell'ultimo tratto.
        val (dx, dy) = if (x == 0.0 && y == 0.0) toLocalMeters(points[i + 1], points[i]) else x to y
        return (Math.toDegrees(atan2(dx, dy)) + FULL_TURN_DEGREES) % FULL_TURN_DEGREES
    }

    // Dai tempi dei punti, interpolati nel tratto; senza (o con un elenco che non combacia con i punti) in proporzione ai metri.
    private fun remainingSecondsAt(segment: Int, fraction: Double, remainingMeters: Double): Double {
        val seconds = route.pointSeconds
        if (seconds.size != route.points.size || seconds.size < 2) return remainingSeconds(route, remainingMeters)
        val elapsed = seconds[segment] + fraction * (seconds[segment + 1] - seconds[segment])
        return (seconds.last() - elapsed).coerceAtLeast(0.0)
    }

    companion object {
        /** Dall'inizio del percorso fino a questa distanza si sta ancora raggiungendo la partenza, senza ricalcolare. */
        const val APPROACH_START_MAX_METERS = 150.0

        /** Quanto la posizione puo' tornare indietro lungo il percorso rispetto al punto piu' avanti raggiunto. */
        private const val BACK_MARGIN_METERS = 10.0

        /** Quanto in avanti, rispetto al punto piu' avanti raggiunto, si cerca il tratto piu' vicino. */
        private const val LOOKAHEAD_METERS = 2000.0

        /** Oltre la strada percorribile tra due posizioni, quanto in avanti puo' spostarle l'errore del GPS. */
        private const val GPS_MARGIN_METERS = 30.0

        private const val MILLIS_PER_SECOND = 1000.0

        /** La finestra in avanti vale [SPEED_FACTOR] volte la velocita' media piu' [SPEED_SLACK_MPS], per le accelerazioni. */
        private const val SPEED_FACTOR = 1.5
        private const val SPEED_SLACK_MPS = 5.0

        /** Peso della velocita' dell'ultima posizione nella media. */
        private const val SPEED_SMOOTHING = 0.5

        /** Differenza di distanza sotto la quale due tratti sono vicini uguali. */
        private const val TIE_METERS = 1.0

        /** Quanto avanti lungo il percorso si guarda per la direzione di marcia. */
        const val BEARING_AHEAD_METERS = 30.0

        private const val FULL_TURN_DEGREES = 360.0

        /** Una svolta superata da meno di questi metri conta gia' come passata (errore del GPS). */
        private const val PASSED_TURN_METERS = 5.0

        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Distanza massima dalla strada di una mappa perche' il suo nome valga per una svolta. */
        const val STREET_MATCH_METERS = 15.0

        /** Frazione del tratto che porta al punto di mezzo. */
        private const val MIDPOINT_FRACTION = 0.5

        /**
         * Il punto per cercare la strada in cui si entra con [instruction]: a meta' del tratto subito
         * dopo la svolta. null per l'arrivo o una svolta sull'ultimo punto.
         */
        fun streetProbe(route: Route, instruction: TurnInstruction): RoutePoint? {
            if (instruction.type == TurnType.ARRIVE || instruction.pointIndex + 1 >= route.points.size) return null
            return interpolate(route.points[instruction.pointIndex], route.points[instruction.pointIndex + 1], MIDPOINT_FRACTION)
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
            val out = DoubleArray(2)
            projectInto(p, a, b, out)
            return out[0] to sqrt(out[1])
        }

        // Come projectOnSegment, senza oggetti: scrive in [out] la frazione e la distanza al quadrato.
        private fun projectInto(p: RoutePoint, a: RoutePoint, b: RoutePoint, out: DoubleArray) {
            val scaleX = Math.toRadians(1.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(a.latitude))
            val scaleY = Math.toRadians(1.0) * EARTH_RADIUS_METERS
            val bx = (b.longitude - a.longitude) * scaleX
            val by = (b.latitude - a.latitude) * scaleY
            val px = (p.longitude - a.longitude) * scaleX
            val py = (p.latitude - a.latitude) * scaleY
            val lengthSquared = bx * bx + by * by
            val fraction = if (lengthSquared == 0.0) 0.0 else ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
            val dx = px - fraction * bx
            val dy = py - fraction * by
            out[0] = fraction
            out[1] = dx * dx + dy * dy
        }
    }
}
