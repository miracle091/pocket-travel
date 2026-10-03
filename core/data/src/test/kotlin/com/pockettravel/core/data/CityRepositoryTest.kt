package com.pockettravel.core.data

import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CityPopulation
import com.pockettravel.core.data.db.CitySectionEntity
import com.pockettravel.core.data.db.CitySectionMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** CityDao e' un'interfaccia Room senza logica propria: un fake in-memory basta, come FakeGuideDao
 * in GuideRepositoryTest. */
private class FakeCityDao : CityDao {
    val stored = mutableListOf<CitySectionEntity>()
    private val matchInfoBySection = mutableMapOf<CitySectionEntity, ByteArray>()

    override suspend fun insertAll(sections: List<CitySectionEntity>) {
        stored += sections
    }

    override fun citiesForRegion(regionId: String): Flow<List<String>> =
        MutableStateFlow(stored.filter { it.regionId == regionId }.map { it.city }.distinct().sorted())

    override fun mainCitiesForRegion(regionId: String, limit: Int): Flow<List<CityPopulation>> =
        MutableStateFlow(
            stored.filter { it.regionId == regionId }.groupBy { it.city }
                .entries.sortedByDescending { (_, sections) -> sections.sumOf { it.body.length } }
                .take(limit).map { (city, sections) -> CityPopulation(city, sections.mapNotNull { it.population }.maxOrNull()) },
        )

    override suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity> =
        stored.filter { it.regionId == regionId && it.city == city }

    // Come FakeGuideDao.searchInRegionRanked: filtra solo per regione e ritorna il matchinfo
    // impostato con setMatchInfo, i test si concentrano sul merge/ranking fatto dal chiamante.
    override suspend fun searchInRegionRanked(regionId: String, query: String, candidateLimit: Int): List<CitySectionMatch> =
        stored
            .filter { it.regionId == regionId }
            .take(candidateLimit)
            .map { CitySectionMatch(it, matchInfoBySection[it] ?: NO_MATCH_INFO) }

    fun setMatchInfo(section: CitySectionEntity, matchinfo: ByteArray) {
        matchInfoBySection[section] = matchinfo
    }

    override suspend fun deleteForRegion(regionId: String) {
        stored.removeAll { it.regionId == regionId }
    }

    override suspend fun optimizeFts() = Unit
}

/** Blob matchinfo(..., 'pcx') finto, stesso formato di GuideRepositoryTest.matchInfo. */
private fun matchInfo(phraseCount: Int, columnCount: Int, perPhraseColumnHits: List<Triple<Int, Int, Int>> = emptyList()): ByteArray {
    val ints = mutableListOf(phraseCount, columnCount)
    perPhraseColumnHits.forEach { (hits, total, docs) -> ints += listOf(hits, total, docs) }
    val bytes = ByteArray(ints.size * 4)
    ints.forEachIndexed { i, value ->
        bytes[i * 4] = (value and 0xFF).toByte()
        bytes[i * 4 + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((value shr 24) and 0xFF).toByte()
    }
    return bytes
}

private val NO_MATCH_INFO = matchInfo(phraseCount = 0, columnCount = 0)

class CityRepositoryTest {

    private fun section(regionId: String, city: String, title: String, body: String = "corpo") = CitySectionEntity(
        regionId = regionId,
        city = city,
        category = GuideCategory.COSA_VEDERE,
        title = title,
        body = body,
        sourceUrl = "https://it.wikivoyage.org/wiki/$city",
    )

    @Test
    fun `citiesFor ritorna i nomi delle citta' della regione, ordinati`() = runBlocking {
        val dao = FakeCityDao()
        dao.stored += listOf(section("italia", "Roma", "Cosa vedere"), section("italia", "Bologna", "Cosa vedere"), section("francia", "Parigi", "Cosa vedere"))
        val repository = CityRepository(dao)

        assertEquals(listOf("Bologna", "Roma"), repository.citiesFor("italia").first())
    }

    @Test
    fun `sectionsFor ritorna solo le sezioni della citta' richiesta`() = runBlocking {
        val dao = FakeCityDao()
        dao.stored += listOf(section("italia", "Roma", "Cosa vedere"), section("italia", "Bologna", "Cosa vedere"))
        val repository = CityRepository(dao)

        val result = repository.sectionsFor("italia", "Roma")

        assertEquals(1, result.size)
        assertEquals("Roma", result.single().city)
    }

    @Test
    fun `searchInRegionScored ordina per rilevanza col matchinfo`() = runBlocking {
        val dao = FakeCityDao()
        val repository = CityRepository(dao)
        val comeArrivare = section("san-marino", "Citta di San Marino", "Come arrivare", "corpo arrivare")
        val cosaVedere = section("san-marino", "Citta di San Marino", "Cosa vedere", "corpo vedere")
        dao.stored += listOf(comeArrivare, cosaVedere)
        dao.setMatchInfo(comeArrivare, matchInfo(phraseCount = 1, columnCount = 2, perPhraseColumnHits = listOf(Triple(0, 0, 1), Triple(1, 2, 2))))
        dao.setMatchInfo(cosaVedere, matchInfo(phraseCount = 1, columnCount = 2, perPhraseColumnHits = listOf(Triple(1, 1, 1), Triple(0, 0, 2))))

        val result = repository.searchInRegionScored("san-marino", "vedere", limit = 2)

        assertEquals(listOf("Cosa vedere", "Come arrivare"), result.map { it.first.title })
    }
}
