package com.pockettravel.core.data

import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.poiCategoryOf
import javax.inject.Inject

class PoiRepository @Inject constructor(private val poiDao: PoiDao) {
    suspend fun forRegion(regionId: String): List<Poi> = poiDao.poisForRegion(regionId).map { it.toDomain() }

    /** Ambasciate e consolati di [country] (ISO alpha-2) nella regione, in ordine di nome. */
    suspend fun embassiesOf(regionId: String, country: String): List<Poi> = poiDao.embassiesOf(regionId, country).map { it.toDomain() }

    /** Quanti treni (stazioni), metro, autostazioni, porti e aeroporti ha la regione, per categoria. */
    suspend fun transportCounts(regionId: String): Map<PoiCategory, Int> =
        poiDao.transportCounts(regionId)
            .groupBy({ poiCategoryOf(it.category, it.osmTag) }, { it.count })
            .mapValues { (_, counts) -> counts.sum() }
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
    val openingHours: String? = null,
    val address: String? = null,
    val website: String? = null,
    val email: String? = null,
    val country: String? = null,
    val nameEn: String? = null,
    val nameIt: String? = null,
    // Dal pacchetto extra: la mappa li mostra anche se isHiddenOnMap() li nasconderebbe.
    val extra: Boolean = false,
)

private fun PoiEntity.toDomain() =
    Poi(id, regionId, name, category, lat, lon, osmTag, phone, wheelchair, openingHours, address, website, email, country, nameEn, nameIt, extra)

/** Nome nella lingua dell'interfaccia ("en"/"it") se OSM lo ha, altrimenti quello locale. */
fun Poi.displayName(language: String): String = (if (language == "en") nameEn else nameIt ?: nameEn) ?: name
