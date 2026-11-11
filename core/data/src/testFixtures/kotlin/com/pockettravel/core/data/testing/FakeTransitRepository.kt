package com.pockettravel.core.data.testing

import com.pockettravel.core.data.NearbyStop
import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.TripRef
import com.pockettravel.core.data.TripView
import java.time.Instant

/** [TransitRepository] in memoria: risponde con [boards] (per regione, altrimenti [defaultBoard]) e registra le richieste in [requests]. */
class FakeTransitRepository : TransitRepository {
    /** Una richiesta di [TransitRepository.board]. */
    data class Request(
        val regionId: String,
        val latitude: Double,
        val longitude: Double,
        val stationModes: Set<TransitMode>,
        val now: Instant,
        val realtime: Map<String, RealtimeFeed> = emptyMap(),
    )

    var defaultBoard: TransitBoard = TransitBoard.NoStops
    val boards = mutableMapOf<String, TransitBoard>()
    val requests = mutableListOf<Request>()

    /** Se impostato, [board] lo lancia. */
    var failure: Throwable? = null

    override suspend fun board(
        regionId: String,
        latitude: Double,
        longitude: Double,
        stationModes: Set<TransitMode>,
        now: Instant,
        realtime: Map<String, RealtimeFeed>,
    ): TransitBoard {
        requests += Request(regionId, latitude, longitude, stationModes, now, realtime)
        failure?.let { throw it }
        return boards[regionId] ?: defaultBoard
    }

    /** La risposta di [nearbyStops]. */
    var nearbyStops: List<NearbyStop> = emptyList()
    val nearbyRequests = mutableListOf<Request>()

    override suspend fun nearbyStops(
        regionId: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        now: Instant,
        realtime: Map<String, RealtimeFeed>,
    ): List<NearbyStop> {
        nearbyRequests += Request(regionId, latitude, longitude, emptySet(), now, realtime)
        failure?.let { throw it }
        return nearbyStops
    }

    /** Una richiesta di [TransitRepository.tripStops]. */
    data class TripRequest(val regionId: String, val ref: TripRef, val now: Instant, val realtime: Map<String, RealtimeFeed>)

    /** Le risposte di [tripStops] per corsa; null per le altre. */
    val tripViews = mutableMapOf<TripRef, TripView>()
    val tripRequests = mutableListOf<TripRequest>()

    override suspend fun tripStops(regionId: String, ref: TripRef, now: Instant, realtime: Map<String, RealtimeFeed>): TripView? {
        tripRequests += TripRequest(regionId, ref, now, realtime)
        failure?.let { throw it }
        return tripViews[ref]
    }
}
