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

/**
 * Costruisce un blob matchinfo(..., 'pcx') finto: interi a 32 bit little-endian, [phraseCount] e
 * [columnCount] seguiti da una tripla (occorrenze in questa riga, occorrenze totali, righe con
 * almeno un'occorrenza) per ogni coppia frase/colonna — stesso formato letto da
 * GuideRepository.matchScore.
 */
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
    fun `searchInRegion filtra anche per regione`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        repository.importSections(listOf(section("italia", "Dogane italiane"), section("francia", "Dogane francesi")))

        val result = repository.searchInRegion("italia", "dogane", limit = 10)

        assertEquals(1, result.size)
        assertEquals("italia", result.single().regionId)
    }

    @Test
    fun `searchInRegionScored espone lo stesso punteggio usato per ordinare searchInRegion`() = runBlocking {
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        val entity = GuideSectionEntity(regionId = "italia", category = GuideCategory.DOGANE, title = "Dogane", body = "corpo", sourceUrl = "https://it.wikivoyage.org/wiki/Italia")
        dao.stored += entity
        dao.setMatchInfo(entity, matchInfo(phraseCount = 1, columnCount = 2, perPhraseColumnHits = listOf(Triple(1, 1, 1), Triple(0, 0, 1))))

        val result = repository.searchInRegionScored("italia", "dogane", limit = 10)

        assertEquals(1, result.size)
        assertEquals("Dogane", result.single().first.title)
        // Un solo hit nel titolo (peso 3.0), nessun hit nel corpo: stesso matchScore usato da searchInRegion.
        assertEquals(3.0, result.single().second, 1e-9)
    }

    @Test
    fun `searchInRegion ordina per rilevanza col matchinfo, non per ordine di inserimento`() = runBlocking {
        // Caso reale: "Quale valuta si usa a San Marino?" tornava "Come arrivare" (che nomina San
        // Marino piu' volte nel corpo) invece di "Valuta e acquisti", perche' la MATCH non aveva un
        // ordine di rilevanza. "marino" e' quasi rumore (presente in entrambe le sezioni: idf basso),
        // mentre "valuta" e' raro e compare anche nel titolo di "Valuta e acquisti" (peso maggiore).
        val dao = FakeGuideDao()
        val repository = GuideRepository(dao)
        val comeArrivare = GuideSectionEntity(
            regionId = "san-marino",
            category = GuideCategory.TRASPORTI,
            title = "Come arrivare",
            body = "Si arriva a San Marino in autobus da Rimini; l'aeroporto piu' vicino e' quello di Rimini.",
            sourceUrl = "https://it.wikivoyage.org/wiki/San_Marino",
        )
        val valutaAcquisti = GuideSectionEntity(
            regionId = "san-marino",
            category = GuideCategory.ACQUISTI,
            title = "Valuta e acquisti",
            body = "A San Marino si usa l'euro, come in Italia. Si puo' pagare in contanti o con carta.",
            sourceUrl = "https://it.wikivoyage.org/wiki/San_Marino",
        )
        dao.stored += listOf(comeArrivare, valutaAcquisti)
        // Frase 0 = "valuta", frase 1 = "marino"; colonne title (indice 0) poi body (indice 1).
        dao.setMatchInfo(
            comeArrivare,
            matchInfo(
                phraseCount = 2, columnCount = 2,
                perPhraseColumnHits = listOf(
                    Triple(0, 0, 1), Triple(0, 0, 1), // "valuta": nessuna occorrenza
                    Triple(0, 0, 2), Triple(5, 7, 2), // "marino": 5 volte nel corpo, in entrambe le sezioni
                ),
            ),
        )
        dao.setMatchInfo(
            valutaAcquisti,
            matchInfo(
                phraseCount = 2, columnCount = 2,
                perPhraseColumnHits = listOf(
                    Triple(1, 3, 1), Triple(2, 3, 1), // "valuta": nel titolo e due volte nel corpo, solo qui
                    Triple(0, 0, 2), Triple(1, 7, 2), // "marino": una volta nel corpo, in entrambe le sezioni
                ),
            ),
        )

        val result = repository.searchInRegion("san-marino", "valuta OR marino", limit = 2)

        assertEquals(listOf("Valuta e acquisti", "Come arrivare"), result.map { it.title })
    }
}
