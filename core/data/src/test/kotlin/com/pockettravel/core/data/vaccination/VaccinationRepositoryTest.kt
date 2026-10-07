package com.pockettravel.core.data.vaccination

import com.pockettravel.core.data.db.VaccMetaEntity
import com.pockettravel.core.data.db.VaccPolioEntryEntity
import com.pockettravel.core.data.db.VaccPolioStatusEntity
import com.pockettravel.core.data.db.VaccRecommendedEntity
import com.pockettravel.core.data.db.VaccSpecialEntity
import com.pockettravel.core.data.db.VaccYfEntryEntity
import com.pockettravel.core.data.db.VaccYfRiskEntity
import com.pockettravel.core.data.db.VaccinationDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VaccinationDao e' un'interfaccia Room senza logica propria: un fake in-memory basta. */
private class FakeVaccinationDao : VaccinationDao {
    var yfRiskRows = emptyList<VaccYfRiskEntity>()
    var yfEntryRows = emptyList<VaccYfEntryEntity>()
    var polioStatusRows = emptyList<VaccPolioStatusEntity>()
    var polioEntryRows = emptyList<VaccPolioEntryEntity>()
    var specialRows = emptyList<VaccSpecialEntity>()
    var recommendedRows = emptyList<VaccRecommendedEntity>()
    var metaRows = emptyList<VaccMetaEntity>()

    override suspend fun insertYfRisk(rows: List<VaccYfRiskEntity>) { yfRiskRows = rows }
    override suspend fun insertYfEntry(rows: List<VaccYfEntryEntity>) { yfEntryRows = rows }
    override suspend fun insertPolioStatus(rows: List<VaccPolioStatusEntity>) { polioStatusRows = rows }
    override suspend fun insertPolioEntry(rows: List<VaccPolioEntryEntity>) { polioEntryRows = rows }
    override suspend fun insertSpecial(rows: List<VaccSpecialEntity>) { specialRows = rows }
    override suspend fun insertRecommended(rows: List<VaccRecommendedEntity>) { recommendedRows = rows }
    override suspend fun insertMeta(rows: List<VaccMetaEntity>) { metaRows = rows }
    override suspend fun yfRisk() = yfRiskRows
    override suspend fun yfEntry() = yfEntryRows
    override suspend fun polioStatus() = polioStatusRows
    override suspend fun polioEntry() = polioEntryRows
    override suspend fun special() = specialRows
    override suspend fun recommended() = recommendedRows
    override suspend fun meta() = metaRows
    override suspend fun deleteYfRisk() { yfRiskRows = emptyList() }
    override suspend fun deleteYfEntry() { yfEntryRows = emptyList() }
    override suspend fun deletePolioStatus() { polioStatusRows = emptyList() }
    override suspend fun deletePolioEntry() { polioEntryRows = emptyList() }
    override suspend fun deleteSpecial() { specialRows = emptyList() }
    override suspend fun deleteRecommended() { recommendedRows = emptyList() }
    override suspend fun deleteMeta() { metaRows = emptyList() }
}

class VaccinationRepositoryTest {

    private val day = "2026-10-03"

    private fun polioEntry(origin: String, window: String = "ANY", vaccine: String = "IPV", applies: String = "ALL") =
        VaccPolioEntryEntity(
            iso2 = "eg", origin = origin, vaccine = vaccine, timeWindow = window, applies = applies,
            noteIt = "", noteEn = "", sources = "F7", verified = day,
        )

    private fun recommended(code: String, level: String = "MOST") =
        VaccRecommendedEntity(iso2 = "ke", vaccine = code, level = level, conditionIt = "", conditionEn = "", sources = "", verified = day)

    private fun map(
        yfRisk: List<VaccYfRiskEntity> = emptyList(),
        yfEntry: List<VaccYfEntryEntity> = emptyList(),
        polioEntry: List<VaccPolioEntryEntity> = emptyList(),
        special: List<VaccSpecialEntity> = emptyList(),
        recommended: List<VaccRecommendedEntity> = emptyList(),
        meta: List<VaccMetaEntity> = emptyList(),
    ) = vaccinationDataFrom(yfRisk, yfEntry, emptyList(), polioEntry, special, recommended, meta)

    private fun risk(scope: String = "WHOLE", sources: String = "") =
        VaccYfRiskEntity(iso2 = "ke", scope = scope, areasIt = "", areasEn = "", sources = sources, verified = day)

    private fun yfEntry(transit: String = "ANY", fromList: String = "") = VaccYfEntryEntity(
        iso2 = "in", rule = "FROM_LIST", minAgeMonths = 9, transit = transit, fromList = fromList, exitRequired = false,
        noteIt = "n", noteEn = "e", sources = "F7", verified = day,
    )

    @Test
    fun `le sigle delle fonti si separano con il piu', senza spazi e senza voci vuote`() {
        val mapped = map(yfRisk = listOf(risk(sources = " F6 + F7 ++ "), risk(sources = "")))
        assertEquals(listOf("F6", "F7"), mapped.yfRisk[0].sources)
        assertFalse(mapped.yfRisk[0].partial)
        assertEquals(emptyList<String>(), mapped.yfRisk[1].sources)
    }

    @Test
    fun `lo scope della febbre gialla distingue maiuscole e minuscole`() {
        assertTrue(map(yfRisk = listOf(risk(scope = "whole"))).yfRisk.isEmpty())
    }

    @Test
    fun `la lista dei paesi di provenienza si normalizza in minuscolo senza vuoti`() {
        val mapped = map(yfEntry = listOf(yfEntry(fromList = " KE,, Ug ,"), yfEntry(fromList = ""))).yfEntry
        assertEquals(setOf("ke", "ug"), mapped[0].fromList)
        assertEquals(9, mapped[0].minAgeMonths)
        assertEquals(emptySet<String>(), mapped[1].fromList)
    }

    @Test
    fun `un transit sconosciuto o in minuscolo salta la riga`() {
        assertTrue(map(yfEntry = listOf(yfEntry(transit = "GT48H"), yfEntry(transit = "any"))).yfEntry.isEmpty())
    }

    @Test
    fun `l'origine polio con prefisso CAT usa la categoria, altrimenti l'elenco dei paesi`() {
        val rows = map(
            polioEntry = listOf(
                polioEntry("CAT:CVDPV2"),
                polioEntry("pk, AF"),
                polioEntry("CAT:SCONOSCIUTA"),
                polioEntry("cat:CVDPV2"),
                polioEntry(""),
            ),
        ).polioEntry
        // "CAT:SCONOSCIUTA" salta; "cat:" minuscolo non e' il prefisso e vale come un (unico) codice paese.
        assertEquals(4, rows.size)
        assertEquals(PolioCategory.CVDPV2, rows[0].originCategory)
        assertEquals(emptySet<String>(), rows[0].originCountries)
        assertNull(rows[1].originCategory)
        assertEquals(setOf("pk", "af"), rows[1].originCountries)
        assertEquals(setOf("cat:cvdpv2"), rows[2].originCountries)
        assertEquals(emptySet<String>(), rows[3].originCountries)
    }

    @Test
    fun `la finestra polio accetta solo i codici dei dati, non i nomi dell'enum`() {
        val rows = map(
            polioEntry = listOf(polioEntry("pk", window = "4W_12M"), polioEntry("pk", window = "W4_12M"), polioEntry("pk", window = "")),
        )
        assertEquals(listOf(PolioWindow.W4_12M), rows.polioEntry.map { it.window })
    }

    @Test
    fun `un vaccino o un'applicazione polio sconosciuti saltano la riga`() {
        val rows = map(polioEntry = listOf(polioEntry("pk", vaccine = "OPV"), polioEntry("pk", applies = "TUTTI"), polioEntry("pk")))
        assertEquals(1, rows.polioEntry.size)
    }

    @Test
    fun `YF vale febbre gialla ma i codici sono esatti`() {
        val mapped = map(recommended = listOf("YF", "YELLOW_FEVER", "yf", "COVID", "RABIES").map { recommended(it) })
        assertEquals(listOf(Vaccine.YELLOW_FEVER, Vaccine.YELLOW_FEVER, Vaccine.RABIES), mapped.recommended.map { it.vaccine })
    }

    @Test
    fun `un livello raccomandato sconosciuto salta la riga`() {
        assertTrue(map(recommended = listOf(recommended("HEPA", level = "ALWAYS"))).recommended.isEmpty())
    }

    @Test
    fun `i requisiti speciali con scopo sconosciuto saltano e i valori nulli restano nulli`() {
        fun special(purpose: String) = VaccSpecialEntity(
            iso2 = "sa", purpose = purpose, vaccine = "MENACWY", minAgeMonths = null, minDaysBefore = null, validityYears = null,
            noteIt = "", noteEn = "", sources = "F7", verified = day,
        )
        val mapped = map(special = listOf(special("HAJJ_UMRAH"), special("PELLEGRINAGGIO"))).special
        assertEquals(1, mapped.size)
        assertNull(mapped.single().minAgeMonths)
        assertNull(mapped.single().validityYears)
    }

    @Test
    fun `la meta ignora le chiavi sconosciute`() {
        val meta = map(meta = listOf(VaccMetaEntity("polio_verified", "2026-01-01"), VaccMetaEntity("altro", "x"))).meta
        assertEquals("2026-01-01", meta.polioVerifiedAt)
        assertNull(meta.polioStatement)
        assertNull(meta.lastReview)
    }

    @Test
    fun `load ritorna null se le tabelle sono vuote, anche con la sola meta`() = runBlocking {
        val dao = FakeVaccinationDao().apply { metaRows = listOf(VaccMetaEntity("last_review", day)) }
        val repository = VaccinationRepository(dao)
        assertNull(repository.load())
        assertNull(repository.evaluate(Trip(departure = "it", destination = "ke")))
    }

    @Test
    fun `load ritorna i dati e evaluate li passa al motore`() = runBlocking {
        val dao = FakeVaccinationDao().apply {
            yfRiskRows = listOf(risk(sources = "F7"))
            recommendedRows = listOf(recommended("YF"))
        }
        val repository = VaccinationRepository(dao)
        assertEquals(1, repository.load()!!.yfRisk.size)
        val result = repository.evaluate(Trip(departure = "it", destination = "ke"))!!
        assertTrue(result.items.any { it.vaccine == Vaccine.YELLOW_FEVER })
    }
}
