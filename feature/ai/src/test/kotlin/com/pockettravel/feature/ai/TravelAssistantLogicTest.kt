package com.pockettravel.feature.ai

import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelAssistantLogicTest {

    @Test
    fun `builds an OR query from words of at least four characters`() {
        val query = buildFtsQuery("Posso portare farmaci da banco in Giappone?", "giappone")

        assertEquals("posso OR portare OR farmaci OR banco", query)
    }

    @Test
    fun `operatori FTS scritti in maiuscolo nella domanda restano parole`() {
        val query = buildFtsQuery("Hotel NEAR stazione OR aeroporto", "italia")

        assertEquals("hotel OR near OR stazione OR aeroporto", query)
    }

    @Test
    fun `drops punctuation from each token`() {
        val query = buildFtsQuery("vaccini, dogana; normativa?!", "italia")

        assertEquals("vaccini OR dogana OR normativa", query)
    }

    @Test
    fun `returns blank for a question made only of short words`() {
        val query = buildFtsQuery("hi to a in", "italia")

        assertTrue(query.isBlank())
    }

    @Test
    fun `drops tokens that are part of the region's display name`() {
        val query = buildFtsQuery("Quale valuta si usa a San Marino?", "san-marino")

        assertEquals("quale OR valuta", query)
    }

    @Test
    fun `region name tokens are excluded even when they are the only long words`() {
        val query = buildFtsQuery("E il Giappone?", "giappone")

        assertTrue(query.isBlank())
    }

    @Test
    fun `truncates context beyond the configured character limit`() {
        val longContext = "a".repeat(3000)

        val truncated = truncateContext(longContext, maxChars = 2000)

        assertEquals(2000, truncated.length)
    }

    @Test
    fun `leaves short context untouched`() {
        val shortContext = "Dogane: dichiarare importi oltre una certa soglia."

        assertEquals(shortContext, truncateContext(shortContext, maxChars = 2000))
    }

    private fun guideSection(title: String, body: String = "corpo", category: GuideCategory = GuideCategory.TRASPORTI) = GuideSection(
        regionId = "italia",
        category = category,
        title = title,
        body = body,
        sourceUrl = "https://it.wikivoyage.org/wiki/Italia",
    )

    private fun citySection(city: String, title: String, body: String = "corpo") = CitySection(
        regionId = "italia",
        city = city,
        category = GuideCategory.COSA_VEDERE,
        title = title,
        body = body,
        sourceUrl = "https://it.wikivoyage.org/wiki/$city",
    )

    @Test
    fun `mergeBestSections unisce guida e citta' per punteggio, non per fonte`() {
        val result = mergeBestSections(
            guideMatches = listOf(guideSection("Dogane", "corpo dogane") to 1.0, guideSection("Trasporti", "corpo trasporti") to 5.0),
            cityMatches = listOf(citySection("Roma", "Cosa vedere a Roma") to 3.0),
            limit = 3,
        )

        assertEquals(3, result.size)
        assertEquals("corpo trasporti", result[0].body)
        assertEquals("corpo dogane", result[2].body)
    }

    @Test
    fun `mergeBestSections taglia al limite anche con piu' candidati di entrambe le fonti`() {
        val guide = listOf(guideSection("A", "corpo A") to 1.0, guideSection("B", "corpo B") to 2.0)
        val city = listOf(citySection("Roma", "C", "corpo C") to 3.0, citySection("Roma", "D", "corpo D") to 4.0)

        val result = mergeBestSections(guide, city, limit = 2)

        assertEquals(listOf("corpo D", "corpo C"), result.map { it.body })
    }

    @Test
    fun `mergeBestSections cita la citta' nelle sezioni delle guide di citta'`() {
        val result = mergeBestSections(emptyList(), listOf(citySection("Roma", "Cosa vedere") to 1.0), limit = 3)

        assertTrue(result.single().citation.contains("(Roma)"))
    }

    @Test
    fun `buildOnDeviceContext aggiunge la nota etichettata dopo le sezioni`() {
        val sections = mergeBestSections(listOf(guideSection("Dogane", "corpo dogane") to 1.0), emptyList(), limit = 3)
        val note = Note(id = 1, title = "Volo di ritorno", body = "Martedi alle 18", updatedAt = 0)

        val context = buildOnDeviceContext(sections, note)

        assertEquals("corpo dogane\n\nNota personale: Volo di ritorno\nMartedi alle 18", context)
        assertEquals("corpo dogane\n\nPersonal note: Volo di ritorno\nMartedi alle 18", buildOnDeviceContext(sections, note, language = "en"))
    }

    @Test
    fun `buildOnDeviceContext senza nota resta solo le sezioni`() {
        val sections = mergeBestSections(listOf(guideSection("Dogane", "corpo dogane") to 1.0), emptyList(), limit = 3)

        assertEquals("corpo dogane", buildOnDeviceContext(sections, note = null))
    }

    @Test
    fun `buildOnDeviceContext resta nello stesso limite di caratteri con o senza nota`() {
        val sections = mergeBestSections(listOf(guideSection("Dogane", "a".repeat(1900)) to 1.0), emptyList(), limit = 3)
        val note = Note(id = 1, title = "t", body = "b".repeat(500), updatedAt = 0)

        val context = buildOnDeviceContext(sections, note, maxChars = 2000)

        assertEquals(2000, context.length)
        // la nota resta (accorciata al suo tetto), sono le sezioni a cedere spazio
        assertTrue(context.contains("Nota personale: t"))
    }
}
