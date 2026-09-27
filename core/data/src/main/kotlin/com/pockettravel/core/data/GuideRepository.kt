package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import javax.inject.Inject

class GuideRepository @Inject constructor(
    private val guideDao: GuideDao,
) {
    /**
     * Solo per i test: non sostituisce le sezioni gia' presenti. L'import reale del pacchetto guide
     * passa da GuidesImporter (core/sync), che svuota e reimporta tutto in una transazione.
     */
    suspend fun importSections(sections: List<GuideSection>) {
        guideDao.insertAll(sections.map { it.toEntity() })
    }

    suspend fun sectionsFor(regionId: String): List<GuideSection> =
        guideDao.sectionsForRegion(regionId).map { it.toDomain() }

    /** [query] e' un'espressione FTS4 MATCH gia' pulita dal chiamante (es. TravelAssistant.buildFtsQuery). */
    suspend fun search(query: String, limit: Int): List<GuideSection> =
        guideDao.search(query, limit).map { it.toDomain() }

    suspend fun searchInRegion(regionId: String, query: String, limit: Int): List<GuideSection> =
        guideDao.searchInRegion(regionId, query, limit).map { it.toDomain() }
}

private fun GuideSection.toEntity() = GuideSectionEntity(
    regionId = regionId,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)

private fun GuideSectionEntity.toDomain() = GuideSection(
    regionId = regionId,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)
