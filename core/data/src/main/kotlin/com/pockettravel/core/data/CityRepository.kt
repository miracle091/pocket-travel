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
     * Come GuideRepository.searchInRegion, ma con il punteggio di rilevanza matchinfo esposto:
     * usata da TravelAssistant per unire i candidati con quelli di GuideRepository, che usa lo
     * stesso schema di ranking (vedi GuideRepository per il formato del blob matchinfo(..., 'pcx')).
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

// Duplica GuideRepository.matchScore/readLittleEndianInt: stesso schema di colonne (title poi body,
// vedi CitySectionFts) e stesso punteggio, ma su una tabella FTS diversa — Room non condivide DAO
// tra entity diverse, quindi non c'e' un punto naturale per una sola implementazione senza un modulo
// a parte solo per questo.
private const val TITLE_COLUMN = 0
private const val TITLE_WEIGHT = 3.0
private const val BODY_WEIGHT = 1.0

private fun matchScore(matchinfo: ByteArray): Double {
    val ints = IntArray(matchinfo.size / 4) { i -> readLittleEndianInt(matchinfo, i * 4) }
    val phraseCount = ints[0]
    val columnCount = ints[1]
    var score = 0.0
    for (phrase in 0 until phraseCount) {
        for (column in 0 until columnCount) {
            val base = 2 + (phrase * columnCount + column) * 3
            val hitsInRow = ints[base]
            val docsWithHit = ints[base + 2]
            val columnWeight = if (column == TITLE_COLUMN) TITLE_WEIGHT else BODY_WEIGHT
            score += columnWeight * hitsInRow / docsWithHit.coerceAtLeast(1)
        }
    }
    return score
}

private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun CitySectionEntity.toDomain() = CitySection(
    regionId = regionId,
    city = city,
    category = category,
    title = title,
    body = body,
    sourceUrl = sourceUrl,
)
