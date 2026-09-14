package com.pockettravel.core.content

import com.pockettravel.core.data.GuideCategory
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione: estratto reale delle sezioni "Get in"/"Respect" dell'articolo
 * Wikivoyage "Japan" (scaricato da en.wikivoyage.org/w/index.php?title=Japan&action=raw il
 * 2026-09-10), non un fixture scritto a mano. A differenza del fixture sintetico di
 * WikivoyageDumpParserTest — che ha solo titoli di primo livello — un articolo Wikivoyage reale
 * ha quasi sempre sotto-titoli ===/==== dentro le sezioni mappate: prima del fix, ogni
 * sotto-titolo veniva trattato come un nuovo titolo di sezione (non mappato), troncando
 * silenziosamente tutto il testo successivo. Copre anche markup reale non presente nel fixture
 * sintetico: link a file immagine ([[File:...]]), link esterni con e senza testo visibile
 * ([https://... testo]), tag HTML via CSS inline (<span style="...">).
 */
class WikivoyageDumpParserRealDataTest {

    private val parser = WikivoyageDumpParser()
    private val realDump = File("src/test/resources/wikivoyage-japan-real.txt").readText()

    @Test
    fun `non tronca il testo dopo il primo sotto-titolo di una sezione mappata`() {
        val sections = parser.parse("japan", SOURCE_URL, realDump)
        val getIn = sections.single { it.category == GuideCategory.DOGANE }

        // "Transit without a visa" e' un sotto-titolo (===...===) dentro "Get in": il suo
        // testo compare solo se il parser non ha smesso di leggere la sezione prima di lui.
        assertTrue(getIn.body.contains("Transit without a visa"))
        // "Residence Card" e' un sotto-titolo successivo, ancora piu' a valle nella sezione.
        assertTrue(getIn.body.contains("Residence Card"))
    }

    @Test
    fun `non lascia markup wikitext grezzo nel corpo`() {
        val sections = parser.parse("japan", SOURCE_URL, realDump)
        val bodies = sections.joinToString("\n") { it.body }

        assertFalse(bodies.contains("[["))
        assertFalse(bodies.contains("]]"))
        assertFalse(bodies.contains("{{"))
        assertFalse(bodies.contains("[http"))
        assertFalse(Regex("""={2,}""").containsMatchIn(bodies))
    }

    @Test
    fun `converte i link esterni nel solo testo visibile`() {
        val sections = parser.parse("japan", SOURCE_URL, realDump)
        val getIn = sections.single { it.category == GuideCategory.DOGANE }

        assertTrue(getIn.body.contains("Citizens of many countries"))
    }

    private companion object {
        const val SOURCE_URL = "https://en.wikivoyage.org/wiki/Japan"
    }
}
