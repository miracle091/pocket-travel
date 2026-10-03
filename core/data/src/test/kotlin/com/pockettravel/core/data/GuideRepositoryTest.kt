package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.GuideSectionMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** GuideDao e' un'interfaccia Room senza logica propria: un fake in-memory basta per
 * verificare il mapping entity<->dominio di GuideRepository senza un vero database. */
private class FakeGuideDao : GuideDao {
    val stored = mutableListOf<GuideSectionEntity>()
    private val matchInfoBySection = mutableMapOf<GuideSectionEntity, ByteArray>()
    var lastSearchQuery: String? = null
    var lastCandidateLimit: Int? = null

    override suspend fun insertAll(sections: List<GuideSectionEntity>) {
        stored += sections
    }

    override suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity> =
        stored.filter { it.regionId == regionId }

    // Il fake non replica il matching testuale FTS: filtra solo per regione e ritorna il
    // matchinfo impostato con setMatchInfo (NO_MATCH_INFO di default), cosi' i test si concentrano
    // sul ranking fatto da GuideRepository invece che sulla sintassi della query.
    override suspend fun searchInRegionRanked(regionId: String, query: String, candidateLimit: Int): List<GuideSectionMatch> {
        lastSearchQuery = query
        lastCandidateLimit = candidateLimit
        return stored
            .filter { it.regionId == regionId }
            .take(candidateLimit)
            .map { GuideSectionMatch(it, matchInfoBySection[it] ?: NO_MATCH_INFO) }
    }

    fun setMatchInfo(section: GuideSectionEntity, matchinfo: ByteArray) {
        matchInfoBySection[section] = matchinfo
    }

    override suspend fun deleteAll() {
        stored.clear()
    }

    override suspend fun optimizeFts() {}
}

class GuideRepositoryTest {

    private fun section(regionId: String, title: String, body: String = "corpo") = GuideSection(
        regionId = regionId,
        category = GuideCategory.DOGANE,
        title = title,
        body = body,
        sourceUrl = "https://en.wikivoyage.org/wiki/Test",
    )

    private fun GuideSection.toEntity() = GuideSectionEntity(regionId = regionId, category = category, title = title, body = body, sourceUrl = sourceUrl)

    @Test
    fun `sectionsFor ritorna le sezioni della stessa regione`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)

        dao.insertAll(listOf(section("italia", "Dogane"), section("francia", "Douanes")).map { it.toEntity() })

        val result = repository.sectionsFor("italia")

        assertEquals(1, result.size)
        assertEquals("Dogane", result.single().title)
        assertEquals("italia", result.single().regionId)
    }

    @Test
    fun `searchCandidates filtra anche per regione`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        dao.insertAll(listOf(section("italia", "Dogane italiane"), section("francia", "Dogane francesi")).map { it.toEntity() })

        val result = repository.searchCandidates("italia", "dogane")

        assertEquals(1, result.size)
        assertEquals("italia", result.single().first.regionId)
    }

    @Test
    fun `searchCandidates legge il matchinfo di ogni candidato`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        val entity = GuideSectionEntity(regionId = "italia", category = GuideCategory.DOGANE, title = "Dogane", body = "corpo", sourceUrl = "https://it.wikivoyage.org/wiki/Italia")
        dao.stored += entity
        dao.setMatchInfo(entity, matchInfoBlob(phrases = 1, hits = listOf(Hits(1), Hits(0, 0, 1)), rowCount = 12))

        val (section, info) = repository.searchCandidates("italia", "dogane").single()

        assertEquals("Dogane", section.title)
        assertEquals(1, info.hitsInRow(0, 0))
        assertEquals(12, info.rowCount)
        assertEquals("dogane", dao.lastSearchQuery)
    }
}
