package com.pockettravel.core.data

import com.pockettravel.core.data.db.CityCoordinates
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

    override suspend fun coordinatesFor(regionId: String, city: String): CityCoordinates? =
        stored.firstOrNull { it.regionId == regionId && it.city == city && it.latitude != null && it.longitude != null }
            ?.let { CityCoordinates(checkNotNull(it.latitude), checkNotNull(it.longitude)) }

    override suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity> =
        stored.filter { it.regionId == regionId && it.city == city }

    // Come FakeGuideDao.searchInRegionRanked: filtra solo per regione e ritorna il matchinfo
    // impostato con setMatchInfo, i test si concentrano sul merge/ranking fatto dal chiamante.
    override suspend fun searchInRegionRanked(regionId: String, query: String, city: String?, candidateLimit: Int): List<CitySectionMatch> =
        stored
            .filter { it.regionId == regionId && (city == null || it.city == city) }
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

class CityRepositoryTest {

    private fun section(
        regionId: String,
        city: String,
        title: String,
        body: String = "corpo",
        category: GuideCategory = GuideCategory.COSA_VEDERE,
    ) = CitySectionEntity(
        regionId = regionId,
        city = city,
        category = category,
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
    fun `sectionsFor mette Storia e Clima di Wikipedia dopo le sezioni di Wikivoyage`() = runBlocking {
        val dao = FakeCityDao()
        // Ordine del DAO (ORDER BY category): CLIMA, COSA_VEDERE, DA_SAPERE, STORIA, TRASPORTI.
        dao.stored += listOf(
            section("italia", "Roma", "Clima", category = GuideCategory.CLIMA),
            section("italia", "Roma", "Cosa vedere", category = GuideCategory.COSA_VEDERE),
            section("italia", "Roma", "Da sapere", category = GuideCategory.DA_SAPERE),
            section("italia", "Roma", "Storia", category = GuideCategory.STORIA),
            section("italia", "Roma", "Trasporti", category = GuideCategory.TRASPORTI),
        )
        val repository = CityRepository(dao)

        val result = repository.sectionsFor("italia", "Roma")

        assertEquals(listOf("Cosa vedere", "Da sapere", "Trasporti", "Storia", "Clima"), result.map { it.title })
    }

    @Test
    fun `searchCandidates con la citta' tiene solo le sue sezioni`() = runBlocking {
        val dao = FakeCityDao()
        dao.stored += listOf(section("italia", "Roma", "Cosa vedere"), section("italia", "Bologna", "Cosa vedere"), section("francia", "Parigi", "Cosa vedere"))
        val repository = CityRepository(dao)

        assertEquals(listOf("Roma", "Bologna"), repository.searchCandidates("italia", "vedere").map { it.first.city })
        assertEquals(listOf("Bologna"), repository.searchCandidates("italia", "vedere", city = "Bologna").map { it.first.city })
    }

    @Test
    fun `searchCandidates legge il matchinfo di ogni candidato`() = runBlocking {
        val dao = FakeCityDao()
        val cosaVedere = section("san-marino", "Citta di San Marino", "Cosa vedere", "corpo vedere")
        dao.stored += cosaVedere
        dao.setMatchInfo(cosaVedere, matchInfoBlob(phrases = 1, hits = listOf(Hits(1), Hits(0, 0, 2)), rowCount = 7, averageLength = listOf(2, 80), length = listOf(2, 40)))
        val repository = CityRepository(dao)

        val info = repository.searchCandidates("san-marino", "vedere").single().second

        assertEquals(1, info.hitsInRow(0, 0))
        assertEquals(7, info.rowCount)
        assertEquals(40, info.length[1])
    }

    @Test
    fun `cityNamesFor ritorna i nomi delle citta' della regione`() = runBlocking {
        val dao = FakeCityDao()
        dao.stored += listOf(section("italia", "Roma", "Cosa vedere"), section("italia", "Bologna", "Cosa vedere"))

        assertEquals(listOf("Bologna", "Roma"), CityRepository(dao).cityNamesFor("italia"))
    }
}
