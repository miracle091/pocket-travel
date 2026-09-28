package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
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
                    assertEquals(true, rs.next())
                    assertEquals("SICUREZZA", rs.getString("category"))
                    assertEquals("Stay safe", rs.getString("title"))
                    assertEquals("https://example.org/test-region", rs.getString("sourceUrl"))

                    assertEquals(true, rs.next())
                    assertEquals("TRASPORTI", rs.getString("category"))
                    assertEquals("Get around", rs.getString("title"))

                    // "See" non ha una categoria mappata: deve essere scartata.
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
                    assertEquals("SICUREZZA", rs.getString("category"))
                    assertEquals("Sicurezza", rs.getString("title"))
                    assertEquals("https://it.wikivoyage.org/wiki/Test", rs.getString("sourceUrl"))

                    assertEquals(true, rs.next())
                    assertEquals("TRASPORTI", rs.getString("category"))
                    assertEquals("Come spostarsi", rs.getString("title"))

                    // "Cosa vedere" non ha una categoria mappata: deve essere scartata.
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
        assertEquals(
            "Lingua: Italiano, Tedesco (Trentino-Alto Adige)\n" +
                "Elettricità: 230V/50Hz (presa italiana, europea e tedesca)\n" +
                "Fuso orario: UTC+1\n" +
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
        val section = englishQuickFactsSection(dump)
        assertEquals("Quick facts", section?.title)
        assertEquals("Electricity: 230V/50Hz (European plug)\nTime zone: UTC+1", section?.body)
    }
}
