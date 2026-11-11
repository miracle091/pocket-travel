package com.pockettravel.core.data.testing

import com.pockettravel.core.data.FavoriteStop
import com.pockettravel.core.data.FavoriteStopsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** [FavoriteStopsRepository] in memoria: una lista per regione, uguali solo se identiche. */
class FakeFavoriteStopsRepository : FavoriteStopsRepository {
    private val regions = mutableMapOf<String, MutableStateFlow<List<FavoriteStop>>>()

    private fun flow(regionId: String) = regions.getOrPut(regionId) { MutableStateFlow(emptyList()) }

    override fun observe(regionId: String): Flow<List<FavoriteStop>> = flow(regionId)

    override suspend fun add(regionId: String, stop: FavoriteStop) = flow(regionId).update { if (stop in it) it else it + stop }

    override suspend fun remove(regionId: String, stop: FavoriteStop) = flow(regionId).update { it - stop }
}
