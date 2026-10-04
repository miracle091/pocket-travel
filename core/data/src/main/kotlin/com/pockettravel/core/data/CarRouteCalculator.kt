package com.pockettravel.core.data

import com.pockettravel.core.data.db.CityCoordinates

/** Percorso in auto tra due punti: lunghezza in metri e tempo in secondi. */
data class CarRoute(val distanceMeters: Double, val durationSeconds: Double)

/**
 * Percorso in auto tra due punti sulla rete stradale scaricata della regione, per la distanza tra due citta'
 * dell'assistente IA. Implementato in feature:map con BRouter: qui perche' feature:ai non dipende da feature:map.
 * Null senza rete stradale della regione, con partenza o arrivo fuori dalla zona scaricata, o senza percorso.
 */
fun interface CarRouteCalculator {
    suspend fun carRoute(regionId: String, from: CityCoordinates, to: CityCoordinates): CarRoute?
}
