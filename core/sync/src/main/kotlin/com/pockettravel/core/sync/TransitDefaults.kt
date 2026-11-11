package com.pockettravel.core.sync

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sqrt

/** Oltre questo numero di reti in una regione si propongono solo quelle vicine (Regno Unito: 12 aree). */
private const val MANY_TRANSIT_FEEDS = 3

/** Entro questa distanza dal rettangolo delle fermate una rete conta come vicina. */
private const val NEAR_TRANSIT_KM = 50.0

// Chilometri per grado di latitudine.
private const val KM_PER_DEGREE = 111.32

/** Come sono state scelte le reti di default di una regione con molte reti (mostrato nei Contenuti). */
@Serializable
enum class TransitDefaultReason {
    /** Quelle entro [NEAR_TRANSIT_KM] dalla posizione. */
    NEAR,

    /** Nessuna entro [NEAR_TRANSIT_KM]: la piu' vicina. */
    NEAREST,

    /** Posizione non nota o in un'altra nazione: tutte. */
    ALL,
}

/** Le reti tolte di default e il perche' ([reason] null con poche reti: si scaricano tutte, niente da spiegare). */
data class TransitDefault(val excluded: Set<String>, val reason: TransitDefaultReason?)

/**
 * La scelta di default quando l'utente non ha ancora scelto: con piu' di [MANY_TRANSIT_FEEDS] reti restano
 * quelle entro [NEAR_TRANSIT_KM] dalla posizione, o la piu' vicina se nessuna lo e'. La posizione va passata
 * solo se e' nella nazione della regione: senza (o in un'altra nazione) restano tutte.
 */
fun defaultTransitChoice(available: List<TransitFeed>, latitude: Double?, longitude: Double?): TransitDefault {
    val distances = if (latitude == null || longitude == null) {
        emptyList()
    } else {
        available.mapNotNull { feed -> feed.bbox?.let { feed.id to distanceToBoxKm(it, latitude, longitude) } }
    }
    return when {
        available.size <= MANY_TRANSIT_FEEDS -> TransitDefault(emptySet(), null)
        // Senza posizione, o senza rettangoli delle reti, restano tutte.
        distances.isEmpty() -> TransitDefault(emptySet(), TransitDefaultReason.ALL)
        else -> {
            val near = distances.filter { it.second <= NEAR_TRANSIT_KM }.map { it.first }
            val kept = near.ifEmpty { listOf(distances.minBy { it.second }.first) }
            TransitDefault(
                available.map { it.id }.toSet() - kept.toSet(),
                if (near.isEmpty()) TransitDefaultReason.NEAREST else TransitDefaultReason.NEAR,
            )
        }
    }
}

// Distanza approssimata (equirettangolare) dal punto al rettangolo, 0 se ci sta dentro.
private fun distanceToBoxKm(box: List<Double>, latitude: Double, longitude: Double): Double {
    val dLat = latitude - latitude.coerceIn(box[1], box[3])
    val dLon = (longitude - longitude.coerceIn(box[0], box[2])) * cos(Math.toRadians(latitude))
    return sqrt(dLat * dLat + dLon * dLon) * KM_PER_DEGREE
}
