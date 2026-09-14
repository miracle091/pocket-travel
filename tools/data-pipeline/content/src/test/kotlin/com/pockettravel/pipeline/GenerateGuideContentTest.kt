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
}
