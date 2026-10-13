package com.pockettravel.core.data.testing

import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.TransitRepository
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
    ): TransitBoard {
        requests += Request(regionId, latitude, longitude, stationModes, now)
        failure?.let { throw it }
        return boards[regionId] ?: defaultBoard
    }
}
