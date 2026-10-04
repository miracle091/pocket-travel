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

    @Test
    fun `le coordinate della citta' vanno su ogni sezione e in cities db, null se mancano`() {
        val jsonl = listOf(
            org.json.JSONObject().put("city", "Torino").put("text", "== Cosa vedere ==\nLa Mole.").put("lat", 45.07034).put("lon", 7.68686),
            org.json.JSONObject().put("city", "Asti").put("text", "== Cosa vedere ==\nIl Palio.").put("lat", org.json.JSONObject.NULL),
        ).joinToString("\n")
        val rows = parseCitiesJsonl(jsonl)
        assertEquals(listOf(45.07034 to 7.68686, null to null), rows.map { it.latitude to it.longitude })

        val outputDb = File.createTempFile("pocket-travel-test", ".cities.db")
        outputDb.delete()
        try {
            writeCitiesDb(rows, outputDb)
            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT latitude, longitude FROM city_sections ORDER BY city")
                    assertEquals(true, rs.next())
                    assertEquals(null, rs.getObject("latitude"))
                    assertEquals(true, rs.next())
                    assertEquals(45.07034, rs.getDouble("latitude"), 0.0)
                    assertEquals(7.68686, rs.getDouble("longitude"), 0.0)
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `citta' di Wikivoyage EN con i titoli di sezione inglesi`() {
        val text = "{{isPartOf|Rimini (province)}}\n==Understand==\nA seaside town.\n==See==\nThe arch.\n==Go next==\nSan Marino.\n{{usablecity}}"
        val jsonl = org.json.JSONObject().put("city", "Rimini").put("text", text).toString()
        val rows = parseCitiesJsonl(jsonl, english = true)
        assertEquals(listOf("DA_SAPERE", "COSA_VEDERE"), rows.map { it.category })
        assertEquals("https://en.wikivoyage.org/wiki/Rimini", rows.first().sourceUrl)
    }

    @Test
    fun `Storia e Clima della voce di Wikipedia diventano sezioni STORIA e CLIMA dopo quelle di Wikivoyage`() {
        val jsonl = org.json.JSONObject()
            .put("city", "Rimini").put("text", "== Cosa vedere ==\nL'arco.").put("population", 150000)
            .put("wikipedia", org.json.JSONObject().put("title", "Rimini (citta')").put("text", "== Storia ==\nColonia romana.\n== Clima ==\nClima temperato."))
            .toString()

        val rows = parseCitiesJsonl(jsonl)

        assertEquals(listOf("COSA_VEDERE", "STORIA", "CLIMA"), rows.map { it.category })
        assertEquals(listOf("Rimini", "Rimini", "Rimini"), rows.map { it.city })
        assertEquals("https://it.wikipedia.org/wiki/Rimini_(citta')", rows[1].sourceUrl)
        assertEquals("Colonia romana.", rows[1].body)
        assertEquals(150000L, rows[2].population)
    }

    @Test
    fun `History e Climate di Wikipedia EN`() {
        val jsonl = org.json.JSONObject()
            .put("city", "Rimini").put("text", "")
            .put("wikipedia", org.json.JSONObject().put("title", "Rimini").put("text", "== History ==\nA Roman colony.\n== Climate ==\nMild."))
            .toString()

        val rows = parseCitiesJsonl(jsonl, english = true)

        assertEquals(listOf("STORIA", "CLIMA"), rows.map { it.category })
        assertEquals("https://en.wikipedia.org/wiki/Rimini", rows.first().sourceUrl)
    }

    @Test
    fun `la Storia si ferma alla fine dell'ultimo paragrafo entro il tetto, il Clima resta intero`() {
        val paragraph = "Frase della storia. ".repeat(60).trim() // ~1200 caratteri
        val storia = List(5) { paragraph }.joinToString("\n\n")
        val clima = "Clima. ".repeat(800).trim()
        val jsonl = org.json.JSONObject()
            .put("city", "Roma").put("text", "")
            .put("wikipedia", org.json.JSONObject().put("title", "Roma").put("text", "== Storia ==\n$storia\n=== Eta' moderna ===\nUltimo.\n== Clima ==\n$clima"))
            .toString()

        val (storiaRow, climaRow) = parseCitiesJsonl(jsonl)

        assertEquals(List(3) { paragraph }.joinToString("\n\n"), storiaRow.body)
        assertEquals(clima, climaRow.body)
    }

    @Test
    fun `la frase che introduceva una tabella tolta non resta in fondo al paragrafo`() {
        val body = "Clima temperato (Köppen: Cfa). Here are other classifications:\n\nPioggia 650 mm.\n\nClimate normals for the airport:"

        assertEquals("Clima temperato (Köppen: Cfa).\n\nPioggia 650 mm.", dropDanglingIntros(body))
        assertEquals("Nessuna tabella.", dropDanglingIntros("Nessuna tabella."))
    }

    @Test
    fun `un solo paragrafo oltre il tetto si taglia all'ultima frase intera`() {
        assertEquals("Uno. Due.", truncateSection("Uno. Due. Tre quattro", maxChars = 12))
        assertEquals("Corto.", truncateSection("Corto.", maxChars = 12))
    }
}
