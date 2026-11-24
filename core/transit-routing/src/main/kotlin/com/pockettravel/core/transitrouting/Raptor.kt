package com.pockettravel.core.transitrouting

/** Un punto del viaggio: la partenza, l'arrivo o una fermata ([stop], indice in [TransitNetwork.stops]). */
data class Place(val lat: Double, val lon: Double, val stop: Int? = null)

/** Tratta di un itinerario, con gli orari in minuti dalla mezzanotte del giorno della ricerca. */
sealed interface Leg {
    val from: Place
    val to: Place
    val departMinute: Int
    val arriveMinute: Int
}

/** A piedi, [meters] in linea d'aria. */
data class WalkLeg(
    override val from: Place,
    override val to: Place,
    override val departMinute: Int,
    override val arriveMinute: Int,
    val meters: Int,
) : Leg

/** In corsa: la corsa [trip] del pattern [pattern], dalla fermata in posizione [boardIndex] a quella in [alightIndex]. */
data class RideLeg(
    override val from: Place,
    override val to: Place,
    override val departMinute: Int,
    override val arriveMinute: Int,
    val pattern: Int,
    val trip: Int,
    val boardIndex: Int,
    val alightIndex: Int,
) : Leg

/** Un modo di arrivare: tratte in ordine, dalla partenza all'arrivo. */
data class Itinerary(val legs: List<Leg>) {
    val departMinute: Int get() = legs.first().departMinute
    val arriveMinute: Int get() = legs.last().arriveMinute
    val transfers: Int get() = (legs.count { it is RideLeg } - 1).coerceAtLeast(0)
}

/**
 * RAPTOR (Delling, Pajor, Werneck, 2012) su una rete: a ogni giro una corsa in piu', poi i passaggi a piedi. Per ogni numero
 * di corse da 1 a [MAX_TRANSFERS] + 1 tiene l'itinerario che arriva prima, se arriva prima di quelli con meno cambi: il
 * risultato va dal meno cambi al piu' veloce.
 */
class Raptor(private val network: TransitNetwork) {

    fun plan(
        from: Place,
        to: Place,
        departMinute: Int,
        maxTransfers: Int = MAX_TRANSFERS,
        accessMeters: Int = ACCESS_METERS,
    ): List<Itinerary> {
        val stops = network.stops
        val rounds = maxTransfers + 1
        val arrival = Array(rounds + 1) { IntArray(stops.size) { UNREACHED } }
        val label = Array(rounds + 1) { arrayOfNulls<Label>(stops.size) }
        val best = IntArray(stops.size) { UNREACHED }
        var marked = mutableSetOf<Int>()
        for ((stop, meters) in near(from, accessMeters)) {
            val time = departMinute + walkMinutes(meters)
            arrival[0][stop] = time
            best[stop] = time
            label[0][stop] = AccessLabel(meters.toInt())
            marked += stop
        }
        val egress = near(to, accessMeters)
        var bestTarget = UNREACHED
        val found = mutableListOf<Itinerary>()
        for (k in 1..rounds) {
            if (marked.isEmpty()) break
            arrival[k] = arrival[k - 1].copyOf()
            label[k] = label[k - 1].copyOf()
            val ridden = rideRound(k, marked, arrival, label, best) { bestTarget }
            marked = ridden.toMutableSet()
            for (stop in ridden) {
                for (path in network.footpaths[stop]) {
                    val time = arrival[k][stop] + path.minutes
                    if (time < minOf(best[path.to], bestTarget)) {
                        arrival[k][path.to] = time
                        best[path.to] = time
                        label[k][path.to] = TransferLabel(stop, path.meters)
                        marked += path.to
                    }
                }
            }
            // Anche dalle fermate raggiunte a piedi dopo la corsa: la meta puo' essere vicina solo a quelle.
            val exit = egress.filter { (stop, _) -> stop in marked }
                .map { (stop, meters) -> Triple(stop, meters, arrival[k][stop] + walkMinutes(meters)) }
                .minByOrNull { it.third }
            if (exit != null && exit.third < bestTarget) {
                bestTarget = exit.third
                found += itinerary(k, exit.first, exit.second, from, to, departMinute, arrival, label)
            }
        }
        return found
    }

    /** Un giro: le corse dai pattern delle fermate [marked]; le fermate raggiunte prima di [bestTarget]. */
    private fun rideRound(
        k: Int,
        marked: Set<Int>,
        arrival: Array<IntArray>,
        label: Array<Array<Label?>>,
        best: IntArray,
        bestTarget: () -> Int,
    ): Set<Int> {
        val firstIndex = mutableMapOf<Int, Int>()
        for (stop in marked) {
            for ((p, i) in network.patternsAt[stop]) firstIndex[p] = minOf(firstIndex[p] ?: i, i)
        }
        val reached = mutableSetOf<Int>()
        for ((p, start) in firstIndex) {
            val pattern = network.patterns[p]
            var trip = -1
            var boardIndex = -1
            for (i in start until pattern.stops.size) {
                val stop = pattern.stops[i]
                if (trip >= 0 && pattern.canAlight?.get(i) != false) {
                    val time = pattern.trips[trip].arrivals[i]
                    if (time < minOf(best[stop], bestTarget())) {
                        arrival[k][stop] = time
                        best[stop] = time
                        label[k][stop] = RideLabel(p, trip, boardIndex, i)
                        reached += stop
                    }
                }
                val earlier = boardableTrip(pattern, i, arrival[k - 1][stop], label[k - 1][stop])
                if (earlier >= 0 && (trip < 0 || earlier < trip)) {
                    trip = earlier
                    boardIndex = i
                }
            }
        }
        return reached
    }

    /** La prima corsa di [pattern] che si prende alla fermata in posizione [index] arrivandoci a [previous]; -1 se nessuna. */
    private fun boardableTrip(pattern: TransitPattern, index: Int, previous: Int, how: Label?): Int {
        if (previous == UNREACHED || pattern.canBoard?.get(index) == false) return -1
        // Scendere e risalire su un'altra corsa costa il tempo di cambio; a piedi e' gia' nel passaggio.
        val ready = previous + if (how is RideLabel) CHANGE_MINUTES else 0
        return earliestTrip(pattern, index, ready)
    }

    // Le corse di un pattern non si sorpassano: le partenze da ogni fermata sono in ordine, si cerca per bisezione.
    private fun earliestTrip(pattern: TransitPattern, index: Int, ready: Int): Int {
        var low = 0
        var high = pattern.trips.size
        while (low < high) {
            val mid = (low + high) / 2
            if (pattern.trips[mid].departures[index] < ready) low = mid + 1 else high = mid
        }
        return if (low < pattern.trips.size) low else -1
    }

    private fun near(place: Place, maxMeters: Int): List<Pair<Int, Double>> =
        network.stops.indices.map { it to distanceMeters(place.lat, place.lon, network.stops[it].lat, network.stops[it].lon) }
            .filter { it.second <= maxMeters }

    private fun itinerary(
        k: Int,
        exitStop: Int,
        exitMeters: Double,
        from: Place,
        to: Place,
        departMinute: Int,
        arrival: Array<IntArray>,
        label: Array<Array<Label?>>,
    ): Itinerary {
        fun place(stop: Int) = network.stops[stop].let { Place(it.lat, it.lon, stop) }
        val exitTime = arrival[k][exitStop]
        val legs = mutableListOf<Leg>(WalkLeg(place(exitStop), to, exitTime, exitTime + walkMinutes(exitMeters), exitMeters.toInt()))
        var stop = exitStop
        var round = k
        while (true) {
            when (val current = label[round][stop]) {
                is RideLabel -> {
                    val pattern = network.patterns[current.pattern]
                    val trip = pattern.trips[current.trip]
                    val board = pattern.stops[current.boardIndex]
                    legs += RideLeg(
                        place(board), place(stop), trip.departures[current.boardIndex], trip.arrivals[current.alightIndex],
                        current.pattern, current.trip, current.boardIndex, current.alightIndex,
                    )
                    stop = board
                    round -= 1
                }
                is TransferLabel -> {
                    legs += WalkLeg(place(current.from), place(stop), arrival[round][current.from], arrival[round][stop], current.meters)
                    stop = current.from
                }
                is AccessLabel -> {
                    // Si esce di casa quanto basta per la prima corsa, non all'ora chiesta.
                    val firstDepart = (legs.last() as? RideLeg)?.departMinute ?: arrival[round][stop]
                    val walk = arrival[round][stop] - departMinute
                    legs += WalkLeg(from, place(stop), firstDepart - walk, firstDepart, current.meters)
                    return Itinerary(legs.reversed())
                }
                null -> error("RAPTOR: fermata $stop senza etichetta al giro $round")
            }
        }
    }

    private sealed interface Label

    private class AccessLabel(val meters: Int) : Label

    private class RideLabel(val pattern: Int, val trip: Int, val boardIndex: Int, val alightIndex: Int) : Label

    private class TransferLabel(val from: Int, val meters: Int) : Label
}

// Scelta dell'utente del 2026-11-18: al piu' tre cambi.
const val MAX_TRANSFERS = 3

private const val UNREACHED = Int.MAX_VALUE / 2
