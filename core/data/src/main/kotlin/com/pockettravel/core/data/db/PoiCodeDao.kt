package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Query

/** Le tabelle poi_region e poi_code, a cui l'importazione dei POI chiede i numeri da scrivere in [PoiRow]. */
@Dao
interface PoiCodeDao {
    /** Registra la regione in poi_region se manca: dopo, [regionRef] ne da' il riferimento per [PoiRow.regionRef]. */
    @Query("INSERT OR IGNORE INTO poi_region (regionId) VALUES (:regionId)")
    suspend fun ensureRegion(regionId: String)

    @Query("SELECT ref FROM poi_region WHERE regionId = :regionId")
    suspend fun regionRef(regionId: String): Long

    /** Registra la coppia in poi_code se manca: dopo, [codeOf] ne da' il codice per [PoiRow.code]. */
    @Query("INSERT OR IGNORE INTO poi_code (category, osmTag) VALUES (:category, :osmTag)")
    suspend fun ensureCode(category: String, osmTag: String)

    @Query("SELECT code FROM poi_code WHERE category = :category AND osmTag = :osmTag")
    suspend fun codeOf(category: String, osmTag: String): Long
}
