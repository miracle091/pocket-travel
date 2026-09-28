package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class GenerateCitiesTest {

    @Test
    fun `mappa i titoli di sezione delle citta' sulle categorie del contratto`() {
        val jsonl = """
            {"city": "Roma", "text": "== Da sapere ==\nCapitale d'Italia.\n\n== Cosa vedere ==\nIl Colosseo.\n\n== Sicurezza ==\nZona tranquilla.\n\n== Vita notturna ==\nMolti locali."}
        """.trimIndent()

        val sections = parseCitiesJsonl(jsonl)

        assertEquals(
            listOf("DA_SAPERE" to "Capitale d'Italia.", "COSA_VEDERE" to "Il Colosseo.", "SICUREZZA" to "Zona tranquilla."),
            sections.map { it.category to it.body },
        )
        // "Vita notturna" non ha una categoria mappata: deve essere scartata.
        assertEquals(3, sections.size)
        assertEquals(listOf("Roma", "Roma", "Roma"), sections.map { it.city })
        assertEquals("https://it.wikivoyage.org/wiki/Roma", sections.first().sourceUrl)
    }

    @Test
    fun `sourceUrl sostituisce gli spazi del titolo con underscore`() {
        val jsonl = """{"city": "New York City", "text": "== Acquisti ==\nTanti negozi."}"""

        val section = parseCitiesJsonl(jsonl).single()

        assertEquals("https://it.wikivoyage.org/wiki/New_York_City", section.sourceUrl)
        assertEquals("ACQUISTI", section.category)
    }

    @Test
    fun `righe vuote nel jsonl vengono ignorate`() {
        val jsonl = "\n{\"city\": \"Roma\", \"text\": \"== Sicurezza ==\\nZona tranquilla.\"}\n\n"

        assertEquals(1, parseCitiesJsonl(jsonl).size)
    }

    @Test
    fun `cities db contiene le sezioni di tutte le citta'`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".cities.db")
        outputDb.delete()

        try {
            writeCitiesDb(
                listOf(
                    CitySectionRow("Roma", "COSA_VEDERE", "Cosa vedere", "Il Colosseo.", "https://it.wikivoyage.org/wiki/Roma"),
                    CitySectionRow("Milano", "ACQUISTI", "Acquisti", "Il Duomo.", "https://it.wikivoyage.org/wiki/Milano"),
                ),
                outputDb,
            )

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT city, category FROM city_sections ORDER BY city")
                    assertEquals(true, rs.next())
                    assertEquals("Milano", rs.getString("city"))
                    assertEquals("ACQUISTI", rs.getString("category"))
                    assertEquals(true, rs.next())
                    assertEquals("Roma", rs.getString("city"))
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }
}
