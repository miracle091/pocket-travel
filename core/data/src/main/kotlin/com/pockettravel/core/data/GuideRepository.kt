package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import javax.inject.Inject

class GuideRepository @Inject constructor(
    private val guideDao: GuideDao,
) {
    suspend fun sectionsFor(regionId: String): List<GuideSection> =
        guideDao.sectionsForRegion(regionId).map { it.toDomain() }

    /**
     * FTS4 non ha bm25() (arrivato solo con FTS5): si prendono fino a CANDIDATE_CAP candidati col loro
     * matchinfo e si riordinano per rilevanza in Kotlin (vedi matchScore) prima di tagliare a [limit],
     * invece di affidarsi al semplice ordine per rowid della MATCH. Usata da TravelAssistant per unire i
     * candidati con quelli di CityRepository.searchInRegionScored, che usa lo stesso schema di ranking.
     */
    suspend fun searchInRegionScored(regionId: String, query: String, limit: Int): List<Pair<GuideSection, Double>> =
        guideDao.searchInRegionRanked(regionId, query, maxOf(limit, CANDIDATE_CAP))
            .map { it.section.toDomain() to matchScore(it.matchinfo) }
            .sortedByDescending { it.second }
            .take(limit)

    private companion object {
        const val CANDIDATE_CAP = 30
    }
}

private fun GuideSectionEntity.toDomain() = GuideSection(
    regionId = regionId,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)
