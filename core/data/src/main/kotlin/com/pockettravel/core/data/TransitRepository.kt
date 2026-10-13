package com.pockettravel.core.data

import java.time.Instant

/** Prossime partenze dei mezzi pubblici vicino a un punto, dalle reti installate della regione (pacchetto TRANSIT). */
interface TransitRepository {
    /**
     * Letture in sola lettura su IO; una rete illeggibile si salta, come se non avesse fermate vicine. [stationModes]: i
     * mezzi della stazione o del terminal nel punto, le cui fermate GTFS possono essere piu' lontane (TRANSIT_STATION_RADIUS_M).
     */
    suspend fun board(
        regionId: String,
        latitude: Double,
        longitude: Double,
        stationModes: Set<TransitMode> = emptySet(),
        now: Instant = Instant.now(),
    ): TransitBoard
}
