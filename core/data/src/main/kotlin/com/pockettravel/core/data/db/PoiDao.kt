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

    // I POI dell'area inquadrata dalla mappa, per i segnalini: tutti i POI di un paese grande (Italia, ~70 MB) non stanno
    // in memoria. Senza indice su lat/lon, come nearest: una scansione a ogni fermo della mappa.
    @Query("SELECT COUNT(*) FROM poi WHERE regionId = :regionId AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun countInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Int

    @Query("SELECT * FROM poi WHERE regionId = :regionId AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun poisInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<PoiEntity>

    // Un POI per cella di una griglia sull'area (celle di cellLat x cellLon gradi), quando sono troppi: sparsi su tutta
    // l'area invece dei primi della tabella. GROUP BY e MIN(rowid), non le window function (SQLite 3.25, da API 30).
    @Query(
        "SELECT * FROM poi WHERE rowid IN (SELECT MIN(rowid) FROM poi WHERE regionId = :regionId " +
            "AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon " +
            "GROUP BY CAST((lat - :minLat) / :cellLat AS INTEGER), CAST((lon - :minLon) / :cellLon AS INTEGER))",
    )
    suspend fun spreadInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double): List<PoiEntity>

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

    // Destinazioni della navigazione: nome locale, italiano o inglese che corrisponde a [pattern] (GLOB, vedi
    // accentInsensitiveGlob). Senza indice: una scansione della tabella, accettabile con il limite e una
    // ricerca ogni tanto.
    @Query(
        "SELECT * FROM poi WHERE regionId IN (:regionIds) AND extra = 0 " +
            "AND (name GLOB :pattern OR nameIt GLOB :pattern OR nameEn GLOB :pattern) LIMIT :limit",
    )
    suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity>

    // I POI del pacchetto base piu' vicini a un punto, dentro il riquadro dato: per il Navigatore senza meta. Senza
    // indice su lat/lon (una scansione, come searchByName): una ricerca solo quando la posizione si sposta.
    @Query(
        "SELECT * FROM poi WHERE regionId IN (:regionIds) AND extra = 0 " +
            "AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon " +
            "ORDER BY (lat - :lat) * (lat - :lat) + (lon - :lon) * (lon - :lon) * :lonScale LIMIT :limit",
    )
    suspend fun nearest(
        regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
    ): List<PoiEntity>

    @Query("DELETE FROM poi WHERE regionId = :regionId")
    suspend fun deleteForRegion(regionId: String)

    /** Solo i POI del pacchetto base ([extra] false) o solo quelli del pacchetto extra. */
    @Query("DELETE FROM poi WHERE regionId = :regionId AND extra = :extra")
    suspend fun deletePackageForRegion(regionId: String, extra: Boolean)
}

data class TransportCount(val osmTag: String, val category: String, val count: Int)
