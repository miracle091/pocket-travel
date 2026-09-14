package com.pockettravel.core.data

import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** GuideDao e' un'interfaccia Room senza logica propria: un fake in-memory basta per
 * verificare il mapping entity<->dominio di GuideRepository senza un vero database. */
private class FakeGuideDao : GuideDao {
    val stored = mutableListOf<GuideSectionEntity>()
    var lastSearchQuery: String? = null

    override suspend fun insertAll(sections: List<GuideSectionEntity>) {
        stored += sections
    }

    override suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity> =
        stored.filter { it.regionId == regionId }

    override suspend fun search(query: String): List<GuideSectionEntity> {
        lastSearchQuery = query
        return stored.filter { it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true) }
    }

    override suspend fun searchInRegion(regionId: String, query: String): List<GuideSectionEntity> =
        search(query).filter { it.regionId == regionId }

    override suspend fun deleteForRegion(regionId: String) {
        stored.removeAll { it.regionId == regionId }
    }
}

class GuideRepositoryTest {

    private fun section(regionId: String, title: String, body: String = "corpo") = GuideSection(
        regionId = regionId,
        category = GuideCategory.DOGANE,
        title = title,
        body = body,
        sourceUrl = "https://en.wikivoyage.org/wiki/Test",
    )

    @Test
    fun `importSections poi sectionsFor ritorna le sezioni della stessa regione`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)

        repository.importSections(listOf(section("italia", "Dogane"), section("francia", "Douanes")))

        val result = repository.sectionsFor("italia")

        assertEquals(1, result.size)
        assertEquals("Dogane", result.single().title)
        assertEquals("italia", result.single().regionId)
    }

    @Test
    fun `search delega alla query FTS del dao`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        repository.importSections(listOf(section("italia", "Vaccinazioni richieste")))

        val result = repository.search("vaccinazioni")

        assertEquals("vaccinazioni", dao.lastSearchQuery)
        assertEquals(1, result.size)
    }

    @Test
    fun `searchInRegion filtra anche per regione`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        repository.importSections(listOf(section("italia", "Dogane italiane"), section("francia", "Dogane francesi")))

        val result = repository.searchInRegion("italia", "dogane")

        assertEquals(1, result.size)
        assertEquals("italia", result.single().regionId)
    }
}
