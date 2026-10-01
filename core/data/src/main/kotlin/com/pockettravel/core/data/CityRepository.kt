package com.pockettravel.core.data

import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CitySectionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class CityRepository @Inject constructor(
    private val cityDao: CityDao,
) {
    /** Nomi delle citta' abbinate alla regione, in ordine alfabetico. */
    fun citiesFor(regionId: String): Flow<List<String>> = cityDao.citiesForRegion(regionId)

    suspend fun sectionsFor(regionId: String, city: String): List<CitySection> =
        cityDao.sectionsFor(regionId, city).map { it.toDomain() }

    /**
     * Ricerca FTS4 con il punteggio di rilevanza matchinfo esposto (come GuideRepository.searchInRegionScored):
     * usata da TravelAssistant per unire i candidati con quelli di GuideRepository, che usa lo
     * stesso schema di ranking (vedi matchScore per il formato del blob matchinfo(..., 'pcx')).
     * [query] e' un'espressione FTS4 MATCH gia' pulita dal chiamante.
     */
    suspend fun searchInRegionScored(regionId: String, query: String, limit: Int): List<Pair<CitySection, Double>> =
        cityDao.searchInRegionRanked(regionId, query, maxOf(limit, CANDIDATE_CAP))
            .map { it.section.toDomain() to matchScore(it.matchinfo) }
            .sortedByDescending { it.second }
            .take(limit)

    private companion object {
        const val CANDIDATE_CAP = 30
    }
}

data class CitySection(
    val regionId: String,
    val city: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
)

private fun CitySectionEntity.toDomain() = CitySection(
    regionId = regionId,
    city = city,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)
