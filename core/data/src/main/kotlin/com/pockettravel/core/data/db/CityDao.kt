package com.pockettravel.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Candidato di ricerca con il matchinfo FTS4 grezzo (formato 'pcx'), per ordinare per rilevanza
 * lato Kotlin come GuideSectionMatch: FTS4 non ha bm25() come FTS5.
 */
data class CitySectionMatch(
    @Embedded val section: CitySectionEntity,
    val matchinfo: ByteArray,
)

@Dao
interface CityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sections: List<CitySectionEntity>)

    @Query("SELECT DISTINCT city FROM city_sections WHERE regionId = :regionId ORDER BY city")
    fun citiesForRegion(regionId: String): Flow<List<String>>

    // Le citta' principali: per popolazione, e senza popolazione (cities.db vecchio o dato assente) per
    // lunghezza della guida, dopo quelle con la popolazione.
    @Query(
        "SELECT city, MAX(population) AS population, MAX(capital) AS capital FROM city_sections WHERE regionId = :regionId GROUP BY city " +
            "ORDER BY MAX(population) IS NULL, MAX(population) DESC, SUM(LENGTH(body)) DESC LIMIT :limit",
    )
    fun mainCitiesForRegion(regionId: String, limit: Int): Flow<List<CityPopulation>>

    @Query("SELECT * FROM city_sections WHERE regionId = :regionId AND city = :city ORDER BY category")
    suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity>

    // Nessun ORDER BY per rilevanza, come GuideDao.searchInRegionRanked: si prendono fino a
    // candidateLimit candidati col loro matchinfo, e CityRepository.searchInRegionScored li
    // riordina in Kotlin.
    @Query(
        """
        SELECT city_sections.*, matchinfo(city_sections_fts, 'pcx') AS matchinfo FROM city_sections
        JOIN city_sections_fts ON city_sections.id = city_sections_fts.rowid
        WHERE city_sections_fts MATCH :query AND city_sections.regionId = :regionId
        LIMIT :candidateLimit
        """
    )
    suspend fun searchInRegionRanked(regionId: String, query: String, candidateLimit: Int): List<CitySectionMatch>

    @Query("DELETE FROM city_sections WHERE regionId = :regionId")
    suspend fun deleteForRegion(regionId: String)

    // Da chiamare dopo il reimport di una regione (CityImporter), come GuideDao.optimizeFts.
    @Query("INSERT INTO city_sections_fts(city_sections_fts) VALUES('optimize')")
    suspend fun optimizeFts()
}

/** Citta' con i suoi abitanti (null se ignoti) e se e' la capitale, per CityDao.mainCitiesForRegion. */
data class CityPopulation(val city: String, val population: Long?, val capital: Boolean = false)
