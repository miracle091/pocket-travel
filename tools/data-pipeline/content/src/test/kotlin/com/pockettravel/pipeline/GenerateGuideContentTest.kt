package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class GenerateGuideContentTest {

    @Test
    fun `estrae solo le sezioni con categoria mappata dall'estratto wikivoyage di test`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            val dumpText = File("testdata/wikivoyage-excerpt.txt").readText()
            val sections = parseWikivoyageDump(dumpText)
            writeGuidesDb(listOf(RegionGuide("test-region", "https://example.org/test-region", sections)), outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery(
                        "SELECT category, title, sourceUrl FROM guide_sections ORDER BY category"
                    )
                    // "See" come nelle guide delle citta': l'assistente risponde a "cosa vedere" anche senza la regione.
                    assertEquals(true, rs.next())
                    assertEquals("COSA_VEDERE", rs.getString("category"))
                    assertEquals("See", rs.getString("title"))

                    assertEquals(true, rs.next())
                    assertEquals("SICUREZZA", rs.getString("category"))
                    assertEquals("Stay safe", rs.getString("title"))
                    assertEquals("https://example.org/test-region", rs.getString("sourceUrl"))

                    assertEquals(true, rs.next())
                    assertEquals("TRASPORTI", rs.getString("category"))
                    assertEquals("Get around", rs.getString("title"))

                    // "Understand" non ha una categoria mappata: deve essere scartata.
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `estrae le sezioni anche dai titoli italiani (build-region_sh preferisce ora Wikivoyage IT)`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            val dumpText = File("testdata/wikivoyage-excerpt-it.txt").readText()
            val sections = parseWikivoyageDump(dumpText)
            writeGuidesDb(listOf(RegionGuide("test-region", "https://it.wikivoyage.org/wiki/Test", sections)), outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery(
                        "SELECT category, title, sourceUrl FROM guide_sections ORDER BY category"
                    )
                    assertEquals(true, rs.next())
                    assertEquals("COSA_VEDERE", rs.getString("category"))
                    assertEquals("Cosa vedere", rs.getString("title"))

                    assertEquals(true, rs.next())
                    assertEquals("SICUREZZA", rs.getString("category"))
                    assertEquals("Sicurezza", rs.getString("title"))
                    assertEquals("https://it.wikivoyage.org/wiki/Test", rs.getString("sourceUrl"))

                    assertEquals(true, rs.next())
                    assertEquals("TRASPORTI", rs.getString("category"))
                    assertEquals("Come spostarsi", rs.getString("title"))

                    // "Da sapere" non ha una categoria mappata: deve essere scartata.
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `ripulisce citazioni, elenchi e sottotitoli vuoti dal corpo per la leggibilita'`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            val dumpText = File("testdata/wikivoyage-excerpt-readability.txt").readText()
            val sections = parseWikivoyageDump(dumpText)
            writeGuidesDb(listOf(RegionGuide("test-region", "https://example.org/test-region", sections)), outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT body FROM guide_sections WHERE category = 'SICUREZZA'")
                    assertEquals(true, rs.next())
                    assertEquals(
                        "The area is generally safe. Watch for bicycles.\n\n" +
                            "▸ Shopping\n" +
                            "Popular purchases include:\n" +
                            "• Perfume\n" +
                            "• Cigarettes\n" +
                            "• Alcohol\n" +
                            "▸ Souvenirs\n" +
                            "• Stamps",
                        rs.getString("body"),
                    )
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `i template marker, see e IATA tengono nome e descrizione invece di sparire`() {
        val dumpText = """
            == Come arrivare ==
            * L'{{marker|tipo=go |nome=[[Aeroporto di Firenze-Peretola|Aeroporto Amerigo Vespucci]] |lat=43.8}} ({{IATA|FLR}}), situato a Peretola.
            Si raggiunge l'ingresso del {{see
            | nome=Castello di San Giorgio | alt= | sito=https://example.org
            | descrizione=,l'attrazione piu' visitata.
            }} Poi {{Pricerange|a}}() si prosegue.
        """.trimIndent()

        val body = parseWikivoyageDump(dumpText).single().body

        assertEquals(
            "• L'Aeroporto Amerigo Vespucci (FLR), situato a Peretola.\n" +
                "Si raggiunge l'ingresso del Castello di San Giorgio, l'attrazione piu' visitata. Poi si prosegue.",
            body,
        )
    }

    @Test
    fun `guides db contiene le sezioni di tutte le regioni e i numeri di emergenza di quelle mappate`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            writeGuidesDb(
                listOf(
                    RegionGuide("italia", "https://it.wikivoyage.org/wiki/Italia", listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "a"))),
                    RegionGuide("regione-sconosciuta", "https://example.org", listOf(GuideSectionRow("TRASPORTI", "Get around", "b"))),
                ),
                outputDb,
            )

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val sections = statement.executeQuery("SELECT regionId FROM guide_sections ORDER BY regionId")
                    assertEquals(true, sections.next())
                    assertEquals("italia", sections.getString(1))
                    assertEquals(true, sections.next())
                    assertEquals("regione-sconosciuta", sections.getString(1))
                    assertEquals(false, sections.next())

                    val numbers = statement.executeQuery("SELECT regionId, general FROM emergency_numbers")
                    assertEquals(true, numbers.next())
                    assertEquals("italia", numbers.getString("regionId"))
                    assertEquals("112", numbers.getString("general"))
                    assertEquals(false, numbers.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `sameGuidesContent ignora l'ordine delle regioni ma non un testo cambiato`() {
        val a = File.createTempFile("pocket-travel-test", ".guides.db")
        val b = File.createTempFile("pocket-travel-test", ".guides.db")
        val c = File.createTempFile("pocket-travel-test", ".guides.db")
        listOf(a, b, c).forEach { it.delete() }
        val italia = RegionGuide("italia", "https://it.wikivoyage.org/wiki/Italia", listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "testo")))
        val giappone = RegionGuide("giappone", "https://it.wikivoyage.org/wiki/Giappone", listOf(GuideSectionRow("SALUTE", "Salute", "testo")))

        try {
            writeGuidesDb(listOf(italia, giappone), a)
            writeGuidesDb(listOf(giappone, italia), b)
            writeGuidesDb(listOf(italia, giappone.copy(sections = listOf(GuideSectionRow("SALUTE", "Salute", "testo nuovo")))), c)

            assertEquals(true, sameGuidesContent(a, b))
            assertEquals(false, sameGuidesContent(a, c))
        } finally {
            listOf(a, b, c).forEach { it.delete() }
        }
    }

    @Test
    fun `sezione Fatti rapidi dai campi del QuickbarCountry e dai numeri di emergenza`() {
        val dump = """
            {{QuickbarCountry
            | Lingua = Italiano, [[Tedesco]] ([[Trentino-Alto Adige]])
            | Elettricità = 230V/50Hz (presa italiana, europea e tedesca)
            | Fuso orario = UTC+1
            }}

            == Sicurezza ==
            Zona tranquilla.
        """.trimIndent()

        val guide = regionGuideFromDumps("italia", dump, "https://it.wikivoyage.org/wiki/Italia", null, "")

        val fattiRapidi = guide.sections.single { it.category == "FATTI_RAPIDI" }
        assertEquals("Fatti rapidi", fattiRapidi.title)
        // Campi della pagina Wikivoyage, gli altri da Wikidata (countries.tsv), in ordine fisso.
        assertEquals(
            "Capitale: Roma\n" +
                "Lingua: Italiano, Tedesco (Trentino-Alto Adige)\n" +
                "Elettricità: 230V/50Hz (presa italiana, europea e tedesca)\n" +
                "Fuso orario: UTC+1\n" +
                "Valuta: euro (EUR)\n" +
                "Prefisso telefonico: +39\n" +
                "Lato di guida: destra\n" +
                "Numeri di emergenza: Generale 112, Polizia 113, Ambulanza 118, Vigili del fuoco 115",
            fattiRapidi.body,
        )
    }

    @Test
    fun `Fatti rapidi su piu' righe (Valuta) diventa una riga sola con le voci separate da virgola`() {
        val dump = """
            {{QuickbarCountry
            | Valuta=
            *bolívar venezuelano sovrano (VES)<br>
            *petro (criptovaluta)<br>
            *dollaro statunitense
            | Fuso orario = UTC-4:30
            }}
        """.trimIndent()

        val fattiRapidi = quickFactsSection("venezuela-test", dump)

        // Ordine fisso (Lingua, Elettricità, Fuso orario, Valuta), non quello di comparsa nel wikitext.
        assertEquals(
            "Fuso orario: UTC-4:30\nValuta: bolívar venezuelano sovrano (VES), petro (criptovaluta), dollaro statunitense",
            fattiRapidi?.body,
        )
    }

    @Test
    fun `nessuna sezione Fatti rapidi senza campi Quickbar ne' numeri di emergenza`() {
        assertEquals(null, quickFactsSection("regione-sconosciuta", "{{QuickbarCountry\n| Banner = x.jpg\n}}"))
    }

    @Test
    fun `usa la pagina inglese solo se quella italiana non da' sezioni`() {
        val itSoloTitoli = "== Sicurezza ==\n\n== A tavola ==\n"
        val itConTesto = "== Sicurezza ==\nZona tranquilla.\n"
        val en = "== Stay safe ==\nQuiet area.\n"

        val fallback = regionGuideFromDumps("r", itSoloTitoli, "https://it.example/r", en, "https://en.example/r")
        assertEquals("https://en.example/r", fallback.sourceUrl)
        assertEquals(listOf("Stay safe"), fallback.sections.map { it.title })

        val italiana = regionGuideFromDumps("r", itConTesto, "https://it.example/r", en, "https://en.example/r")
        assertEquals("https://it.example/r", italiana.sourceUrl)
        assertEquals(listOf("Sicurezza"), italiana.sections.map { it.title })

        val senzaInglese = regionGuideFromDumps("r", itSoloTitoli, "https://it.example/r", null, "")
        assertEquals("https://it.example/r", senzaInglese.sourceUrl)
        assertEquals(0, senzaInglese.sections.size)
    }

    @Test
    fun `campo vuoto del Quickbar seguito da un altro sulla stessa riga`() {
        val dump = "{{QuickbarCountry\n|Elettricità= | Fuso orario = UTC-3\n|Valuta=peso\n}}"
        assertEquals("Fuso orario: UTC-3\nValuta: peso", quickFactsSection("zz", dump)?.body)
    }

    @Test
    fun `fatti rapidi inglesi dai campi neutri del Quickbar italiano`() {
        assertEquals("220V/50Hz (European plug)", englishElectricity("220V/50Hz (presa europea)"))
        assertEquals("230V/50Hz (European and British plugs)", englishElectricity("230V/50Hz (presa europea e britannica)"))
        assertEquals("240V/50Hz (Australian, Chinese and Argentine plugs)", englishElectricity("240V/50Hz (presa australiana/cinese/argentina)"))
        assertEquals("220V/50Hz", englishElectricity("220V/50Hz (presa strana)"))
        assertEquals(null, englishElectricity("dipende dalla zona"))
        assertEquals("UTC+5:30", englishTimeZone("UTC+5:30"))
        assertEquals("UTC-3, UTC-4", englishTimeZone("UTC-3 (costa orientale) e UTC-4 (costa occidentale)"))
        val dump = "{{QuickbarCountry\n|Lingua=Italiano\n|Elettricità=230V/50Hz (presa europea)\n|Fuso orario=UTC+1\n}}"
        val section = englishQuickFactsSection("italia", dump)
        assertEquals("Quick facts", section?.title)
        assertEquals(
            "Capital: Rome\nLanguage: Italian\nElectricity: 230V/50Hz (European plug)\nTime zone: UTC+1\nCurrency: euro (EUR)\n" +
                "Calling code: +39\nDriving side: right\nEmergency numbers: General 112, Police 113, Ambulance 118, Fire 115",
            section?.body,
        )
        // Senza pagina italiana, e per i campi italiani non traducibili, i dati di Wikidata.
        assertEquals(
            "Capital: Rome\nLanguage: Italian\nElectricity: 230 V, plug types C, F, L\nTime zone: UTC+1\nCurrency: euro (EUR)\n" +
                "Calling code: +39\nDriving side: right\nEmergency numbers: General 112, Police 113, Ambulance 118, Fire 115",
            englishQuickFactsSection("italia", "{{QuickbarCountry\n|Elettricità=dipende dalla zona\n}}")?.body,
        )
        assertEquals(null, englishQuickFactsSection("regione-sconosciuta", ""))
    }

    @Test
    fun `la sottosezione Costo della vita sparisce con tutto il suo testo`() {
        val raw = "Si paga in euro.\n=== Costo della vita ===\nGli stipendi sono bassi.\n==== Dettagli ====\nAffitti alti.\n" +
            "=== Acquisti ===\nNegozi aperti fino alle 20."
        val body = cleanBody(raw)
        assertTrue(body.contains("Si paga in euro."))
        assertTrue(body.contains("Negozi aperti fino alle 20."))
        assertFalse(body.contains("stipendi"))
        assertFalse(body.contains("Affitti"))
        assertFalse(body.contains("Costo della vita"))
        assertEquals("▸ Costs\nPrices.", cleanBody("=== Cost of living ===\nRent is high.\n=== Costs ===\nPrices."))
    }

    @Test
    fun `lingua inglese dai nomi italiani del Quickbar`() {
        assertEquals("Italian, German, French, Slovenian", englishLanguage("Italiano, Tedesco (Trentino-Alto Adige), Francese (Valle d'Aosta), Sloveno (Friuli-Venezia Giulia)"))
        assertEquals("Dutch, French, German", englishLanguage("Olandese, Francese, Tedesco"))
        assertEquals("German", englishLanguage("Tedesco, regionale: croato, sloveno e ungherese"))
        assertEquals(null, englishLanguage("lingua locale"))
    }

    @Test
    fun `le entita' html del wikitext diventano caratteri`() {
        val sections = parseWikivoyageDump("== Sicurezza ==\nRoma &mdash; Firenze: 30&nbsp;km, &laquo;tranquilla&raquo; &#8211; ok &#x2192; s&igrave;\n")
        assertEquals("Roma — Firenze: 30\u00A0km, «tranquilla» – ok → sì", sections.single().body)
    }

    @Test
    fun `entita' invisibili spariscono e le lettere con il caron diventano caratteri`() {
        assertEquals("rajavartijat/gränsbevakare, Prešhevo: roadblocks", cleanBody("rajavartijat/&ZeroWidthSpace;gränsbevakare, Pre&scaron;hevo: &lrm;roadblocks"))
    }

    @Test
    fun `spazi invisibili e trattini facoltativi spariscono dal testo`() {
        assertEquals("gazpacho al 10% del totale", cleanBody("gaz\u00ADpacho al\u200B 10%\u200B del totale"))
        // I segni di direzione servono al testo arabo: restano.
        assertEquals("ريال\u200E", cleanBody("ريال\u200E"))
    }

    @Test
    fun `spazi doppi, tabulazioni e spazi misti diventano uno spazio solo`() {
        assertEquals("a 25\u00A0km da Roma", cleanBody("a 25 \u00A0km da Roma"))
        assertEquals("Madagascar — Antananarivo", cleanBody("Madagascar —\tAntananarivo"))
        assertEquals("Budva:\u00A0+382 33", cleanBody("Budva:\u00A0 \u00A0 \u00A0+382 33"))
        assertEquals("Dopo il punto. Poi", cleanBody("Dopo il punto.  Poi"))
    }

    @Test
    fun `nessuno spazio prima di punteggiatura e dopo la parentesi aperta, anche senza interruzione`() {
        assertEquals("Durante il soggiorno:\n• state attenti", cleanBody("Durante il soggiorno\u202F:\n* state attenti"))
        assertEquals("costa (ISO code: AUD) e (€1)", cleanBody("costa ( ISO code: AUD) e ( €1)"))
        // Due righe unite da cleanBody: il capo con la virgola non lascia " ,".
        assertEquals("carburante, acqua e cibo", cleanBody("carburante\n, acqua e cibo"))
        // Dopo il pallino dell'elenco la punteggiatura resta (voce che iniziava con un template tolto).
        assertEquals("• , la strada", cleanBody("* {{shield|I-90}}, la strada"))
    }

    @Test
    fun `una voce di elenco vuota non resta come pallino solo`() {
        assertEquals("Musei:\n• Uffizi", cleanBody("Musei:\n* {{marker|nome=|descrizione=}}\n* Uffizi"))
    }

    @Test
    fun `fra caratteri giapponesi e cinesi non restano spazi`() {
        assertEquals("la carta (在留カード) e il soba (そば) di grano saraceno", cleanBody("la carta (在 留 カ ー ド) e il soba (そ ば) di grano saraceno"))
        // Fra parole latine e fra latino e giapponese lo spazio serve.
        assertEquals("il sushi 寿司 e il ramen", cleanBody("il sushi 寿司 e il ramen"))
    }

    @Test
    fun `immagini e link a Commons spariscono interi, anche con parentesi nella didascalia o non chiusi`() {
        assertEquals(
            "▸ Politica\nIl Paese vive una crisi.",
            cleanBody("===Politica===\n[[File:Propaganda, Myanmar 2.jpg|thumb|\"Tatmadaw [Armed Forces] e il [[popolo]] sono uno.\"]]\nIl Paese vive una crisi."),
        )
        assertEquals("Immagini di mammiferi e uccelli.", cleanBody("Immagini di mammiferi [[c:Category:Mammals|[2]]] e uccelli."))
        // Non chiuso: va via solo il nome del file, il resto della riga e' testo vero.
        assertEquals("Cercate il logo e dite \"touch\" per pagare.", cleanBody("Cercate il logo [[File:EMVCoContactlessIndicator.svg e dite \"touch\" per pagare."))
        // Su piu' righe (didascalia con una legenda a template).
        assertEquals("Serve il visto.", cleanBody("[[File:Mappa.png|thumb|\n{{legend|#fff|Visto}}\n{{legend|#000|No}}\n]]Serve il visto."))
    }

    @Test
    fun `il tag chiuso senza nome sparisce`() {
        assertEquals("Restate in macchina.", cleanBody("Restate in macchina.</>"))
    }

    @Test
    fun `entita' sconosciute o non valide restano com'erano`() {
        assertEquals("&foo; & &#99999999;", decodeHtmlEntities("&foo; & &#99999999;"))
    }
}
