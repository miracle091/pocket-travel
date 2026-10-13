package com.pockettravel.core.data

import com.pockettravel.core.data.db.CityCoordinates
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.CityPopulation
import com.pockettravel.core.data.db.CitySectionEntity
import com.pockettravel.core.data.db.CitySectionMatch
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.GuideSectionEntity
import com.pockettravel.core.data.db.GuideSectionMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DAO delle guide che registra gli argomenti dell'ultima ricerca e restituisce righe fisse. */
private class RecordingGuideDao(
    private val sections: List<GuideSectionEntity> = emptyList(),
    private val matches: List<GuideSectionMatch> = emptyList(),
) : GuideDao {
    var lastRegionIds: List<String>? = null
    var lastQuery: String? = null
    var lastLimit: Int? = null

    override suspend fun insertAll(sections: List<GuideSectionEntity>) = Unit

    override suspend fun sectionsForRegion(regionId: String): List<GuideSectionEntity> = sections.filter { it.regionId == regionId }

    override suspend fun searchInRegionsRanked(regionIds: List<String>, query: String, candidateLimit: Int): List<GuideSectionMatch> {
        lastRegionIds = regionIds
        lastQuery = query
        lastLimit = candidateLimit
        return matches
    }

    override suspend fun deleteAll() = Unit

    override suspend fun optimizeFts() = Unit
}

/** DAO delle citta' che registra gli argomenti dell'ultima ricerca e restituisce righe fisse. */
private class RecordingCityDao(
    private val sections: List<CitySectionEntity> = emptyList(),
    private val matches: List<CitySectionMatch> = emptyList(),
    private val main: List<CityPopulation> = emptyList(),
    private val coordinates: CityCoordinates? = null,
) : CityDao {
    var lastRegionId: String? = null
    var lastQuery: String? = null
    var lastCity: String? = "unset"
    var lastLimit: Int? = null
    var lastMainLimit: Int? = null

    override suspend fun insertAll(sections: List<CitySectionEntity>) = Unit

    override fun citiesForRegion(regionId: String): Flow<List<String>> =
        flowOf(sections.filter { it.regionId == regionId }.map { it.city }.distinct())

    override fun mainCitiesForRegion(regionId: String, limit: Int): Flow<List<CityPopulation>> {
        lastMainLimit = limit
        return flowOf(main)
    }

    override suspend fun coordinatesFor(regionId: String, city: String): CityCoordinates? = coordinates

    override suspend fun sectionsFor(regionId: String, city: String): List<CitySectionEntity> =
        sections.filter { it.regionId == regionId && it.city == city }

    override suspend fun searchInRegionRanked(regionId: String, query: String, city: String?, candidateLimit: Int): List<CitySectionMatch> {
        lastRegionId = regionId
        lastQuery = query
        lastCity = city
        lastLimit = candidateLimit
        return matches
    }

    override suspend fun deleteForRegion(regionId: String) = Unit

    override suspend fun optimizeFts() = Unit
}

class GuideAndCityRepositoryEdgeCasesTest {

    private fun guideEntity(regionId: String, title: String, translated: Boolean = false, category: GuideCategory = GuideCategory.SALUTE) =
        GuideSectionEntity(
            regionId = regionId,
            category = category,
            title = title,
            body = "corpo $title",
            sourceUrl = "https://example.org/$title",
            translated = translated,
        )

    private fun cityEntity(city: String, title: String, category: GuideCategory = GuideCategory.COSA_VEDERE, translated: Boolean = false) =
        CitySectionEntity(
            regionId = "italia",
            city = city,
            category = category,
            title = title,
            body = "corpo $title",
            sourceUrl = "https://example.org/$title",
            translated = translated,
        )

    // GuideRepository

    @Test
    fun `l'id della guida generale e' quello della pipeline`() {
        assertEquals("_general", GENERAL_GUIDE_REGION_ID)
    }

    @Test
    fun `sectionsFor di una regione senza guida e' vuoto`() = runBlocking {
        val repository = GuideRepository(RecordingGuideDao(sections = listOf(guideEntity("italia", "Salute"))))

        assertEquals(emptyList<GuideSection>(), repository.sectionsFor("francia"))
    }

    @Test
    fun `sectionsFor porta ogni campo, compreso il flag di traduzione`() = runBlocking {
        val repository = GuideRepository(
            RecordingGuideDao(sections = listOf(guideEntity("italia", "Salute", translated = true, category = GuideCategory.DOGANE))),
        )

        val section = repository.sectionsFor("italia").single()

        assertEquals(
            GuideSection("italia", GuideCategory.DOGANE, "Salute", "corpo Salute", "https://example.org/Salute", translated = true),
            section,
        )
    }

    @Test
    fun `la ricerca in una regione chiede cento candidati e passa la query com'e'`() = runBlocking {
        val dao = RecordingGuideDao()

        GuideRepository(dao).searchCandidates("italia", "\"acqua\" OR potabile*")

        assertEquals(listOf("italia"), dao.lastRegionIds)
        assertEquals("\"acqua\" OR potabile*", dao.lastQuery)
        assertEquals(100, dao.lastLimit)
    }

    @Test
    fun `il tetto dei candidati cresce con le regioni cercate`() = runBlocking {
        val dao = RecordingGuideDao()

        GuideRepository(dao).searchCandidates(listOf("california", "nevada", "oregon"), "wine")

        assertEquals(listOf("california", "nevada", "oregon"), dao.lastRegionIds)
        assertEquals(300, dao.lastLimit)
    }

    @Test
    fun `senza regioni la ricerca non ha candidati e non ne chiede`() = runBlocking {
        val dao = RecordingGuideDao()

        val result = GuideRepository(dao).searchCandidates(emptyList(), "wine")

        assertEquals(emptyList<Pair<GuideSection, FtsMatchInfo>>(), result)
        assertEquals(0, dao.lastLimit)
    }

    @Test
    fun `i candidati tengono l'ordine del DAO e ognuno il suo matchinfo`() = runBlocking {
        val first = guideEntity("italia", "Primo")
        val second = guideEntity("italia", "Secondo", translated = true)
        val dao = RecordingGuideDao(
            matches = listOf(
                GuideSectionMatch(second, matchInfoBlob(phrases = 1, hits = listOf(Hits(3), Hits(0, 0, 0)), rowCount = 4)),
                GuideSectionMatch(first, matchInfoBlob(phrases = 1, hits = listOf(Hits(0, 0, 0), Hits(2)), rowCount = 9)),
            ),
        )

        val result = GuideRepository(dao).searchCandidates("italia", "x")

        assertEquals(listOf("Secondo", "Primo"), result.map { it.first.title })
        assertTrue(result[0].first.translated)
        assertEquals(3, result[0].second.hitsInRow(0, 0))
        assertEquals(4, result[0].second.rowCount)
        assertEquals(2, result[1].second.hitsInRow(0, 1))
        assertEquals(9, result[1].second.rowCount)
    }

    // CityRepository

    @Test
    fun `le citta' principali portano abitanti e capitale, anche mancanti`() = runBlocking {
        val dao = RecordingCityDao(
            main = listOf(CityPopulation("Roma", 2_800_000L, capital = true), CityPopulation("Bari", null), CityPopulation("Siena", 54_000L)),
        )

        val result = CityRepository(dao).mainCitiesFor("italia", 3).first()

        assertEquals(
            listOf(MainCity("Roma", 2_800_000L, capital = true), MainCity("Bari", null, capital = false), MainCity("Siena", 54_000L)),
            result,
        )
        assertEquals(3, dao.lastMainLimit)
    }

    @Test
    fun `senza citta' principali la lista e' vuota`() = runBlocking {
        assertEquals(emptyList<MainCity>(), CityRepository(RecordingCityDao()).mainCitiesFor("italia", 5).first())
    }

    @Test
    fun `le coordinate della citta' si passano, null se il cities db non le ha`() = runBlocking {
        assertEquals(CityCoordinates(41.9, 12.5), CityRepository(RecordingCityDao(coordinates = CityCoordinates(41.9, 12.5))).coordinatesFor("italia", "Roma"))
        assertNull(CityRepository(RecordingCityDao()).coordinatesFor("italia", "Roma"))
    }

    @Test
    fun `Storia e Clima vanno in coda in quest'ordine anche se il DAO li da' al contrario`() = runBlocking {
        val dao = RecordingCityDao(
            sections = listOf(
                cityEntity("Roma", "Clima", GuideCategory.CLIMA),
                cityEntity("Roma", "Storia", GuideCategory.STORIA),
                cityEntity("Roma", "Vedere", GuideCategory.COSA_VEDERE),
            ),
        )

        val titles = CityRepository(dao).sectionsFor("italia", "Roma").map { it.title }

        assertEquals(listOf("Vedere", "Storia", "Clima"), titles)
    }

    @Test
    fun `le altre sezioni tengono l'ordine del DAO davanti a Storia e Clima`() = runBlocking {
        val dao = RecordingCityDao(
            sections = listOf(
                cityEntity("Roma", "Trasporti", GuideCategory.TRASPORTI),
                cityEntity("Roma", "Storia", GuideCategory.STORIA),
                cityEntity("Roma", "Alloggio", GuideCategory.ALLOGGIO),
                cityEntity("Roma", "Vedere", GuideCategory.COSA_VEDERE),
            ),
        )

        val titles = CityRepository(dao).sectionsFor("italia", "Roma").map { it.title }

        assertEquals(listOf("Trasporti", "Alloggio", "Vedere", "Storia"), titles)
    }

    @Test
    fun `una citta' senza sezioni da' una lista vuota e il flag di traduzione si conserva`() = runBlocking {
        val dao = RecordingCityDao(sections = listOf(cityEntity("Roma", "Vedere", translated = true)))
        val repository = CityRepository(dao)

        assertEquals(emptyList<CitySection>(), repository.sectionsFor("italia", "Milano"))
        assertTrue(repository.sectionsFor("italia", "Roma").single().translated)
    }

    @Test
    fun `la ricerca nelle citta' passa regione, query e citta' al DAO con un tetto di cento candidati`() = runBlocking {
        val dao = RecordingCityDao()
        val repository = CityRepository(dao)

        repository.searchCandidates("italia", "duomo")
        assertEquals("italia", dao.lastRegionId)
        assertEquals("duomo", dao.lastQuery)
        assertNull(dao.lastCity)
        assertEquals(100, dao.lastLimit)

        repository.searchCandidates("italia", "duomo", city = "Milano")
        assertEquals("Milano", dao.lastCity)
        assertEquals(100, dao.lastLimit)
    }

    @Test
    fun `i candidati delle citta' portano il matchinfo e la sezione convertita`() = runBlocking {
        val section = cityEntity("Milano", "Duomo", translated = true)
        val dao = RecordingCityDao(
            matches = listOf(CitySectionMatch(section, matchInfoBlob(phrases = 1, hits = listOf(Hits(1), Hits(0, 0, 0)), rowCount = 11))),
        )

        val (found, info) = CityRepository(dao).searchCandidates("italia", "duomo").single()

        assertEquals(CitySection("italia", "Milano", GuideCategory.COSA_VEDERE, "Duomo", "corpo Duomo", "https://example.org/Duomo", translated = true), found)
        assertEquals(11, info.rowCount)
        assertEquals(1, info.hitsInRow(0, 0))
    }

    @Test
    fun `i nomi delle citta' si leggono una volta sola dal flusso`() = runBlocking {
        val dao = RecordingCityDao(sections = listOf(cityEntity("Roma", "A"), cityEntity("Roma", "B"), cityEntity("Bari", "C")))

        assertEquals(listOf("Roma", "Bari"), CityRepository(dao).cityNamesFor("italia"))
        assertEquals(emptyList<String>(), CityRepository(dao).cityNamesFor("francia"))
    }
}
