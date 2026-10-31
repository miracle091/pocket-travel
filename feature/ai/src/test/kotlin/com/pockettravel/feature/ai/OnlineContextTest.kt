package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Il contesto dei modelli online: blocchi con la fonte, budget proprio, nota solo se passata, citazioni delle sezioni entrate.
class OnlineContextTest {

    private fun section(title: String, body: String) = AssistantSection(
        body = body,
        category = GuideCategory.COSA_VEDERE,
        citation = "Fonte: Wikivoyage, sezione $title — https://it.wikivoyage.org/wiki/Roma",
    )

    private val note = Note(id = 1, title = "Volo", body = "Martedi alle 18", updatedAt = 0)

    @Test
    fun `every section is a block with its source and the citation of each is returned`() {
        val context = buildOnlineContext(listOf(section("Cosa vedere", "Il Colosseo"), section("Dormire", "Hotel Roma")), note = null)

        assertEquals(
            "<contesto fonte=\"Wikivoyage, sezione Cosa vedere\">\nIl Colosseo\n</contesto>\n\n" +
                "<contesto fonte=\"Wikivoyage, sezione Dormire\">\nHotel Roma\n</contesto>",
            context.text,
        )
        assertEquals(listOf(section("Cosa vedere", "").citation, section("Dormire", "").citation), context.citations)
    }

    @Test
    fun `the budget includes the markers and the sections that do not fit are not cited`() {
        val sections = (1..6).map { section("S$it", "parola ".repeat(300).trim()) }

        val context = buildOnlineContext(sections, note = null, maxChars = ONLINE_MAX_CHARS)

        assertTrue(context.text.length <= ONLINE_MAX_CHARS)
        assertTrue(context.text.length > ONLINE_MAX_CHARS - 2_000)
        assertEquals(context.text.split("<contesto ").size - 1, context.citations.size)

        val small = buildOnlineContext(sections, note = null, maxChars = 700)
        assertTrue(small.text.length <= 700)
        assertTrue(small.citations.size < sections.size)
    }

    @Test
    fun `a long section keeps the paragraphs about the question`() {
        val body = (1..200).joinToString("\n") { if (it == 150) "il mercato di Testaccio" else "riga di riempimento numero $it" }

        val context = buildOnlineContext(listOf(section("Mangiare", body)), note = null, maxChars = 600, focusStems = setOf("testa"))

        assertTrue(context.text.length <= 600)
        assertTrue(context.text.contains("il mercato di Testaccio"))
    }

    @Test
    fun `the note is a block at the end only when it is passed`() {
        val sections = listOf(section("Cosa vedere", "Il Colosseo"))

        val without = buildOnlineContext(sections, note = null)
        val with = buildOnlineContext(sections, note, language = "en")

        assertFalse(without.text.contains("Martedi"))
        assertTrue(with.text.endsWith("<context source=\"Personal note\">\nVolo\nMartedi alle 18\n</context>"))
        // La nota non e' una fonte della guida: non entra nelle citazioni.
        assertEquals(without.citations, with.citations)
    }

    @Test
    fun `the note keeps its space and the whole context stays in the budget`() {
        val sections = listOf(section("Cosa vedere", "parola ".repeat(500)))
        val longNote = note.copy(body = "n".repeat(2_000))

        val context = buildOnlineContext(sections, longNote, maxChars = 1_500)

        assertTrue(context.text.length <= 1_500)
        assertTrue(context.text.contains("<contesto fonte=\"Nota personale\">"))
        assertTrue(context.text.contains("Wikivoyage, sezione Cosa vedere"))
    }
}
