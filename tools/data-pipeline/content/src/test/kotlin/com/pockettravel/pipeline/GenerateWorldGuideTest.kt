package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenerateWorldGuideTest {

    private val url = "https://it.wikivoyage.org/wiki/Jet_lag"

    @Test
    fun `una sezione per titolo con il nome della pagina, l'introduzione in testa alla prima e senza Vedi anche`() {
        val dump = """
            {{Pagebanner|Jet lag banner.jpg}}
            Il '''jet lag''' e' il disturbo dovuto al cambio di fuso orario.
            == Prepararsi ==
            * Dormire di piu' nei giorni prima.
            == Recupero ==
            Un'ora al giorno.
            == Vedi anche ==
            * [[Salute]]
        """.trimIndent()

        val sections = parseWorldPage("Jet lag", "SALUTE", dump, url)

        assertEquals(listOf("Jet lag: Prepararsi", "Jet lag: Recupero"), sections.map { it.title })
        assertEquals("Il jet lag e' il disturbo dovuto al cambio di fuso orario.\n\n• Dormire di piu' nei giorni prima.", sections[0].body)
        assertEquals("Un'ora al giorno.", sections[1].body)
        assertTrue(sections.all { it.category == "SALUTE" && it.sourceUrl == url })
        // Una pagina senza sottotitoli resta la sola introduzione.
        assertEquals(listOf("Jet lag"), parseWorldPage("Jet lag", "SALUTE", "Solo introduzione.", url).map { it.title })
    }

    @Test
    fun `una pagina non scaricata riprende le sue sezioni pubblicate, non quelle delle altre pagine`() {
        val dump = File.createTempFile("pocket-travel-test", ".txt").apply { writeText("== Truffe dei taxi ==\nTassametro spento.") }
        val tsv = File.createTempFile("pocket-travel-test", ".tsv")
        val scamsUrl = "https://it.wikivoyage.org/wiki/Truffe_comuni"
        try {
            val redirect = File.createTempFile("pocket-travel-test", ".txt").apply { writeText("#RINVIA [[Salute]]") }
            redirect.deleteOnExit()
            tsv.writeText("SALUTE\tJet lag\t\t$url\nSICUREZZA\tTruffe comuni\t${dump.path}\t$scamsUrl\nSALUTE\tSalute\t${redirect.path}\thttps://it.wikivoyage.org/wiki/Salute\n")
            val published = listOf(
                GuideSectionRow("SALUTE", "Jet lag", "testo pubblicato", url),
                GuideSectionRow("SALUTE", "Mal di montagna", "altra pagina", "https://it.wikivoyage.org/wiki/Mal_di_montagna"),
            )

            val guide = readWorldGuide(tsv, published)

            assertEquals(WORLD_REGION_ID, guide?.regionId)
            assertEquals(listOf("Jet lag", "Truffe comuni: Truffe dei taxi"), guide?.sections?.map { it.title })
            assertEquals(scamsUrl, guide?.sections?.last()?.sourceUrl)
        } finally {
            dump.delete()
            tsv.delete()
        }
    }

    @Test
    fun `nessuna guida del mondo senza sezioni`() {
        val tsv = File.createTempFile("pocket-travel-test", ".tsv")
        try {
            tsv.writeText("SALUTE\tJet lag\t\t$url\n")
            assertNull(readWorldGuide(tsv, emptyList()))
        } finally {
            tsv.delete()
        }
    }

    @Test
    fun `nessuna regione di regions sh usa l'id riservato del mondo`() {
        val script = File("../scripts/regions.sh").takeIf { it.exists() } ?: File("scripts/regions.sh")
        val regionIds = script.readLines().map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').substringBefore('|') }
        assertTrue("nessuna regione letta da ${script.path}", regionIds.size > 100)
        assertFalse(regionIds.any { it.startsWith("_") })
    }
}
