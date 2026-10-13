package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class TranslatedSectionsTest {

    private val jsonl = """
        {"owner": "finlandia", "category": "SALUTE", "sections": [{"title": "Salute", "body": "Acqua potabile.\n▸ Zecche", "sourceUrl": "https://en.wikivoyage.org/wiki/Finland"}]}

        {"owner": "Venezia", "category": "STORIA", "sections": [{"title": "Storia", "body": "Fondata nel 421.", "sourceUrl": "https://en.wikipedia.org/wiki/Venice"}]}
    """.trimIndent()

    @Test
    fun `legge le sezioni tradotte con translated e url d'origine`() {
        val translated = parseTranslatedSections(jsonl)

        assertEquals(listOf("finlandia" to "SALUTE", "Venezia" to "STORIA"), translated.map { it.owner to it.category })
        assertEquals(
            GuideSectionRow("SALUTE", "Salute", "Acqua potabile.\n▸ Zecche", "https://en.wikivoyage.org/wiki/Finland", translated = true),
            translated.first().sections.single(),
        )
    }

    @Test
    fun `la categoria tradotta sostituisce quella italiana al suo posto`() {
        val guide = RegionGuide(
            "finlandia", "https://it.wikivoyage.org/wiki/Finlandia",
            listOf(GuideSectionRow("FATTI_RAPIDI", "Fatti rapidi", "x"), GuideSectionRow("SALUTE", "Salute", "poco"), GuideSectionRow("SALUTE", "Altro", "poco"), GuideSectionRow("CIBO_BEVANDE", "Cibo", "y")),
        )

        val result = listOf(guide).withTranslations(parseTranslatedSections(jsonl)).single()

        assertEquals(listOf("FATTI_RAPIDI", "SALUTE", "CIBO_BEVANDE"), result.sections.map { it.category })
        assertEquals(listOf(false, true, false), result.sections.map { it.translated })
        assertEquals("Acqua potabile.\n▸ Zecche", result.sections[1].body)
    }

    @Test
    fun `i consigli del governo canadese tradotti si aggiungono in fondo senza togliere la categoria italiana`() {
        val advice = """{"owner": "finlandia", "category": "SALUTE", "sections": [{"title": "Salute (Governo del Canada)", "body": "Zecche.", "sourceUrl": "https://travel.gc.ca/destinations/fi"}]}"""
        val guide = RegionGuide(
            "finlandia", "https://it.wikivoyage.org/wiki/Finlandia",
            listOf(
                GuideSectionRow("SALUTE", "Salute", "Acqua potabile."),
                GuideSectionRow("SALUTE", "Salute (Governo del Canada)", "vecchia", "https://travel.gc.ca/destinations/fi", translated = true),
                GuideSectionRow("CIBO_BEVANDE", "Cibo", "y"),
            ),
        )

        val result = listOf(guide).withTranslations(parseTranslatedSections(advice)).single()

        assertEquals(listOf("Salute", "Cibo", "Salute (Governo del Canada)"), result.sections.map { it.title })
        assertEquals(listOf("Acqua potabile.", "y", "Zecche."), result.sections.map { it.body })
    }

    private val gc = "https://travel.gc.ca/destinations/fi"
    private val publishedAdvice = listOf(
        GuideSectionRow("SICUREZZA", "Sicurezza (Governo del Canada)", "Furti.", gc, translated = true),
        GuideSectionRow("SALUTE", "Salute (Governo del Canada)", "Zecche vecchie.", gc, translated = true),
    )

    @Test
    fun `senza traduzioni restano i consigli tradotti gia' pubblicati`() {
        val guide = RegionGuide("finlandia", "https://it.wikivoyage.org/wiki/Finlandia", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua.")))

        val result = listOf(guide).withTranslations(emptyList()) { publishedAdvice }.single()

        assertEquals(listOf("Acqua.", "Furti.", "Zecche vecchie."), result.sections.map { it.body })
    }

    @Test
    fun `una categoria di consigli tradotta sostituisce solo la sua, le altre restano quelle pubblicate`() {
        val advice = """{"owner": "finlandia", "category": "SALUTE", "sections": [{"title": "Salute (Governo del Canada)", "body": "Zecche.", "sourceUrl": "$gc"}]}"""
        // Regione senza dump in questa run: la guida e' quella pubblicata, con i consigli tradotti dentro.
        val guide = RegionGuide("finlandia", "https://it.wikivoyage.org/wiki/Finlandia", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua.")) + publishedAdvice)

        val result = listOf(guide).withTranslations(parseTranslatedSections(advice)) { publishedAdvice }.single()

        assertEquals(listOf("Acqua.", "Furti.", "Zecche."), result.sections.map { it.body })
    }

    @Test
    fun `una categoria assente in italiano va in fondo e le altre regioni restano uguali`() {
        val finlandia = RegionGuide("finlandia", "u", listOf(GuideSectionRow("CIBO_BEVANDE", "Cibo", "y")))
        val svezia = RegionGuide("svezia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "z")))

        val result = listOf(finlandia, svezia).withTranslations(parseTranslatedSections(jsonl))

        assertEquals(listOf("CIBO_BEVANDE", "SALUTE"), result[0].sections.map { it.category })
        assertEquals(svezia, result[1])
    }

    @Test
    fun `guides db scrive translated e l'url della sezione tradotta`() {
        val db = File.createTempFile("pocket-travel-test", ".guides.db")
        db.delete()
        try {
            val guide = RegionGuide("finlandia", "https://it.wikivoyage.org/wiki/Finlandia", listOf(GuideSectionRow("CIBO_BEVANDE", "Cibo", "y")))
            writeGuidesDb(listOf(guide).withTranslations(parseTranslatedSections(jsonl)), db)

            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT category, sourceUrl, translated FROM guide_sections ORDER BY rowid")
                    val rows = buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getInt(3))) }
                    assertEquals(
                        listOf(
                            Triple("CIBO_BEVANDE", "https://it.wikivoyage.org/wiki/Finlandia", 0),
                            Triple("SALUTE", "https://en.wikivoyage.org/wiki/Finland", 1),
                        ),
                        rows,
                    )
                }
            }
        } finally {
            db.delete()
        }
    }

    @Test
    fun `una sezione tradotta cambia il contenuto confrontato con il pubblicato`() {
        val a = File.createTempFile("pocket-travel-test", ".guides.db")
        val b = File.createTempFile("pocket-travel-test", ".guides.db")
        listOf(a, b).forEach { it.delete() }
        try {
            val plain = RegionGuide("finlandia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua potabile.\n▸ Zecche")))
            writeGuidesDb(listOf(plain), a)
            writeGuidesDb(listOf(plain.copy(sections = listOf(plain.sections.single().copy(translated = true)))), b)

            assertEquals(false, sameGuidesContent(a, b))
        } finally {
            listOf(a, b).forEach { it.delete() }
        }
    }

    @Test
    fun `le citta' tengono popolazione e capitale e segnano le sezioni tradotte`() {
        val rows = listOf(
            CitySectionRow("Venezia", "COSA_VEDERE", "Cosa vedere", "Piazza San Marco.", "https://it.wikivoyage.org/wiki/Venezia", 250000, false),
            CitySectionRow("Venezia", "STORIA", "Storia", "breve", "https://it.wikipedia.org/wiki/Venezia", 250000, false),
            CitySectionRow("Roma", "STORIA", "Storia", "x", "https://it.wikipedia.org/wiki/Roma", 2800000, true),
        )

        val result = rows.withCityTranslations(parseTranslatedSections(jsonl))

        assertEquals(listOf("Venezia" to "COSA_VEDERE", "Venezia" to "STORIA", "Roma" to "STORIA"), result.map { it.city to it.category })
        val storia = result[1]
        assertEquals(CitySectionRow("Venezia", "STORIA", "Storia", "Fondata nel 421.", "https://en.wikipedia.org/wiki/Venice", 250000, false, translated = true), storia)
        assertEquals(rows[2], result[2])
    }

    @Test
    fun `cities db ha la colonna translated`() {
        val db = File.createTempFile("pocket-travel-test", ".cities.db")
        db.delete()
        try {
            writeCitiesDb(listOf(CitySectionRow("Roma", "STORIA", "Storia", "x", "u", translated = true)), db)
            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT translated FROM city_sections")
                    assertEquals(true, rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        } finally {
            db.delete()
        }
    }

    // --- parseTranslatedSections: casi limite ---

    @Test
    fun `parseTranslatedSections - vuoto, righe vuote e sezioni vuote`() {
        assertEquals(emptyList<TranslatedCategory>(), parseTranslatedSections(""))
        assertEquals(emptyList<TranslatedCategory>(), parseTranslatedSections("\n  \n\n"))
        val empty = parseTranslatedSections("""{"owner": "finlandia", "category": "SALUTE", "sections": []}""")
        assertEquals(listOf(TranslatedCategory("finlandia", "SALUTE", emptyList())), empty)
    }

    @Test
    fun `parseTranslatedSections - ogni sezione prende la categoria della riga e il flag translated, con testo unicode`() {
        val line = """{"owner": "giappone", "category": "CIBO_BEVANDE", "sections": [""" +
            """{"title": "料理", "body": "Ramen 🍜 e 'sake'\n▸ Bere", "sourceUrl": "https://en.wikivoyage.org/wiki/Japan"},""" +
            """{"title": "Bere", "body": "Tè", "sourceUrl": "https://en.wikivoyage.org/wiki/Japan"}]}"""

        val result = parseTranslatedSections(line).single()

        assertEquals(listOf("CIBO_BEVANDE", "CIBO_BEVANDE"), result.sections.map { it.category })
        assertEquals(listOf(true, true), result.sections.map { it.translated })
        assertEquals("料理", result.sections[0].title)
        assertEquals("Ramen 🍜 e 'sake'\n▸ Bere", result.sections[0].body)
    }

    @Test
    fun `parseTranslatedSections - una riga malformata o con campi mancanti fa fallire la lettura`() {
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseTranslatedSections("non json") }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseTranslatedSections("""{"owner": "a", "sections": []}""") }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseTranslatedSections("""{"owner": "a", "category": "X"}""") }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseTranslatedSections("""{"category": "X", "sections": []}""") }
        // Una sezione senza sourceUrl non e' ammessa: tutte le sezioni tradotte citano la pagina d'origine.
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) {
            parseTranslatedSections("""{"owner": "a", "category": "X", "sections": [{"title": "t", "body": "b"}]}""")
        }
    }

    // --- replaceCategories ---

    @Test
    fun `replaceCategories mette il blocco al posto della prima riga della categoria e toglie le altre`() {
        val rows = listOf("A1", "B1", "A2", "C1", "A3")

        val result = replaceCategories(rows, { it.take(1) }, mapOf("A" to listOf("X", "Y")))

        assertEquals(listOf("X", "Y", "B1", "C1"), result)
    }

    @Test
    fun `replaceCategories - le categorie assenti vanno in fondo nell'ordine dei rimpiazzi`() {
        val result = replaceCategories(listOf("A1"), { it.take(1) }, linkedMapOf("Z" to listOf("Z1"), "A" to listOf("X"), "M" to listOf("M1", "M2")))

        assertEquals(listOf("X", "Z1", "M1", "M2"), result)
    }

    @Test
    fun `replaceCategories - senza rimpiazzi o senza righe`() {
        assertEquals(listOf("A1", "B1"), replaceCategories(listOf("A1", "B1"), { it.take(1) }, emptyMap()))
        assertEquals(emptyList<String>(), replaceCategories<String>(emptyList(), { it.take(1) }, emptyMap()))
        assertEquals(listOf("X"), replaceCategories<String>(emptyList(), { it.take(1) }, mapOf("A" to listOf("X"))))
        // Un rimpiazzo vuoto toglie tutta la categoria.
        assertEquals(listOf("B1"), replaceCategories(listOf("A1", "B1", "A2"), { it.take(1) }, mapOf("A" to emptyList<String>())))
    }

    // --- withTranslations: casi limite ---

    @Test
    fun `senza traduzioni ne' consigli pubblicati la guida resta lo stesso oggetto`() {
        val guide = RegionGuide("finlandia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua.")))

        val result = listOf(guide).withTranslations(emptyList())

        assertEquals(listOf(guide), result)
        assertTrue(result.single() === guide)
    }

    @Test
    fun `le traduzioni di un proprietario che non e' tra le guide si ignorano`() {
        val guide = RegionGuide("svezia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "z")))

        assertEquals(listOf(guide), listOf(guide).withTranslations(parseTranslatedSections(jsonl)))
    }

    @Test
    fun `una traduzione con sole sezioni vuote non cambia la categoria italiana`() {
        val guide = RegionGuide("finlandia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua.")))
        val empty = """{"owner": "finlandia", "category": "SALUTE", "sections": []}"""

        assertEquals(listOf(guide), listOf(guide).withTranslations(parseTranslatedSections(empty)))
    }

    @Test
    fun `i consigli tradotti gia' nella guida si sostituiscono con quelli pubblicati e quelli non tradotti restano`() {
        val untranslatedAdvice = GuideSectionRow("SICUREZZA", "Safety (Government of Canada)", "inglese", gc)
        val oldTranslated = GuideSectionRow("SALUTE", "Salute (Governo del Canada)", "vecchi", gc, translated = true)
        val guide = RegionGuide("finlandia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua."), untranslatedAdvice, oldTranslated))

        val result = listOf(guide).withTranslations(emptyList()) { listOf(publishedAdvice[0]) }.single()

        assertEquals(listOf("Acqua.", "inglese", "Furti."), result.sections.map { it.body })
    }

    @Test
    fun `i consigli pubblicati non tradotti o non di viaggio non si ricopiano`() {
        val guide = RegionGuide("finlandia", "u", listOf(GuideSectionRow("SALUTE", "Salute", "Acqua.")))
        val notTranslated = GuideSectionRow("SICUREZZA", "Safety", "inglese", gc)
        val notAdvice = GuideSectionRow("SICUREZZA", "Sicurezza", "wikivoyage", "https://it.wikivoyage.org/wiki/Finlandia", translated = true)

        val result = listOf(guide).withTranslations(emptyList()) { listOf(notTranslated, notAdvice) }.single()

        assertEquals(listOf("Acqua."), result.sections.map { it.body })
    }

    @Test
    fun `piu' categorie tradotte della stessa regione mantengono l'ordine delle righe italiane`() {
        val two = """
            {"owner": "finlandia", "category": "SALUTE", "sections": [{"title": "Salute", "body": "S", "sourceUrl": "https://en.wikivoyage.org/wiki/Finland"}]}
            {"owner": "finlandia", "category": "ALLOGGIO", "sections": [{"title": "Alloggio", "body": "A", "sourceUrl": "https://en.wikivoyage.org/wiki/Finland"}]}
        """.trimIndent()
        val guide = RegionGuide(
            "finlandia", "u",
            listOf(GuideSectionRow("ALLOGGIO", "Alloggio", "a"), GuideSectionRow("CIBO_BEVANDE", "Cibo", "c"), GuideSectionRow("SALUTE", "Salute", "s")),
        )

        val result = listOf(guide).withTranslations(parseTranslatedSections(two)).single()

        assertEquals(listOf("A", "c", "S"), result.sections.map { it.body })
        assertEquals(listOf(true, false, true), result.sections.map { it.translated })
    }

    // --- withCityTranslations: casi limite ---

    private val venice = CitySectionRow("Venezia", "COSA_VEDERE", "Cosa vedere", "Piazza San Marco.", "https://it.wikivoyage.org/wiki/Venezia", 250000, false, 45.4, 12.3)

    @Test
    fun `withCityTranslations - senza traduzioni le righe restano le stesse`() {
        val rows = listOf(venice, venice.copy(category = "STORIA", title = "Storia"))

        assertEquals(rows, rows.withCityTranslations(emptyList()))
        assertEquals(emptyList<CitySectionRow>(), emptyList<CitySectionRow>().withCityTranslations(parseTranslatedSections(jsonl)))
    }

    @Test
    fun `withCityTranslations - la categoria assente va in fondo con i dati della citta' e le citta' sconosciute non si aggiungono`() {
        val rows = listOf(venice)

        val result = rows.withCityTranslations(parseTranslatedSections(jsonl))

        // jsonl traduce STORIA di Venezia (assente nelle righe) e SALUTE di "finlandia" (non e' una citta' delle righe).
        assertEquals(listOf("COSA_VEDERE", "STORIA"), result.map { it.category })
        val storia = result.last()
        assertEquals(listOf<Any?>(250000L, false, 45.4, 12.3, true), listOf(storia.population, storia.capital, storia.latitude, storia.longitude, storia.translated))
    }

    @Test
    fun `withCityTranslations - le righe di una stessa citta' si raggruppano e l'url mancante diventa vuoto`() {
        val roma = CitySectionRow("Roma", "STORIA", "Storia", "x", "u", 2800000, true)
        val rows = listOf(venice, roma, venice.copy(category = "ACQUISTI", title = "Acquisti"))
        val noUrl = TranslatedCategory("Roma", "STORIA", listOf(GuideSectionRow("STORIA", "Storia", "tradotta", null, translated = true)))

        val result = rows.withCityTranslations(listOf(noUrl))

        assertEquals(listOf("Venezia" to "COSA_VEDERE", "Venezia" to "ACQUISTI", "Roma" to "STORIA"), result.map { it.city to it.category })
        assertEquals("", result.last().sourceUrl)
        assertEquals(true, result.last().capital)
        assertEquals(2800000L, result.last().population)
    }
}
