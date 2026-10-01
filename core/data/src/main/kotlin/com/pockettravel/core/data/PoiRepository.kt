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

    /**
     * POI delle [regionIds] col nome (locale, italiano o inglese) che contiene [query], senza quelli nascosti
     * sulla mappa: al piu' [limit], senza ordine (lo decide chi chiama, per esempio la distanza). Le maiuscole
     * contano solo fuori dall'ASCII (LIKE di SQLite): "riga" trova "Riga" ma non "Rīga".
     */
    suspend fun searchByName(regionIds: List<String>, query: String, limit: Int): List<Poi> {
        val text = query.trim()
        if (text.length < 2 || regionIds.isEmpty()) return emptyList()
        val pattern = "%" + text.replace("\\", "").replace("%", "").replace("_", "") + "%"
        return poiDao.searchByName(regionIds, pattern, limit).map { it.toDomain() }.filterNot { it.isHiddenOnMap() }
    }

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
