package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenerateGeneralGuideTest {

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

        val sections = parseGeneralGuidePage("Jet lag", "SALUTE", dump, url)

        assertEquals(listOf("Jet lag: Prepararsi", "Jet lag: Recupero"), sections.map { it.title })
        assertEquals("Il jet lag e' il disturbo dovuto al cambio di fuso orario.\n\n• Dormire di piu' nei giorni prima.", sections[0].body)
        assertEquals("Un'ora al giorno.", sections[1].body)
        assertTrue(sections.all { it.category == "SALUTE" && it.sourceUrl == url })
        // Una pagina senza sottotitoli resta la sola introduzione.
        assertEquals(listOf("Jet lag"), parseGeneralGuidePage("Jet lag", "SALUTE", "Solo introduzione.", url).map { it.title })
    }

    @Test
    fun `la nota per chi scrive la pagina non entra nel testo`() {
        val dump = ": ''See [[Wikivoyage:Currency]] for editing guidelines for currency expressions.''\n" +
            "Cash is still useful.\n== Cards ==\nVisa is widely accepted."

        val sections = parseGeneralGuidePage("Money", "ACQUISTI", dump, "https://en.wikivoyage.org/wiki/Money")

        assertEquals("Cash is still useful.\n\nVisa is widely accepted.", sections[0].body)
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

            val guide = readGeneralGuide(tsv, published)

            assertEquals(GENERAL_GUIDE_REGION_ID, guide?.regionId)
            assertEquals(listOf("Jet lag", "Truffe comuni: Truffe dei taxi"), guide?.sections?.map { it.title })
            assertEquals(scamsUrl, guide?.sections?.last()?.sourceUrl)
        } finally {
            dump.delete()
            tsv.delete()
        }
    }

    @Test
    fun `nessuna guida della guida generale senza sezioni`() {
        val tsv = File.createTempFile("pocket-travel-test", ".tsv")
        try {
            tsv.writeText("SALUTE\tJet lag\t\t$url\n")
            assertNull(readGeneralGuide(tsv, emptyList()))
        } finally {
            tsv.delete()
        }
    }

    @Test
    fun `nessuna regione di regions sh usa l'id riservato della guida generale`() {
        val script = File("../scripts/regions.sh").takeIf { it.exists() } ?: File("scripts/regions.sh")
        val regionIds = script.readLines().map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').substringBefore('|') }
        assertTrue("nessuna regione letta da ${script.path}", regionIds.size > 100)
        assertFalse(regionIds.any { it.startsWith("_") })
    }

    // --- parseGeneralGuidePage: casi limite ---

    @Test
    fun `i titoli di rimando e di indice si saltano senza distinguere le maiuscole, in italiano e in inglese`() {
        val skipped = listOf(
            "See also", "SEE ALSO", "Vedi anche", "Articoli correlati", "Contents", "Note", "Help page", "Information by region",
            "Informazioni per regione", "Health-related attractions", "Attrazioni legate alla salute",
        )
        skipped.forEach { heading ->
            val sections = parseGeneralGuidePage("Salute", "SALUTE", "== Consigli ==\nBevete.\n== $heading ==\nTesto da saltare.", url)

            assertEquals(heading, listOf("Salute: Consigli"), sections.map { it.title })
        }
    }

    @Test
    fun `l'introduzione si unisce alla prima sezione solo se esiste e se c'e' una sezione dopo`() {
        // Introduzione e due sezioni: la prima sezione prende l'introduzione, la seconda resta com'e'.
        val withIntro = parseGeneralGuidePage("Mance", "ACQUISTI", "Le mance variano.\n== Europa ==\nPoco.\n== Asia ==\nQuasi nulla.", url)
        assertEquals(listOf("Mance: Europa", "Mance: Asia"), withIntro.map { it.title })
        assertEquals("Le mance variano.\n\nPoco.", withIntro[0].body)
        assertEquals("Quasi nulla.", withIntro[1].body)

        // Senza introduzione (solo un template) la prima sezione e' la prima "==".
        val noIntro = parseGeneralGuidePage("Mance", "ACQUISTI", "{{Pagebanner|x.jpg}}\n== Europa ==\nPoco.", url)
        assertEquals(listOf("Mance: Europa"), noIntro.map { it.title })
        assertEquals("Poco.", noIntro.single().body)

        // Introduzione e solo sezioni da saltare: resta la sola introduzione con il titolo della pagina.
        val onlySkipped = parseGeneralGuidePage("Mance", "ACQUISTI", "Intro.\n== Vedi anche ==\n* [[Soldi]]", url)
        assertEquals(listOf("Mance"), onlySkipped.map { it.title })
    }

    @Test
    fun `una sezione vuota o fatta di soli template non crea la riga e un dump vuoto non da' sezioni`() {
        val sections = parseGeneralGuidePage("Mance", "ACQUISTI", "== Vuota ==\n{{Pricerange}}\n== Piena ==\nTesto.", url)

        assertEquals(listOf("Mance: Piena"), sections.map { it.title })
        assertEquals(emptyList<GuideSectionRow>(), parseGeneralGuidePage("Mance", "ACQUISTI", "", url))
    }

    @Test
    fun `i sottotitoli di livello tre restano nel corpo della sezione`() {
        val sections = parseGeneralGuidePage("Mance", "ACQUISTI", "== Europa ==\nIntro.\n=== Italia ===\nPoco.", url)

        assertEquals(1, sections.size)
        assertEquals("Intro.\n▸ Italia\nPoco.", sections.single().body)
    }

    // --- readGeneralGuide: casi limite ---

    private fun withFiles(block: (File, (String) -> File) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-general").toFile()
        try {
            block(File(dir, "guida.tsv")) { text -> File.createTempFile("dump", ".txt", dir).apply { writeText(text) } }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `un dump vuoto o un percorso mancante si trattano come pagina non scaricata`() {
        withFiles { tsv, dumpOf ->
            val empty = dumpOf("")
            val missing = File(empty.parentFile, "non-esiste.txt")
            tsv.writeText("SALUTE\tJet lag\t${empty.path}\t$url\nSALUTE\tAltra\t${missing.path}\thttps://it.wikivoyage.org/wiki/Altra\n")
            val published = listOf(
                GuideSectionRow("SALUTE", "Jet lag", "uno", url),
                GuideSectionRow("SALUTE", "Jet lag: Consigli", "due", url),
                GuideSectionRow("SALUTE", "Altra", "tre", "https://it.wikivoyage.org/wiki/Altra"),
            )

            val guide = readGeneralGuide(tsv, published)

            assertEquals(listOf("uno", "due", "tre"), guide?.sections?.map { it.body })
            assertEquals(url, guide?.sourceUrl)
        }
    }

    @Test
    fun `un rinvio si riconosce in italiano e in inglese, con maiuscole e spazi iniziali`() {
        listOf("#REDIRECT [[Salute]]", "#redirect [[Salute]]", "  \n#RINVIA [[Salute]]", "#Rinvia[[Salute]]").forEach { text ->
            withFiles { tsv, dumpOf ->
                val dump = dumpOf(text)
                tsv.writeText("SALUTE\tJet lag\t${dump.path}\t$url\n")

                val guide = readGeneralGuide(tsv, listOf(GuideSectionRow("SALUTE", "Jet lag", "pubblicato", url)))

                assertEquals(text, listOf("pubblicato"), guide?.sections?.map { it.body })
            }
        }
    }

    @Test
    fun `un cancelletto che non e' in testa al dump non e' un rinvio`() {
        withFiles { tsv, dumpOf ->
            val dump = dumpOf("Testo normale.\n#REDIRECT in mezzo\n== Sezione ==\nCorpo.")
            tsv.writeText("SALUTE\tJet lag\t${dump.path}\t$url\n")

            val guide = readGeneralGuide(tsv, listOf(GuideSectionRow("SALUTE", "Jet lag", "pubblicato", url)))

            assertEquals(listOf("Jet lag: Sezione"), guide?.sections?.map { it.title })
        }
    }

    @Test
    fun `tsv vuoto o di sole righe vuote non da' guida, e le pagine escono nell'ordine del file`() {
        withFiles { tsv, dumpOf ->
            tsv.writeText("\n\n")
            assertNull(readGeneralGuide(tsv, emptyList()))

            val first = dumpOf("== Uno ==\nA.")
            val second = dumpOf("== Due ==\nB.")
            tsv.writeText("SALUTE\tPrima\t${first.path}\tu1\n\nSICUREZZA\tSeconda\t${second.path}\tu2\n")

            val guide = readGeneralGuide(tsv, emptyList())

            assertEquals(listOf("Prima: Uno", "Seconda: Due"), guide?.sections?.map { it.title })
            assertEquals(listOf("SALUTE", "SICUREZZA"), guide?.sections?.map { it.category })
            assertEquals(GENERAL_GUIDE_REGION_ID, guide?.regionId)
            assertEquals("u1", guide?.sourceUrl)
        }
    }

    @Test
    fun `una riga del tsv con meno di quattro colonne fa fallire la lettura`() {
        withFiles { tsv, _ ->
            tsv.writeText("SALUTE\tJet lag\t\n")

            org.junit.Assert.assertThrows(IndexOutOfBoundsException::class.java) { readGeneralGuide(tsv, emptyList()) }
        }
    }

    @Test
    fun `le sezioni di una guida generale scritte in guides db si ritrovano con il suo regionId`() {
        val db = File.createTempFile("pocket-travel-test", ".guides.db").also { it.delete() }
        try {
            val sections = parseGeneralGuidePage("Jet lag", "SALUTE", "Intro.\n== Consigli ==\nBevete.", url)
            writeGuidesDb(listOf(RegionGuide(GENERAL_GUIDE_REGION_ID, url, sections)), db)

            assertEquals(listOf(listOf<String?>(GENERAL_GUIDE_REGION_ID, "Jet lag: Consigli")), readRows(db, "SELECT regionId, title FROM guide_sections"))
            // La guida generale non ha numeri di emergenza: nessuna riga in emergency_numbers.
            assertEquals(emptyList<List<String?>>(), readRows(db, "SELECT * FROM emergency_numbers"))
        } finally {
            db.delete()
        }
    }
}
