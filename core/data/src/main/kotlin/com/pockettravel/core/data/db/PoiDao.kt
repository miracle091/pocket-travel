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
    // in memoria. Senza indice su lat/lon, come nearest: una scansione a ogni fermo della mappa. [excluded] sono le
    // coppie "category|osmTag" delle categorie filtrate dall'utente (vedi PoiRepository.inBounds): escluse qui, prima del
    // conteggio e del GROUP BY, altrimenti le celle sceglierebbero POI poi scartati e le categorie rimaste quasi sparirebbero.
    // [accessibility]: MapAccessibility.ordinal, il filtro "Con disabilita'" della mappa (stesso motivo delle categorie).
    @Query(POIS_IN_BOUNDS)
    suspend fun poisInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>, accessibility: Int,
    ): List<PoiEntity>

    // Un POI per cella di una griglia sull'area (celle di cellLat x cellLon gradi), quando sono troppi: sparsi su tutta
    // l'area invece dei primi della tabella. GROUP BY e MIN(rowid), non le window function (SQLite 3.25, da API 30).
    @Query(SPREAD_IN_BOUNDS)
    suspend fun spreadInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
        accessibility: Int,
    ): List<PoiEntity>

    // Le coppie (category, osmTag) presenti nell'area, senza filtri di categoria: da qui l'elenco delle categorie del foglio
    // dei filtri e le coppie da escludere, senza caricare i POI. count: i POI della coppia che passano [accessibility].
    @Query(CATEGORY_TAGS_IN_BOUNDS)
    suspend fun categoryTagsInBounds(regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, accessibility: Int): List<CategoryTag>

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

data class CategoryTag(val category: String, val osmTag: String, val count: Int)

// Le query dei segnalini sono costanti per poterle eseguire anche con sqlite-jdbc nei test.
private const val IN_AREA = "regionId = :regionId AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon"
private const val NOT_EXCLUDED = "(category || '|' || osmTag) NOT IN (:excluded)"

// Come MapScreen: senza "Con disabilita'" (0) niente parcheggi per disabili; con (1) niente POI con wheelchair=no; con
// "solo accessibili" (2) solo quelli col distintivo (wheelchair o toilets:wheelchair yes/designated/limited) e i parcheggi.
private const val BADGE = "('yes', 'designated', 'limited')"
private const val ACCESSIBLE =
    "(CASE :accessibility WHEN 0 THEN category != 'parking_disabled' " +
        "WHEN 1 THEN (wheelchair IS NULL OR wheelchair != 'no') " +
        "ELSE (category = 'parking_disabled' OR wheelchair IN $BADGE OR toiletsWheelchair IN $BADGE) END)"
internal const val POIS_IN_BOUNDS = "SELECT * FROM poi WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE"
internal const val SPREAD_IN_BOUNDS =
    "SELECT * FROM poi WHERE rowid IN (SELECT MIN(rowid) FROM poi WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE " +
        "GROUP BY CAST((lat - :minLat) / :cellLat AS INTEGER), CAST((lon - :minLon) / :cellLon AS INTEGER))"
internal const val CATEGORY_TAGS_IN_BOUNDS =
    "SELECT category, osmTag, SUM($ACCESSIBLE) AS count FROM poi WHERE $IN_AREA GROUP BY category, osmTag"
