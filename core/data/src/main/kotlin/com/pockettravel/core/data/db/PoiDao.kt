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

    // I POI dell'area inquadrata dalla mappa, per i segnalini: tutti i POI di una nazione grande (Italia, ~70 MB) non stanno
    // in memoria. Senza indice su lat/lon, come nearest: una scansione a ogni fermo della mappa. [excluded] sono le
    // coppie "category|osmTag" delle categorie filtrate dall'utente (vedi PoiRepository.inBounds): escluse qui, prima del
    // conteggio e del GROUP BY, altrimenti le celle sceglierebbero POI poi scartati e le categorie rimaste quasi sparirebbero.
    // [accessibility]: MapAccessibility.ordinal, il filtro "In sedia a rotelle" della mappa (stesso motivo delle categorie).
    // Al piu' [limit] righe: chiedendone una in piu' della soglia si sa se servono le celle, senza contare tutta l'area.
    @Query(POIS_IN_BOUNDS)
    suspend fun poisInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, excluded: List<String>, accessibility: Int, limit: Int,
    ): List<PoiEntity>

    // Un POI per cella di una griglia sull'area (celle di cellLat x cellLon gradi), quando sono troppi: sparsi su tutta
    // l'area invece dei primi della tabella. GROUP BY e MIN(rowid), non le window function (SQLite 3.25, da API 30).
    @Query(SPREAD_IN_BOUNDS)
    suspend fun spreadInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
        accessibility: Int,
    ): List<PoiEntity>

    // Come spreadInBounds, per le aree larghe (vedi PoiRepository.WIDE_AREA_DEGREES): legge le righe della regione in ordine di
    // rowid, cioe' in sequenza sul disco. Con l'indice su (regionId, lat) l'area e' poi una fascia di latitudine che attraversa
    // tutta la nazione, e le righe arrivano in ordine di latitudine, cioe' sparse: a cache fredda, su un milione di POI, 9 s invece di 1.
    @Query(SPREAD_IN_WIDE_BOUNDS)
    suspend fun spreadInWideBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double, excluded: List<String>,
        accessibility: Int,
    ): List<PoiEntity>

    // Le coppie (category, osmTag) della regione: da qui l'elenco delle categorie del foglio dei filtri e le coppie da
    // escludere. Una volta per regione (chi chiama la tiene): ripetuta a ogni fermo della mappa costerebbe troppo. Letta
    // tutta dall'indice (regionId, category, osmTag), senza toccare la tabella.
    @Query(CATEGORY_TAGS_IN_REGION)
    suspend fun categoryTagsInRegion(regionId: String): List<CategoryTag>

    // Stazioni, autostazioni, porti e aeroporti della regione, per i fatti rapidi della guida.
    @Query(
        "SELECT osmTag, category, COUNT(*) AS count FROM poi WHERE regionId = :regionId " +
            "AND osmTag IN ('railway=station', 'railway=halt', 'amenity=bus_station', 'amenity=ferry_terminal', 'aeroway=aerodrome') " +
            "GROUP BY osmTag, category",
    )
    suspend fun transportCounts(regionId: String): List<TransportCount>

    // Ambasciate e consolati di un paese nella regione, per il riquadro dei numeri di emergenza.
    @Query("SELECT * FROM poi WHERE regionId = :regionId AND category = 'embassy' AND country = :country ORDER BY name")
    suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity>

    // Destinazioni della navigazione: nome locale, italiano o inglese che corrisponde a [pattern] (GLOB, vedi
    // accentInsensitiveGlob). Il GLOB scandisce tutte le righe delle regioni, ma INDEXED BY lo costringe a leggerle in ordine di
    // rowid (in sequenza sul disco): scelto da solo, SQLite usa l'indice su (regionId, lat) e le legge sparse, a cache fredda
    // 7 s invece di 0,5 su un milione di POI. Accettabile con il limite e una ricerca ogni tanto.
    @Query(
        "SELECT * FROM poi INDEXED BY index_poi_regionId WHERE regionId IN (:regionIds) AND extra = 0 " +
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

    /** POI della regione: tutti con [extra] null, altrimenti solo quelli del pacchetto base (false) o extra (true). */
    @Query("DELETE FROM poi WHERE regionId = :regionId AND (:extra IS NULL OR extra = :extra)")
    suspend fun deleteForRegion(regionId: String, extra: Boolean?)
}

data class TransportCount(val osmTag: String, val category: String, val count: Int)

data class CategoryTag(val category: String, val osmTag: String)

// Le query dei segnalini sono costanti per poterle eseguire anche con sqlite-jdbc nei test.
private const val IN_AREA = "regionId = :regionId AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon"
private const val NOT_EXCLUDED = "(category || '|' || osmTag) NOT IN (:excluded)"

// Come MapScreen: senza "In sedia a rotelle" (0) niente parcheggi per disabili; con (1) niente POI con wheelchair=no; con
// "solo accessibili" (2) solo quelli col distintivo (wheelchair o toilets:wheelchair yes/designated/limited) e i parcheggi.
private const val BADGE = "('yes', 'designated', 'limited')"
private const val ACCESSIBLE =
    "(CASE :accessibility WHEN 0 THEN category != 'parking_disabled' " +
        "WHEN 1 THEN (wheelchair IS NULL OR wheelchair != 'no') " +
        "ELSE (category = 'parking_disabled' OR wheelchair IN $BADGE OR toiletsWheelchair IN $BADGE) END)"
internal const val POIS_IN_BOUNDS = "SELECT * FROM poi WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE LIMIT :limit"
internal const val SPREAD_IN_BOUNDS =
    "SELECT * FROM poi WHERE rowid IN (SELECT MIN(rowid) FROM poi WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE " +
        "GROUP BY CAST((lat - :minLat) / :cellLat AS INTEGER), CAST((lon - :minLon) / :cellLon AS INTEGER))"
// Come SPREAD_IN_BOUNDS, ma con le righe della regione lette in ordine di rowid (vedi PoiDao.spreadInWideBounds).
internal const val SPREAD_IN_WIDE_BOUNDS =
    "SELECT * FROM poi WHERE rowid IN (SELECT MIN(rowid) FROM poi INDEXED BY index_poi_regionId WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE " +
        "GROUP BY CAST((lat - :minLat) / :cellLat AS INTEGER), CAST((lon - :minLon) / :cellLon AS INTEGER))"
internal const val CATEGORY_TAGS_IN_REGION = "SELECT DISTINCT category, osmTag FROM poi WHERE regionId = :regionId"
