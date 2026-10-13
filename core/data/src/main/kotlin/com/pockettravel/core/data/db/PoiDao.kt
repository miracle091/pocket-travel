package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PoiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pois: List<PoiRow>)

    @Query("$POI_SELECT $POI_FROM WHERE poi.regionRef = $REGION_REF")
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
    // CROSS JOIN: parte dai pochi codici di poi_code e per ognuno conta le righe della regione dall'indice (regionRef, code);
    // scelto da solo, SQLite scorreva tutto l'indice della regione.
    @Query(
        "SELECT poi_code.osmTag AS osmTag, poi_code.category AS category, COUNT(*) AS count " +
            "FROM poi_code CROSS JOIN poi ON poi.code = poi_code.code WHERE poi.regionRef = $REGION_REF " +
            "AND poi_code.osmTag IN ('railway=station', 'railway=halt', 'amenity=bus_station', 'amenity=ferry_terminal', 'aeroway=aerodrome') " +
            "GROUP BY poi_code.osmTag, poi_code.category",
    )
    suspend fun transportCounts(regionId: String): List<TransportCount>

    // Ambasciate e consolati di un paese nella regione, per il riquadro dei numeri di emergenza.
    // CROSS JOIN come in transportCounts: dai codici delle ambasciate all'indice (regionRef, code), non a tutta la regione.
    @Query(
        "$POI_SELECT FROM poi_code CROSS JOIN poi ON poi.code = poi_code.code CROSS JOIN poi_region ON poi_region.ref = poi.regionRef " +
            "WHERE poi_code.category = 'embassy' AND poi.regionRef = $REGION_REF AND poi.country = :country ORDER BY poi.name",
    )
    suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity>

    // Destinazioni della navigazione: nome locale, italiano o inglese che corrisponde a [pattern] (GLOB, vedi
    // accentInsensitiveGlob). Il GLOB scandisce tutte le righe delle regioni, ma INDEXED BY lo costringe a leggerle in ordine di
    // rowid (in sequenza sul disco): scelto da solo, SQLite usa l'indice su (regionId, lat) e le legge sparse, a cache fredda
    // 7 s invece di 0,5 su un milione di POI. Accettabile con il limite e una ricerca ogni tanto.
    @Query(
        "$POI_SELECT FROM poi INDEXED BY index_poi_regionRef $POI_JOINS WHERE poi.regionRef IN $REGION_REFS AND poi.extra = 0 " +
            "AND (poi.name GLOB :pattern OR poi.nameIt GLOB :pattern OR poi.nameEn GLOB :pattern) LIMIT :limit",
    )
    suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity>

    // I POI del pacchetto base piu' vicini a un punto, dentro il rettangolo dato: per il Navigatore senza destinazione. Senza
    // indice su lat/lon (una scansione, come searchByName): una ricerca solo quando la posizione si sposta.
    @Query(
        "$POI_SELECT $POI_FROM WHERE poi.regionRef IN $REGION_REFS AND poi.extra = 0 " +
            "AND poi.latE6 BETWEEN :minLat * 1000000 AND :maxLat * 1000000 AND poi.lonE6 BETWEEN :minLon * 1000000 AND :maxLon * 1000000 " +
            "ORDER BY ($LAT - :lat) * ($LAT - :lat) + ($LON - :lon) * ($LON - :lon) * :lonScale LIMIT :limit",
    )
    suspend fun nearest(
        regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
    ): List<PoiEntity>

    /** POI della regione: tutti con [extra] null, altrimenti solo quelli del pacchetto base (false) o extra (true). */
    @Query("DELETE FROM poi WHERE regionRef = $REGION_REF AND (:extra IS NULL OR extra = :extra)")
    suspend fun deleteForRegion(regionId: String, extra: Boolean?)
}

data class TransportCount(val osmTag: String, val category: String, val count: Int)

data class CategoryTag(val category: String, val osmTag: String)

// Le query dei segnalini sono costanti per poterle eseguire anche con sqlite-jdbc nei test.
// Il riferimento della regione si calcola una volta sola (sottoquery senza correlazione) e l'indice su regionRef lo usa come costante.
private const val REGION_REF = "(SELECT ref FROM poi_region WHERE regionId = :regionId)"
private const val REGION_REFS = "(SELECT ref FROM poi_region WHERE regionId IN (:regionIds))"
private const val LAT = "poi.latE6 / 1000000.0"
private const val LON = "poi.lonE6 / 1000000.0"

// Le colonne di PoiEntity: category, osmTag e regionId vengono dalle tabelle dei codici, lat e lon dai microgradi.
private const val POI_SELECT =
    "SELECT poi.id AS id, poi_region.regionId AS regionId, poi.name AS name, poi_code.category AS category, $LAT AS lat, $LON AS lon, " +
        "poi_code.osmTag AS osmTag, poi.phone AS phone, poi.wheelchair AS wheelchair, poi.openingHours AS openingHours, poi.address AS address, " +
        "poi.website AS website, poi.email AS email, poi.country AS country, poi.nameEn AS nameEn, poi.nameIt AS nameIt, " +
        "poi.toiletsWheelchair AS toiletsWheelchair, poi.capacityDisabled AS capacityDisabled, poi.extra AS extra"
private const val POI_JOINS = "JOIN poi_code ON poi_code.code = poi.code JOIN poi_region ON poi_region.ref = poi.regionRef"
private const val POI_FROM = "FROM poi $POI_JOINS"

// Area in microgradi: il confronto e' fra l'intero dell'indice e il numero della query, senza arrotondare.
private const val IN_AREA =
    "poi.regionRef = $REGION_REF AND poi.latE6 BETWEEN :minLat * 1000000 AND :maxLat * 1000000 " +
        "AND poi.lonE6 BETWEEN :minLon * 1000000 AND :maxLon * 1000000"
// [excluded]: coppie "category|osmTag". Si risolvono nei codici una volta sola, senza una join con poi_code per ogni riga dell'area.
private const val NOT_EXCLUDED = "poi.code NOT IN (SELECT code FROM poi_code WHERE (category || '|' || osmTag) IN (:excluded))"

// Come MapScreen: senza "In sedia a rotelle" (0) niente parcheggi per disabili; con (1) niente POI con wheelchair=no; con
// "solo accessibili" (2) solo quelli col distintivo (wheelchair o toilets:wheelchair yes/designated/limited) e i parcheggi.
private const val BADGE = "('yes', 'designated', 'limited')"
private const val PARKING_DISABLED = "(SELECT code FROM poi_code WHERE category = 'parking_disabled')"
private const val ACCESSIBLE =
    "(CASE :accessibility WHEN 0 THEN poi.code NOT IN $PARKING_DISABLED " +
        "WHEN 1 THEN (poi.wheelchair IS NULL OR poi.wheelchair != 'no') " +
        "ELSE (poi.code IN $PARKING_DISABLED OR poi.wheelchair IN $BADGE OR poi.toiletsWheelchair IN $BADGE) END)"
private const val CELL_GROUP =
    "GROUP BY CAST(($LAT - :minLat) / :cellLat AS INTEGER), CAST(($LON - :minLon) / :cellLon AS INTEGER)"
internal const val POIS_IN_BOUNDS = "$POI_SELECT $POI_FROM WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE LIMIT :limit"
internal const val SPREAD_IN_BOUNDS =
    "$POI_SELECT $POI_FROM WHERE poi.id IN (SELECT MIN(poi.id) FROM poi WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE $CELL_GROUP)"
// Come SPREAD_IN_BOUNDS, ma con le righe della regione lette in ordine di rowid (vedi PoiDao.spreadInWideBounds).
internal const val SPREAD_IN_WIDE_BOUNDS =
    "$POI_SELECT $POI_FROM WHERE poi.id IN (SELECT MIN(poi.id) FROM poi INDEXED BY index_poi_regionRef " +
        "WHERE $IN_AREA AND $NOT_EXCLUDED AND $ACCESSIBLE $CELL_GROUP)"
// Le coppie della regione dai codici (poche centinaia): per ognuno basta una ricerca nell'indice (regionRef, code).
internal const val CATEGORY_TAGS_IN_REGION =
    "SELECT category, osmTag FROM poi_code WHERE EXISTS (SELECT 1 FROM poi WHERE poi.regionRef = $REGION_REF AND poi.code = poi_code.code)"
