package com.pockettravel.feature.map

/**
 * Quante volte e quanto spesso cercare il nome della strada delle svolte: una svolta in una strada senza nome non
 * si cerca per sempre ([maxAttempts] ricerche) e tra due ricerche passano almeno [intervalMillis]. Si cercano solo le
 * svolte vicine ([nearTurns]): quelle lontane hanno le tile non ancora caricate e consumerebbero i tentativi a vuoto.
 */
class StreetLookupBudget(private val maxAttempts: Int = MAX_ATTEMPTS, private val intervalMillis: Long = INTERVAL_MILLIS) {
    private val attempts = mutableMapOf<Int, Int>()
    private var lastSearchMillis: Long? = null

    /** Le svolte (per indice del punto) ancora da cercare, se e' passato abbastanza dall'ultima ricerca. */
    fun due(pointIndexes: Collection<Int>, nowMillis: Long): List<Int> {
        val last = lastSearchMillis
        if (last != null && nowMillis - last < intervalMillis) return emptyList()
        return pointIndexes.filter { (attempts[it] ?: 0) < maxAttempts }
    }

    /** Registra una ricerca fatta per [searched], che ha trovato il nome di [found]. */
    fun record(searched: Collection<Int>, found: Collection<Int>, nowMillis: Long) {
        lastSearchMillis = nowMillis
        searched.filter { it !in found }.forEach { attempts[it] = (attempts[it] ?: 0) + 1 }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INTERVAL_MILLIS = 5_000L
        private const val NEAR_METERS = 1_500.0

        /** Le svolte di [probes] (indice del punto e punto da cercare) entro 1,5 km in linea d'aria da [position]. */
        fun nearTurns(probes: List<Pair<Int, RoutePoint>>, position: RoutePoint): List<Int> =
            probes.filter { (_, probe) -> NavigationTracker.distanceMeters(position, probe) <= NEAR_METERS }.map { it.first }
    }
}
