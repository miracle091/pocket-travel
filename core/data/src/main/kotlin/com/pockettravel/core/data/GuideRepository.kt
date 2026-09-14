package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import javax.inject.Inject

class GuideRepository @Inject constructor(
    private val guideDao: GuideDao,
) {
    suspend fun importSections(sections: List<GuideSection>) {
        guideDao.insertAll(sections.map { it.toEntity() })
    }

    suspend fun sectionsFor(regionId: String): List<GuideSection> =
        guideDao.sectionsForRegion(regionId).map { it.toDomain() }

    suspend fun search(query: String): List<GuideSection> =
        guideDao.search(query).map { it.toDomain() }

    suspend fun searchInRegion(regionId: String, query: String): List<GuideSection> =
        guideDao.searchInRegion(regionId, query).map { it.toDomain() }
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
