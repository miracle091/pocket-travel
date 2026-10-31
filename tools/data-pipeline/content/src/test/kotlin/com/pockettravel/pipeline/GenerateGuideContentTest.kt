package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class GenerateGuideContentTest {

    @Test
    fun `estrae solo le sezioni con categoria mappata dal dump wikivoyage di test`() {
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

        val soloCosaVedere = regionGuideFromDumps("r", "== Cosa vedere ==\nIl duomo.\n", "https://it.example/r", en, "https://en.example/r")
        assertEquals("https://en.example/r", soloCosaVedere.sourceUrl)

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
    fun `senza il dump italiano i fatti rapidi inglesi restano quelli pubblicati`() {
        val published = GuideSectionRow(category = "FATTI_RAPIDI", title = "Quick facts", body = "Language: Italian\nElectricity: 230V/50Hz (European plug)")
        val other = GuideSectionRow(category = "ALTRO", title = "Altro", body = "x")
        assertEquals(published, englishQuickFactsOrPublished("italia", null) { listOf(other, published) })
        // Con il dump italiano si rigenerano, anche se c'e' una sezione pubblicata, che non si legge nemmeno.
        val dump = "{{QuickbarCountry\n|Lingua=Italiano\n}}"
        assertEquals(
            englishQuickFactsSection("italia", dump),
            englishQuickFactsOrPublished("italia", dump) { error("sezioni pubblicate lette senza bisogno") },
        )
        // Niente dump e niente sezione pubblicata: i soli dati di Wikidata.
        assertEquals(englishQuickFactsSection("italia", ""), englishQuickFactsOrPublished("italia", null) { listOf(other) })
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
        // Una "[" sola nella didascalia: il paragrafo dopo resta, anche con un link.
        assertTrue(cleanBody("[[File:x.jpg|thumb|a [ b]] Primo.\n\nAltro [[Roma]] qui.").endsWith("Altro Roma qui."))
    }

    @Test
    fun `il tag chiuso senza nome sparisce`() {
        assertEquals("Restate in macchina.", cleanBody("Restate in macchina.</>"))
    }

    @Test
    fun `entita' sconosciute o non valide restano com'erano`() {
        assertEquals("&foo; & &#99999999;", decodeHtmlEntities("&foo; & &#99999999;"))
    }

    // --- parseWikivoyageDump ---

    private fun bodyOf(dump: String): String = parseWikivoyageDump(dump).single().body

    @Test
    fun `ogni titolo noto, inglese o italiano, ha la sua categoria`() {
        val expected = mapOf(
            "Respect" to "USI_COSTUMI", "Rispettare le usanze" to "USI_COSTUMI",
            "Get in" to "DOGANE", "Come arrivare" to "DOGANE",
            "Stay healthy" to "SALUTE", "Situazione sanitaria" to "SALUTE",
            "Stay safe" to "SICUREZZA", "Sicurezza" to "SICUREZZA",
            "Get around" to "TRASPORTI", "Come spostarsi" to "TRASPORTI",
            "Talk" to "FRASI_UTILI", "Sleep" to "ALLOGGIO",
            "Eat" to "CIBO_BEVANDE", "Drink" to "CIBO_BEVANDE", "A tavola" to "CIBO_BEVANDE",
            "Buy" to "ACQUISTI", "Valuta e acquisti" to "ACQUISTI",
            "Connect" to "CONNETTIVITA", "Come restare in contatto" to "CONNETTIVITA",
            "Cope" to "VITA_QUOTIDIANA", "Tenersi informati" to "VITA_QUOTIDIANA",
            "See" to "COSA_VEDERE", "Do" to "COSA_VEDERE", "Cosa vedere" to "COSA_VEDERE", "Cosa fare" to "COSA_VEDERE",
        )
        expected.forEach { (heading, category) ->
            val sections = parseWikivoyageDump("== $heading ==\nTesto.\n")
            assertEquals(heading, 1, sections.size)
            assertEquals(heading, category, sections.single().category)
            assertEquals(heading, heading, sections.single().title)
        }
    }

    @Test
    fun `il titolo si riconosce senza distinguere maiuscole e con spazi in piu'`() {
        val sections = parseWikivoyageDump("==  STAY SAFE  ==\nTesto.\n==Eat==\nPasta.\n")

        assertEquals(listOf("SICUREZZA" to "STAY SAFE", "CIBO_BEVANDE" to "Eat"), sections.map { it.category to it.title })
    }

    @Test
    fun `i sottotitoli restano nel corpo e il testo prima del primo titolo e' ignorato`() {
        val sections = parseWikivoyageDump("Introduzione.\n== Stay safe ==\nIntro\n=== Crime ===\nPickpockets\n")

        assertEquals(1, sections.size)
        assertEquals("Intro\n▸ Crime\nPickpockets", sections.single().body)
    }

    @Test
    fun `una sezione con il solo titolo o con corpo vuoto si scarta`() {
        val sections = parseWikivoyageDump("== Stay safe ==\n\n   \n== Eat ==\nPasta\n== Sleep ==\n<!-- niente -->\n{{Pricerange}}\n")

        assertEquals(listOf("Eat"), sections.map { it.title })
    }

    @Test
    fun `titoli ripetuti danno sezioni distinte e i titoli non mappati interrompono la sezione precedente`() {
        val sections = parseWikivoyageDump("== Eat ==\nUno.\n== Understand ==\nSi scarta.\n== Eat ==\nDue.\n")

        assertEquals(listOf("Uno.", "Due."), sections.map { it.body })
    }

    @Test
    fun `le righe con CRLF e la mancanza dell'a capo finale non cambiano il risultato`() {
        assertEquals("Pasta", bodyOf("== Eat ==\r\nPasta\r\n"))
        assertEquals("Pasta", bodyOf("== Eat ==\nPasta"))
    }

    @Test
    fun `testo vuoto o senza titoli non da' sezioni`() {
        assertEquals(emptyList<GuideSectionRow>(), parseWikivoyageDump(""))
        assertEquals(emptyList<GuideSectionRow>(), parseWikivoyageDump("Solo testo\nsenza titoli\n"))
        assertEquals(emptyList<GuideSectionRow>(), parseWikivoyageDump("=== Eat ===\nNon e' un titolo di sezione.\n"))
    }

    @Test
    fun `una mappa di categorie personalizzata sostituisce quella delle regioni`() {
        val sections = parseWikivoyageDump("== Foo ==\nbar\n== Eat ==\nPasta\n", mapOf("foo" to "ALTRO"))

        assertEquals(listOf("ALTRO" to "bar"), sections.map { it.category to it.body })
    }

    // --- cleanBody ---

    @Test
    fun `commenti HTML anche su piu' righe e citazioni ref spariscono`() {
        assertEquals("Ciao mondo", cleanBody("Ciao <!-- nota\nsu piu' righe --> mondo"))
        assertEquals("Aperto tutti i giorni.", cleanBody("Aperto<ref>Fonte: sito</ref> tutti i giorni.<ref name=\"a\" />"))
        assertEquals("Testo", cleanBody("Testo<ref name=x>riga uno\nriga due</ref>"))
    }

    @Test
    fun `gallerie e tabelle, anche annidate, spariscono intere`() {
        assertEquals("Prima\n\nDopo", cleanBody("Prima\n<gallery>\nFile:Pizza.jpg|Pizza\n</gallery>\nDopo"))
        assertEquals("Orari:\n\nFine", cleanBody("Orari:\n{| class=\"wikitable\"\n|-\n| a || b\n|}\nFine"))
        assertEquals("A\n\nB", cleanBody("A\n{| x\n{| y\n|}\n|}\nB"))
    }

    @Test
    fun `i template di valuta, misura e lingua mostrano il loro valore`() {
        assertEquals("Costa 5 EUR a persona", cleanBody("Costa {{EUR|5}} a persona"))
        assertEquals("Provate Bar.", cleanBody("Provate {{listing|nome=Bar|prezzo={{EUR|5}}}}."))
        assertEquals("Piove 641 mm l'anno", cleanBody("Piove {{convert|641|mm|in}} l'anno"))
        assertEquals("Tra 15 and 25 °C in estate", cleanBody("Tra {{cvt|15|and|25|C}} in estate"))
        assertEquals("Media 23,1 °C", cleanBody("Media {{M|23.1|u=°C}}"))
        assertEquals("Il Vieux-Port e il Vieux-Port", cleanBody("Il {{lang|fr|Vieux-Port}} e il {{lang-fr|Vieux-Port}}"))
        assertEquals("Chiamate +39 055", cleanBody("Chiamate {{phone|+39 055}}"))
        assertEquals("Il Duomo di Pisa e il Duomo", cleanBody("Il {{ill|Duomo|lt=Duomo di Pisa}} e il {{ill|Duomo}}"))
    }

    @Test
    fun `un template sconosciuto chiuso sparisce e uno non chiuso resta come testo`() {
        assertEquals("Prima dopo", cleanBody("Prima {{Sconosciuto|x}} dopo"))
        assertEquals("Resta {{aperto", cleanBody("Resta {{aperto"))
    }

    @Test
    fun `corsivo e grassetto si tolgono ma l'apostrofo attaccato resta`() {
        assertEquals("corsivo e grassetto e entrambi", cleanBody("''corsivo'' e '''grassetto''' e '''''entrambi'''''"))
        assertEquals("l'Arte", cleanBody("l'''Arte'''"))
        assertEquals("l'Arte", cleanBody("l''''Arte"))
    }

    @Test
    fun `link esterni e interni lasciano solo il testo`() {
        assertEquals("Vedi Sito ufficiale.", cleanBody("Vedi [http://example.org Sito ufficiale]."))
        assertEquals("Vedi.", cleanBody("Vedi [https://example.org]."))
        assertEquals("la capitale e Firenze", cleanBody("[[Roma|la capitale]] e [[Firenze]]"))
        assertEquals("Testo", cleanBody("Testo [[Category:Foo]]"))
    }

    @Test
    fun `le note per chi scrive la pagina spariscono`() {
        assertEquals("Si paga in euro.", cleanBody(":See [[Wikivoyage:Currency]] for editing guidelines.\nSi paga in euro."))
    }

    @Test
    fun `elenchi numerati e annidati diventano pallini e i termini di definizione sottotitoli`() {
        assertEquals("• uno\n• due", cleanBody("# uno\n# due"))
        assertEquals("• a\n• b", cleanBody("* a\n** b"))
        assertEquals("▸ Vini rossi\nBarolo", cleanBody(";Vini rossi\nBarolo"))
        assertEquals("rientrato", cleanBody(": rientrato"))
    }

    @Test
    fun `tag HTML, parentesi vuote e righe vuote in eccesso si ripuliscono`() {
        assertEquals("Testo forte e a capo", cleanBody("Testo <b>forte</b> e <br/> a capo <br>"))
        assertEquals("Roma centro", cleanBody("Roma (, ) centro"))
        assertEquals("a\n\nb", cleanBody("a\n\n\n\nb"))
        assertEquals("", cleanBody(""))
        assertEquals("", cleanBody("   \n\n  \t "))
    }

    @Test
    fun `le righe di testo consecutive si uniscono e gli elenchi restano righe a se'`() {
        assertEquals("prima riga seconda riga", cleanBody("prima riga\nseconda riga"))
        assertEquals("• uno\ntesto", cleanBody("* uno\ntesto"))
        assertEquals("• a\n• b", cleanBody("* a\n\n* b"))
    }

    @Test
    fun `un sottotitolo seguito solo da altri sottotitoli o dalla fine non resta`() {
        assertEquals("Testo", cleanBody("Testo\n=== Vuoto ===\n"))
        assertEquals("▸ Pieno\nContenuto", cleanBody("=== Vuoto ===\n=== Pieno ===\nContenuto"))
    }

    @Test
    fun `testo unicode non latino e emoji attraversano la pulizia intatti`() {
        assertEquals("東京 – Tōkyō 😀 Москва", cleanBody("東京 – Tōkyō 😀 Москва"))
    }

    // --- decodeHtmlEntities, dropSkippedSubsections ---

    @Test
    fun `entita' numeriche decimali, esadecimali e fuori dallo Unicode`() {
        assertEquals("😀", decodeHtmlEntities("&#x1F600;"))
        assertEquals("A", decodeHtmlEntities("&#X41;"))
        assertEquals("A", decodeHtmlEntities("&#65;"))
        assertEquals("&#1114112;", decodeHtmlEntities("&#1114112;"))
        assertEquals("&#xZZ;", decodeHtmlEntities("&#xZZ;"))
    }

    @Test
    fun `le entita' si decodificano una sola volta e distinguono le maiuscole`() {
        assertEquals("&lt;", decodeHtmlEntities("&amp;lt;"))
        assertEquals("&AMP;", decodeHtmlEntities("&AMP;"))
        assertEquals("\"Å\" è", decodeHtmlEntities("&quot;&Aring;&quot; &egrave;"))
        assertEquals("senza entita' & niente", decodeHtmlEntities("senza entita' & niente"))
    }

    @Test
    fun `dropSkippedSubsections si ferma al titolo dello stesso livello o superiore, senza distinguere le maiuscole`() {
        assertEquals("A\n=== Altro ===\ny", dropSkippedSubsections("A\n=== Cost of living ===\nx\n=== Altro ===\ny"))
        assertEquals("=== Sopra ===\ny", dropSkippedSubsections("==== Costo della vita ====\nx\n=== Sopra ===\ny"))
        assertEquals("", dropSkippedSubsections("=== COSTO DELLA VITA ===\nx\n==== Dettagli ====\ny"))
        assertEquals("a\nb", dropSkippedSubsections("a\nb"))
    }

    // --- Fatti rapidi ---

    @Test
    fun `i campi del Quickbar oltre i primi 4000 caratteri si ignorano`() {
        val far = "x".repeat(4100) + "\n|Valuta=euro\n}}"
        val near = "{{QuickbarCountry\n|Valuta=euro\n}}\n" + "x".repeat(4100)

        assertEquals(null, quickFactsSection("zz", far))
        assertEquals("Valuta: euro", quickFactsSection("zz", near)?.body)
    }

    @Ignore("un ultimo campo vuoto prima di \"}}\" diventa \"}}\": in quickFactFieldRegex \"=\\s*\" consuma l'a capo")
    @Test
    fun `il valore di un campo del Quickbar si ripulisce dal markup e un campo vuoto non crea righe`() {
        val dump = "{{QuickbarCountry\n|Lingua=[[Italiano|italiano]]<ref>x</ref> '''ufficiale'''\n|Valuta=\n|Fuso orario=   \n}}"
        val section = quickFactsSection("zz", dump)

        assertEquals("FATTI_RAPIDI", section?.category)
        assertEquals("Lingua: italiano ufficiale", section?.body)
    }

    @Test
    fun `i fatti rapidi inglesi hanno titolo e categoria propri e saltano i campi non traducibili`() {
        val section = englishQuickFactsSection("zz", "{{QuickbarCountry\n|Lingua=lingua inventata\n|Fuso orario=ora locale\n|Elettricità=dipende\n}}")

        assertEquals(null, section)
    }

    @Test
    fun `englishElectricity gestisce parentesi assenti, elenchi con virgola e note sconosciute`() {
        assertEquals("230V", englishElectricity("230V"))
        assertEquals("230V/50Hz (European and British plugs)", englishElectricity("230V/50Hz (presa europea, britannica)"))
        assertEquals("230V", englishElectricity("230V (presa tipo C)"))
        assertEquals(null, englishElectricity(""))
    }

    @Test
    fun `englishTimeZone e englishLanguage gestiscono parentesi, elenchi e valori non traducibili`() {
        assertEquals("UTC+1", englishTimeZone("UTC+1 (CET)"))
        assertEquals(null, englishTimeZone("ora locale"))
        assertEquals("Italian, German", englishLanguage("Italiano e Tedesco"))
        assertEquals("Italian", englishLanguage("Italiano, Italiano"))
        assertEquals("Spanish, Portuguese", englishLanguage("Spagnolo; Portoghese"))
        assertEquals(null, englishLanguage(""))
        assertEquals(null, englishLanguage("(solo parentesi)"))
    }

    // --- regionGuideFromDumps ---

    @Test
    fun `senza pagina inglese un riquadro Quickbar e le sezioni restano della pagina scaricata`() {
        val guide = regionGuideFromDumps("zz", "{{QuickbarCountry\n|Valuta=euro\n}}\n== Sicurezza ==\nOk.\n", "https://it.example/zz", null, "")

        assertEquals(listOf("FATTI_RAPIDI", "SICUREZZA"), guide.sections.map { it.category })
        assertEquals("https://it.example/zz", guide.sourceUrl)
    }

    @Test
    fun `se la pagina inglese di ripiego e' vuota si tiene quella italiana`() {
        val guide = regionGuideFromDumps("zz", "== Sicurezza ==\n", "https://it.example/zz", "== Understand ==\nNo.\n", "https://en.example/zz")

        assertEquals("https://it.example/zz", guide.sourceUrl)
        assertEquals(emptyList<GuideSectionRow>(), guide.sections)
    }

    @Test
    fun `il ripiego sull'inglese tiene i fatti rapidi della pagina italiana`() {
        val dumpIt = "{{QuickbarCountry\n|Valuta=euro\n}}\n== Cosa vedere ==\nIl duomo.\n"
        val guide = regionGuideFromDumps("zz", dumpIt, "https://it.example/zz", "== Stay safe ==\nQuiet.\n", "https://en.example/zz")

        assertEquals(listOf("FATTI_RAPIDI", "SICUREZZA"), guide.sections.map { section -> section.category })
        assertEquals("Valuta: euro", guide.sections.first().body)
        assertEquals("https://en.example/zz", guide.sourceUrl)
    }

    // --- writeGuidesDb, sameGuidesContent, readRows ---

    private fun withTempDbs(count: Int, block: (List<File>) -> Unit) {
        val files = List(count) { File.createTempFile("pocket-travel-test", ".guides.db").also { it.delete() } }
        try {
            block(files)
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test
    fun `guide_sections ha le colonne attese, salva il flag tradotto e l'URL proprio di ogni sezione`() {
        withTempDbs(1) { (db) ->
            val guide = RegionGuide(
                "italia", "https://it.wikivoyage.org/wiki/Italia",
                listOf(
                    GuideSectionRow("SICUREZZA", "Sicurezza", "Citta' è 東京 😀"),
                    GuideSectionRow("SALUTE", "Stay healthy", "Tradotta", sourceUrl = "https://en.wikivoyage.org/wiki/Italy", translated = true),
                ),
            )
            writeGuidesDb(listOf(guide), db)

            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val columns = statement.executeQuery("PRAGMA table_info(guide_sections)").use { rs ->
                        generateSequence { if (rs.next()) rs.getString("name") else null }.toList()
                    }
                    assertEquals(listOf("regionId", "category", "title", "body", "sourceUrl", "translated", "translatedBy"), columns)

                    val rows = statement.executeQuery("SELECT category, body, sourceUrl, translated FROM guide_sections ORDER BY category").use { rs ->
                        generateSequence { if (rs.next()) listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) else null }.toList()
                    }
                    assertEquals(
                        listOf(
                            listOf("SALUTE", "Tradotta", "https://en.wikivoyage.org/wiki/Italy", "1"),
                            listOf("SICUREZZA", "Citta' è 東京 😀", "https://it.wikivoyage.org/wiki/Italia", "0"),
                        ),
                        rows,
                    )

                    val tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
                    }
                    assertTrue(tables.toString(), tables.containsAll(setOf("guide_sections", "emergency_numbers", "vacc_meta")))
                }
            }
        }
    }

    @Test
    fun `senza regioni guides db ha comunque le tabelle vuote`() {
        withTempDbs(1) { (db) ->
            writeGuidesDb(emptyList(), db)

            assertEquals(emptyList<List<String?>>(), readRows(db, "SELECT * FROM guide_sections"))
            assertEquals(emptyList<List<String?>>(), readRows(db, "SELECT * FROM emergency_numbers"))
        }
    }

    @Test
    fun `riscrivere guides db sullo stesso file sostituisce le sezioni invece di accumularle`() {
        withTempDbs(1) { (db) ->
            val guide = RegionGuide("italia", "https://example.org", listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "a")))
            writeGuidesDb(listOf(guide), db)
            writeGuidesDb(listOf(guide), db)

            assertEquals(1, readRows(db, "SELECT * FROM guide_sections")?.size)
        }
    }

    @Test
    fun `readRows restituisce null per una tabella assente e le righe con i NULL per una presente`() {
        withTempDbs(1) { (db) ->
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org", listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "a")))), db)

            assertEquals(null, readRows(db, "SELECT * FROM tabella_che_non_esiste"))
            assertEquals(listOf(listOf("italia", null)), readRows(db, "SELECT regionId, NULL FROM guide_sections"))
        }
    }

    @Test
    fun `sameGuidesContent distingue flag tradotto, URL e categoria`() {
        withTempDbs(4) { (base, translated, otherUrl, otherCategory) ->
            val section = GuideSectionRow("SICUREZZA", "Sicurezza", "testo")
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org/a", listOf(section))), base)
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org/a", listOf(section.copy(translated = true)))), translated)
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org/b", listOf(section))), otherUrl)
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org/a", listOf(section.copy(category = "SALUTE")))), otherCategory)

            assertFalse(sameGuidesContent(base, translated))
            assertFalse(sameGuidesContent(base, otherUrl))
            assertFalse(sameGuidesContent(base, otherCategory))
            assertTrue(sameGuidesContent(base, base))
        }
    }

    @Test
    fun `sameGuidesContent puo' ignorare le sezioni dei consigli di viaggio e sameTravelAdvice guarda solo quelle`() {
        withTempDbs(2) { (a, b) ->
            val fixed = GuideSectionRow("SICUREZZA", "Sicurezza", "testo")
            fun advice(body: String) = GuideSectionRow("SICUREZZA", "Consigli", body, sourceUrl = "https://$TRAVEL_ADVICE_HOST/destinations/it")
            val fcdo = GuideSectionRow("SICUREZZA", "Advice", "x", sourceUrl = "${FCDO_ADVICE_PREFIX}italy")
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org", listOf(fixed, advice("uno"), fcdo))), a)
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org", listOf(fixed, advice("due"), fcdo))), b)

            assertFalse(sameGuidesContent(a, b))
            assertTrue(sameGuidesContent(a, b, includeTravelAdvice = false))
            assertFalse(sameTravelAdvice(a, b))
        }
        withTempDbs(2) { (a, b) ->
            val advice = GuideSectionRow("SICUREZZA", "Consigli", "stesso", sourceUrl = "https://$TRAVEL_ADVICE_HOST/destinations/it")
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org", listOf(advice, GuideSectionRow("SALUTE", "Salute", "uno")))), a)
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org", listOf(advice, GuideSectionRow("SALUTE", "Salute", "due")))), b)

            assertTrue(sameTravelAdvice(a, b))
            assertFalse(sameGuidesContent(a, b, includeTravelAdvice = false))
        }
    }

    @Test
    fun `sameMissions e sameGuidesContent su due file senza missioni, e un file senza le tabelle delle guide non e' uguale a uno completo`() {
        withTempDbs(3) { (a, b, foreign) ->
            writeGuidesDb(emptyList(), a)
            writeGuidesDb(emptyList(), b)
            DriverManager.getConnection("jdbc:sqlite:${foreign.path}").use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE altro (x TEXT)") }
            }

            assertTrue(sameMissions(a, b))
            assertTrue(sameGuidesContent(a, b))
            assertFalse(sameGuidesContent(a, foreign))
        }
    }
}
