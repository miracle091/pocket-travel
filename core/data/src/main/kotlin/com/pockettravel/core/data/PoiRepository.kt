package com.pockettravel.core.data

import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import javax.inject.Inject

class PoiRepository @Inject constructor(private val poiDao: PoiDao) {
    suspend fun forRegion(regionId: String): List<Poi> = poiDao.poisForRegion(regionId).map { it.toDomain() }
}

data class Poi(
    val id: Long,
    val regionId: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val osmTag: String,
    val phone: String?,
    val wheelchair: String? = null,
    // Dal pacchetto extra: la mappa li mostra anche se isHiddenOnMap() li nasconderebbe.
    val extra: Boolean = false,
)

private fun PoiEntity.toDomain() = Poi(id, regionId, name, category, lat, lon, osmTag, phone, wheelchair, extra)
