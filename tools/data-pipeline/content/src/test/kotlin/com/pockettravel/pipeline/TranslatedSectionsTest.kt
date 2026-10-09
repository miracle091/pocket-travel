package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
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
}
