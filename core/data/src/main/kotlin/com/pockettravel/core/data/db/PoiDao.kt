package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PoiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pois: List<PoiEntity>)

    @Query("SELECT * FROM poi WHERE regionId = :regionId")
    suspend fun poisForRegion(regionId: String): List<PoiEntity>

    // Stazioni, autostazioni, porti e aeroporti della regione, per i fatti rapidi della guida.
    @Query(
        "SELECT osmTag, category, COUNT(*) AS count FROM poi WHERE regionId = :regionId " +
            "AND osmTag IN ('railway=station', 'railway=halt', 'amenity=bus_station', 'amenity=ferry_terminal', 'aeroway=aerodrome') " +
            "GROUP BY osmTag, category",
    )
    suspend fun transportCounts(regionId: String): List<TransportCount>

    // Ambasciate e consolati di un paese nella regione, per la scheda dei numeri di emergenza.
    @Query("SELECT * FROM poi WHERE regionId = :regionId AND category = 'embassy' AND country = :country ORDER BY name")
    suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity>

    // Destinazioni della navigazione: nome locale, italiano o inglese che contiene [pattern] (gia' con i %).
    // LIKE senza indice: una scansione della tabella, accettabile con il limite e una ricerca ogni tanto.
    @Query(
        "SELECT * FROM poi WHERE regionId IN (:regionIds) AND extra = 0 " +
            "AND (name LIKE :pattern OR nameIt LIKE :pattern OR nameEn LIKE :pattern) LIMIT :limit",
    )
    suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity>

    @Query("DELETE FROM poi WHERE regionId = :regionId")
    suspend fun deleteForRegion(regionId: String)

    /** Solo i POI del pacchetto base ([extra] false) o solo quelli del pacchetto extra. */
    @Query("DELETE FROM poi WHERE regionId = :regionId AND extra = :extra")
    suspend fun deletePackageForRegion(regionId: String, extra: Boolean)
}

data class TransportCount(val osmTag: String, val category: String, val count: Int)
