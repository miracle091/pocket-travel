package com.pockettravel.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CountryWikipediaTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val long = "Il sushi e il ramen sono i piatti piu' noti. ".repeat(6).trim()

    private fun line(regionId: String, title: String, text: String) =
        JSONObject(mapOf("regionId" to regionId, "title" to title, "text" to text)).toString()

    @Test
    fun `sezioni nelle categorie della guida con la voce come fonte`() {
        val text = "== Gastronomia ==\n{{vedi anche|Cucina giapponese}}\n$long\n== Media ==\n$long\n== Telecomunicazioni ==\n$long\n== Tradizioni ==\n$long"

        val sections = wikipediaGuideSections("Giappone", text, english = false)

        assertEquals(listOf("CIBO_BEVANDE", "VITA_QUOTIDIANA", "CONNETTIVITA", "USI_COSTUMI"), sections.map { it.category })
        assertEquals(listOf("Gastronomia", "Media", "Telecomunicazioni", "Tradizioni"), sections.map { it.title })
        assertEquals(long, sections.first().body)
        assertTrue(sections.all { it.sourceUrl == "https://it.wikipedia.org/wiki/Giappone" && !it.translated })
    }

    @Test
    fun `sottotitolo rimasto senza testo tolto`() {
        assertEquals("▸ Galateo\nInchini.", dropOrphanSubheadings("▸ Feste\n\n▸ Galateo\nInchini.\n\n▸ Ricorrenze"))
    }

    @Test
    fun `voce inglese con i titoli inglesi`() {
        val sections = wikipediaGuideSections("South Korea", "== Cuisine ==\n$long\n== Media ==\n$long", english = true)

        assertEquals(listOf("CIBO_BEVANDE", "VITA_QUOTIDIANA"), sections.map { it.category })
        assertEquals("https://en.wikipedia.org/wiki/South_Korea", sections.first().sourceUrl)
    }

    @Test
    fun `sezione con il solo rimando alla voce a parte tolta, quella lunga accorciata`() {
        val stub = "== Gastronomia ==\n{{vedi anche|Cucina keniota}}\n\nLa cucina keniota ha subito molte influenze."
        assertEquals(emptyList<GuideSectionRow>(), wikipediaGuideSections("Kenya", stub, english = false))

        val paragraphs = (1..40).joinToString("\n\n") { "Paragrafo $it sulla cucina del paese, con riso e pesce." }
        val body = wikipediaGuideSections("Kenya", "== Cucina ==\n$paragraphs", english = false).single().body
        assertTrue(body.length in WIKIPEDIA_MIN_CHARS..4000)
        assertTrue(body.endsWith("pesce."))
    }

    @Test
    fun `le sezioni nuove sostituiscono quelle di Wikipedia, le regioni non lette tengono le pubblicate`() {
        val wikivoyage = GuideSectionRow("CIBO_BEVANDE", "A tavola", "Sushi.", "https://it.wikivoyage.org/wiki/Giappone")
        val old = GuideSectionRow("CIBO_BEVANDE", "Gastronomia", "vecchia", "https://it.wikipedia.org/wiki/Giappone")
        val fresh = GuideSectionRow("CIBO_BEVANDE", "Gastronomia", "nuova", "https://it.wikipedia.org/wiki/Giappone")
        val guides = listOf(
            RegionGuide("giappone", "https://it.wikivoyage.org/wiki/Giappone", listOf(wikivoyage, old)),
            RegionGuide("kenya", "https://it.wikivoyage.org/wiki/Kenya", listOf(wikivoyage)),
            RegionGuide("perù", "https://it.wikivoyage.org/wiki/Perù", listOf(wikivoyage)),
        )
        val published = mapOf("kenya" to listOf(wikivoyage, old.copy(sourceUrl = "https://it.wikipedia.org/wiki/Kenya")))

        val result = guides.withWikipedia(mapOf("giappone" to listOf(fresh), "perù" to emptyList())) { published[it].orEmpty() }

        assertEquals(listOf(wikivoyage, fresh), result[0].sections)
        assertEquals(listOf(wikivoyage, old.copy(sourceUrl = "https://it.wikipedia.org/wiki/Kenya")), result[1].sections)
        assertEquals(listOf(wikivoyage), result[2].sections)
    }

    @Test
    fun `generateGuides con --wikipedia scrive le sezioni e senza il file ricopia le pubblicate`() {
        val dump = tmp.newFile("sm.txt").apply { writeText("== A tavola ==\nPiadina.\n") }
        val regions = tmp.newFile("regions.tsv").apply { writeText("san-marino\t${dump.path}\thttps://it.wikivoyage.org/wiki/San_Marino\n") }
        val jsonl = tmp.newFile("wp.jsonl").apply { writeText(line("san-marino", "San Marino", "== Cucina ==\n$long") + "\n") }
        val first = File(tmp.root, "first.db")

        runGenerateGuides("--wikipedia", jsonl.path, regions.path, first.path)
        val sql = "SELECT category, title, sourceUrl FROM guide_sections WHERE regionId = 'san-marino' AND category = 'CIBO_BEVANDE' ORDER BY rowid"
        val expected = listOf(
            listOf("CIBO_BEVANDE", "A tavola", "https://it.wikivoyage.org/wiki/San_Marino"),
            listOf("CIBO_BEVANDE", "Cucina", "https://it.wikipedia.org/wiki/San_Marino"),
        )
        assertEquals(expected, readRows(first, sql))

        val second = File(tmp.root, "second.db")
        runGenerateGuides("--wikipedia", File(tmp.root, "assente.jsonl").path, regions.path, second.path, first.path)
        // Contenuto uguale al pubblicato: nessun nuovo guides.db, le sezioni di Wikipedia ricopiate non cambiano nulla.
        assertTrue(!second.exists())
    }

    // main e' sovraccaricato da altri file dello stesso package: si chiama dalla classe generata.
    private fun runGenerateGuides(vararg args: String) {
        Class.forName("com.pockettravel.pipeline.GenerateGuideContentKt").getMethod("main", Array<String>::class.java).invoke(null, arrayOf(*args) as Any)
    }
}
