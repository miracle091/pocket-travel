package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class LocalTermsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ceviche = LocalTermRow("Ceviche", listOf("Cebiche", "Seviche"), "Ceviche", "Piatto di pesce crudo.", "https://it.wikipedia.org/wiki/Ceviche", 7)
    private val pisco = LocalTermRow("Pisco", emptyList(), "Pisco (liquore)", "Acquavite d'uva.", "https://it.wikipedia.org/wiki/Pisco_(liquore)", null)

    private fun guidesDb(name: String, terms: Map<String, List<LocalTermRow>>): File = tmp.newFile(name).also {
        writeGuidesDb(listOf(RegionGuide("peru", "https://it.wikivoyage.org/wiki/Perù", listOf(GuideSectionRow("CIBO_BEVANDE", "Mangiare", "Ceviche.")))), it)
        writeLocalTermsTable(terms, it)
    }

    @Test
    fun `righe del file di local_terms py, regione letta senza termini compresa`() {
        val jsonl = """
            {"regionId": "peru", "terms": [{"term": "Ceviche", "aliases": ["Cebiche", "Seviche"], "title": "Ceviche", "summary": "Piatto di pesce crudo.", "url": "https://it.wikipedia.org/wiki/Ceviche", "revision": 7}, {"term": "Pisco", "aliases": [], "title": "Pisco (liquore)", "summary": "Acquavite d'uva.", "url": "https://it.wikipedia.org/wiki/Pisco_(liquore)", "revision": null}]}

            {"regionId": "malta", "terms": []}
        """.trimIndent()

        assertEquals(mapOf("peru" to listOf(ceviche, pisco), "malta" to emptyList()), parseLocalTerms(jsonl))
    }

    @Test
    fun `guide_terms si rilegge uguale, regione per regione`() {
        val db = guidesDb("guides.db", mapOf("peru" to listOf(ceviche, pisco), "malta" to emptyList()))

        assertEquals(listOf(ceviche, pisco), readLocalTerms(db, "peru"))
        assertEquals(emptyList<LocalTermRow>(), readLocalTerms(db, "malta"))
    }

    @Test
    fun `guides db pubblicato senza la tabella, nessun termine`() {
        val db = tmp.newFile("old.db")
        writeGuidesDb(listOf(RegionGuide("peru", "https://it.wikivoyage.org/wiki/Perù", emptyList())), db)

        assertEquals(emptyList<LocalTermRow>(), readLocalTerms(db, "peru"))
    }

    @Test
    fun `le regioni non lette in questa run tengono i termini pubblicati, quelle lette senza termini restano senza`() {
        val published = mapOf("peru" to listOf(pisco), "malta" to listOf(pisco), "grecia" to listOf(pisco))
        val fresh = mapOf("peru" to listOf(ceviche), "malta" to emptyList())

        val terms = localTermsOf(listOf("peru", "malta", "grecia"), fresh) { published[it].orEmpty() }

        assertEquals(mapOf("peru" to listOf(ceviche), "malta" to emptyList(), "grecia" to listOf(pisco)), terms)
    }

    @Test
    fun `sameGuidesContent vede un termine cambiato ma non una revisione nuova della voce`() {
        val base = guidesDb("base.db", mapOf("peru" to listOf(ceviche)))
        val newRevision = guidesDb("revision.db", mapOf("peru" to listOf(ceviche.copy(revision = 8))))
        val otherSummary = guidesDb("summary.db", mapOf("peru" to listOf(ceviche.copy(summary = "Pesce marinato nel lime."))))
        val withoutTerms = tmp.newFile("without.db").also {
            writeGuidesDb(listOf(RegionGuide("peru", "https://it.wikivoyage.org/wiki/Perù", listOf(GuideSectionRow("CIBO_BEVANDE", "Mangiare", "Ceviche.")))), it)
        }

        assertTrue(sameGuidesContent(base, newRevision))
        assertFalse(sameGuidesContent(base, otherSummary))
        assertFalse(sameGuidesContent(withoutTerms, base))
    }

    @Test
    fun `data di lettura oggi per le regioni lette, quella pubblicata per le altre, nessuna per le mai lette`() {
        val today = LocalDate.of(2026, 11, 18)
        val dates = localTermsReadDates(listOf("peru", "malta", "gozo", "zambia"), mapOf("peru" to listOf(ceviche), "malta" to emptyList()), today) {
            mapOf("peru" to "2026-11-03", "gozo" to "2026-11-10")[it]
        }

        assertEquals(mapOf("peru" to "2026-11-18", "malta" to "2026-11-18", "gozo" to "2026-11-10"), dates)
    }

    @Test
    fun `guide_terms_read si rilegge uguale e non conta nel confronto con le guide pubblicate`() {
        val base = guidesDb("base.db", mapOf("peru" to listOf(ceviche)))
        val read = guidesDb("read.db", mapOf("peru" to listOf(ceviche))).also { writeLocalTermsReadTable(mapOf("peru" to "2026-11-18"), it) }

        assertEquals(mapOf("peru" to "2026-11-18"), readLocalTermsReadDates(read))
        assertEquals(emptyMap<String, String>(), readLocalTermsReadDates(base))
        assertTrue(sameGuidesContent(base, read))
    }

    @Test
    fun `date di lettura cambiate pubblicano solo se quelle pubblicate mancano o sono vecchie di piu' di 28 giorni`() {
        val today = LocalDate.of(2026, 12, 1)
        fun db(name: String, dates: Map<String, String>?) = guidesDb(name, mapOf("peru" to listOf(ceviche))).also { file ->
            dates?.let { writeLocalTermsReadTable(it, file) }
        }
        val fresh = db("fresh.db", mapOf("peru" to "2026-12-01", "malta" to "2026-11-20"))

        assertTrue(keepPublishedGuides(fresh, db("same.db", mapOf("peru" to "2026-12-01", "malta" to "2026-11-20")), today))
        assertTrue(keepPublishedGuides(fresh, db("recent.db", mapOf("peru" to "2026-11-10", "malta" to "2026-11-20")), today))
        assertFalse(keepPublishedGuides(fresh, db("old.db", mapOf("peru" to "2026-11-02", "malta" to "2026-11-20")), today))
        assertFalse(keepPublishedGuides(fresh, db("none.db", null), today))
    }
}
