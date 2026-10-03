package com.pockettravel.core.data

import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.TransportCount
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Il LIMIT di SQL viene prima del filtro sui POI nascosti: il repository deve chiedere di piu' e tagliare dopo. */
class PoiRepositorySearchTest {
    // Il fake applica il limite come SQL, prima del filtro dei nascosti.
    private class FakePoiDao(private val rows: List<PoiEntity>) : PoiDao {
        var requestedLimit = 0

        override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> {
            requestedLimit = limit
            return rows.take(limit)
        }
        override suspend fun nearest(
            regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
        ): List<PoiEntity> = emptyList()

        override suspend fun insertAll(pois: List<PoiEntity>) = Unit
        override suspend fun poisForRegion(regionId: String): List<PoiEntity> = emptyList()
        override suspend fun poisInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>): List<PoiEntity> = emptyList()
        override suspend fun spreadInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>): List<PoiEntity> = emptyList()
        override suspend fun categoryTagsInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<CategoryTag> = emptyList()
        override suspend fun transportCounts(regionId: String): List<TransportCount> = emptyList()
        override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> = emptyList()
        override suspend fun deleteForRegion(regionId: String) = Unit
        override suspend fun deletePackageForRegion(regionId: String, extra: Boolean) = Unit
    }

    private fun poi(id: Long, name: String, category: String = "restaurant", osmTag: String = "amenity=restaurant") =
        PoiEntity(id = id, regionId = "italia", name = name, category = category, lat = 45.0, lon = 9.0, osmTag = osmTag)

    @Test
    fun `i nascosti non svuotano il risultato e il limite vale dopo il filtro`() = runBlocking {
        // I primi tre sono nascosti (nome uguale alla categoria): con LIMIT 2 prima del filtro non resterebbe nulla.
        val rows = listOf(poi(1, "restaurant"), poi(2, "restaurant"), poi(3, "restaurant")) +
            (4L..9L).map { poi(it, "Da Mario $it") }
        val dao = FakePoiDao(rows)
        val result = PoiRepository(dao).searchByName(listOf("italia"), "ma", 2)
        assertEquals(listOf(4L, 5L), result.map { it.id })
        assertEquals(8, dao.requestedLimit)
    }
}
