package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pulizia del wikitext delle guide: casi trovati nelle guide pubblicate a fine settembre 2026. */
class GuideMarkupTest {
    private fun body(wikitext: String): String = parseWikivoyageDump("== Sicurezza ==\n$wikitext\n").single().body

    @Test
    fun `template annidati e valute`() {
        assertEquals(
            "Prelievi di almeno 500 ALL. Tel +355 674542627.",
            body("Prelievi di almeno {{ALL|500}}{{Dead link|date=2024 |note={{x|y}}}}. Tel {{phone|+355 674542627}}."),
        )
    }

    @Test
    fun `listing con template dentro`() {
        assertEquals("Hotel Roma: camere a 50 EUR", body("{{sleep|name=Hotel Roma|content=camere a {{EUR|50}}|price=}}"))
    }

    @Test
    fun `immagini con link nella didascalia, gallerie e tabelle spariscono`() {
        assertEquals(
            "Prima. Dopo.",
            body(
                "Prima.[[File:Porto.jpg|thumb|Il porto con la nave [http://example.org Irish Ferries] e il [[Duomo]]]]\n" +
                    "<gallery>\nFile:Pizza.jpg|Pizza\n</gallery>{| class=\"wikitable\"\n|-\n| a || b\n|}Dopo.",
            ),
        )
    }

    @Test
    fun `link esterni senza testo non si mangiano la riga dopo`() {
        assertEquals(
            "• Da Bari (3 ore).\n• Traghetto di European Seaways da Brindisi.",
            body("* Da Bari (3 ore).[http://www.apdurres.com.al]\n* Traghetto di [http://www.europeanseaways.com European Seaways] da [[Brindisi]]."),
        )
    }

    @Test
    fun `apostrofo attaccato a corsivo e grassetto resta`() {
        assertEquals("nel 2017 l'Arte dei Pizzaiuoli e l'Opera", body("nel 2017 l'''Arte dei Pizzaiuoli'' e l''''Opera'''"))
    }

    @Test
    fun `a capo del wikitext dentro un paragrafo e righe vuote superflue`() {
        assertEquals(
            "Una frase spezzata su due righe.\n▸ Titolo\n• Prima voce\n• Seconda voce\n\nNuovo paragrafo.",
            body("Una frase spezzata\nsu due righe.\n===Titolo===\n\n* Prima voce\n\n* Seconda voce\n\nNuovo paragrafo."),
        )
    }

    @Test
    fun `spazi doppi e spazi prima della punteggiatura`() {
        assertEquals("Due spazi. Poi una virgola, fine.", body("Due  spazi.  Poi una virgola {{nota}} , fine."))
    }

    @Test
    fun `testo vuoto, senza intestazioni o con sezioni vuote non da' sezioni`() {
        assertEquals(emptyList<GuideSectionRow>(), parseWikivoyageDump(""))
        assertEquals(emptyList<GuideSectionRow>(), parseWikivoyageDump("Solo testo senza intestazioni.\n"))
        assertEquals(emptyList<GuideSectionRow>(), parseWikivoyageDump("== Sicurezza ==\n{{template}}\n<!-- niente -->\n"))
    }

    @Test
    fun `un'intestazione con tre uguali e' un sottotitolo e non apre una sezione`() {
        val sections = parseWikivoyageDump("== Sicurezza ==\nPrima.\n=== Dettagli ===\nDopo.\n")

        assertEquals(1, sections.size)
        assertEquals("Prima.\n▸ Dettagli\nDopo.", sections.single().body)
    }
}
