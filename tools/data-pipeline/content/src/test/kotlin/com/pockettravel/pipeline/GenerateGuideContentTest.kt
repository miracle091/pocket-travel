package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerateGuideContentTest {

    @Test
    fun `estrae solo le sezioni con categoria mappata dall'estratto wikivoyage di test`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()

        try {
            val dumpText = File("testdata/wikivoyage-excerpt.txt").readText()
            val sections = parseWikivoyageDump(dumpText)
            writeGuideDb(sections, "test-region", "https://example.org/test-region", outputDb)

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
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()

        try {
            val dumpText = File("testdata/wikivoyage-excerpt-it.txt").readText()
            val sections = parseWikivoyageDump(dumpText)
            writeGuideDb(sections, "test-region", "https://it.wikivoyage.org/wiki/Test", outputDb)

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
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()

        try {
            val dumpText = File("testdata/wikivoyage-excerpt-readability.txt").readText()
            val sections = parseWikivoyageDump(dumpText)
            writeGuideDb(sections, "test-region", "https://example.org/test-region", outputDb)

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
}
