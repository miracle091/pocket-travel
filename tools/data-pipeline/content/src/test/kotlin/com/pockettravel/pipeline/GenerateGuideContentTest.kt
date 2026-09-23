package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
