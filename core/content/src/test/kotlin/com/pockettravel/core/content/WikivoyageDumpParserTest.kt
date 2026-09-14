package com.pockettravel.core.content

import com.pockettravel.core.data.GuideCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WikivoyageDumpParserTest {

    private val parser = WikivoyageDumpParser()

    private val sampleDump = """
        == Understand ==
        Japan is an island nation in East Asia.

        == Get in ==
        Most visitors can enter visa-free for up to 90 days. Declare cash over ¥1,000,000 at [[customs]].

        == Get around ==
        The rail network is extensive. A Japan Rail Pass covers most long-distance travel.

        == Stay healthy ==
        Tap water is safe to drink. No vaccinations are required for entry.

        == Stay safe ==
        Japan has a very low crime rate. Natural disasters like earthquakes are a real risk.

        == Respect ==
        Bowing is the customary greeting. Remove your shoes before entering a home.

        == Talk ==
        '''Konnichiwa''' means hello. Few people speak English outside major cities.
    """.trimIndent()

    @Test
    fun `extracts only headings mapped to a guide category`() {
        val sections = parser.parse("japan", SOURCE_URL, sampleDump)

        assertEquals(6, sections.size)
        assertTrue(sections.none { it.title.equals("Understand", ignoreCase = true) })
    }

    @Test
    fun `maps each mapped heading to its guide category`() {
        val sections = parser.parse("japan", SOURCE_URL, sampleDump)
        val byCategory = sections.associateBy { it.category }

        assertEquals(GuideCategory.entries.toSet(), byCategory.keys)
        assertTrue(byCategory.getValue(GuideCategory.USI_COSTUMI).body.contains("Bowing"))
        assertTrue(byCategory.getValue(GuideCategory.DOGANE).body.contains("customs"))
        assertTrue(byCategory.getValue(GuideCategory.TRASPORTI).body.contains("Japan Rail Pass"))
        assertTrue(byCategory.getValue(GuideCategory.SALUTE).body.contains("Tap water"))
        assertTrue(byCategory.getValue(GuideCategory.SICUREZZA).body.contains("earthquakes"))
    }

    @Test
    fun `strips wikitext markup from the body`() {
        val sections = parser.parse("japan", SOURCE_URL, sampleDump)
        val talk = sections.first { it.category == GuideCategory.FRASI_UTILI }

        assertFalse(talk.body.contains("'''"))
        assertTrue(talk.body.contains("Konnichiwa"))
    }

    @Test
    fun `tags every section with the given region and source url`() {
        val sections = parser.parse("japan", SOURCE_URL, sampleDump)

        assertTrue(sections.all { it.regionId == "japan" })
        assertTrue(sections.all { it.sourceUrl == SOURCE_URL })
    }

    private companion object {
        const val SOURCE_URL = "https://en.wikivoyage.org/wiki/Japan"
    }
}
