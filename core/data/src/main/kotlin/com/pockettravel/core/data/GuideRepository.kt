package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import javax.inject.Inject

/**
 * regionId della guida generale di guide_sections: sezioni delle pagine tematiche di Wikivoyage (salute,
 * documenti, mance, truffe...) per le domande generali, che l'assistente cerca insieme alla regione aperta. Lo stesso id
 * di GenerateGeneralGuide.kt nella pipeline.
 */
const val GENERAL_GUIDE_REGION_ID = "_general"

class GuideRepository @Inject constructor(
    private val guideDao: GuideDao,
) {
    suspend fun sectionsFor(regionId: String): List<GuideSection> =
        guideDao.sectionsForRegion(regionId).map { it.toDomain() }

    /**
     * Fino a CANDIDATE_CAP sezioni che corrispondono a [query] (espressione FTS4 MATCH gia' pulita dal chiamante),
     * col loro matchinfo letto e senza ordine di rilevanza: FTS4 non ha bm25() (arrivato solo con FTS5), quindi
     * TravelAssistant le ordina in Kotlin insieme a quelle di CityRepository.searchCandidates (bm25Score).
     */
    suspend fun searchCandidates(regionId: String, query: String): List<Pair<GuideSection, FtsMatchInfo>> =
        searchCandidates(listOf(regionId), query)

    /**
     * Come l'altra searchCandidates, nelle guide di tutte le regioni [regionIds] (una nazione divisa, gli stati USA), con
     * CANDIDATE_CAP candidati per regione: senza ordine di rilevanza un tetto unico si riempirebbe con le prime regioni.
     */
    suspend fun searchCandidates(regionIds: List<String>, query: String): List<Pair<GuideSection, FtsMatchInfo>> =
        guideDao.searchInRegionsRanked(regionIds, query, CANDIDATE_CAP * regionIds.size)
            .map { it.section.toDomain() to FtsMatchInfo.parse(it.matchinfo) }

    private companion object {
        const val CANDIDATE_CAP = 100
    }
}

private fun GuideSectionEntity.toDomain() = GuideSection(
    regionId = regionId,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
    translated = translated,
)
