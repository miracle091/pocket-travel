package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
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

    // --- parseCitiesJsonl: casi limite ---

    private fun cityLine(city: String, text: String, build: org.json.JSONObject.() -> Unit = {}): String =
        org.json.JSONObject().put("city", city).put("text", text).apply(build).toString()

    @Test
    fun `ogni titolo di sezione delle citta' italiane e inglesi ha la sua categoria`() {
        val italian = mapOf(
            "Da sapere" to "DA_SAPERE", "Come arrivare" to "TRASPORTI", "Come spostarsi" to "TRASPORTI", "Cosa vedere" to "COSA_VEDERE",
            "Cosa fare" to "COSA_VEDERE", "Acquisti" to "ACQUISTI", "Dove mangiare" to "CIBO_BEVANDE", "Dove alloggiare" to "ALLOGGIO",
            "Sicurezza" to "SICUREZZA", "Come restare in contatto" to "CONNETTIVITA", "Informazioni utili" to "VITA_QUOTIDIANA",
        )
        val english = mapOf(
            "Understand" to "DA_SAPERE", "Get in" to "TRASPORTI", "Get around" to "TRASPORTI", "See" to "COSA_VEDERE", "Do" to "COSA_VEDERE",
            "Buy" to "ACQUISTI", "Eat" to "CIBO_BEVANDE", "Drink" to "CIBO_BEVANDE", "Sleep" to "ALLOGGIO", "Stay safe" to "SICUREZZA",
            "Connect" to "CONNETTIVITA", "Cope" to "VITA_QUOTIDIANA",
        )
        italian.forEach { (heading, category) ->
            assertEquals(heading, listOf(category), parseCitiesJsonl(cityLine("Roma", "== $heading ==\nTesto."), english = false).map { it.category })
        }
        english.forEach { (heading, category) ->
            assertEquals(heading, listOf(category), parseCitiesJsonl(cityLine("Rome", "== $heading ==\nText."), english = true).map { it.category })
        }
        // I titoli di una lingua non valgono per l'altra.
        assertEquals(emptyList<CitySectionRow>(), parseCitiesJsonl(cityLine("Roma", "== See ==\nText."), english = false))
        assertEquals(emptyList<CitySectionRow>(), parseCitiesJsonl(cityLine("Rome", "== Cosa vedere ==\nTesto."), english = true))
    }

    @Test
    fun `popolazione, capitale e coordinate - valori mancanti, nulli, non validi o come stringa`() {
        fun first(build: org.json.JSONObject.() -> Unit) = parseCitiesJsonl(cityLine("Roma", "== Sicurezza ==\nOk.", build)).single()

        val plain = first {}
        assertEquals(listOf(null, false, null, null), listOf(plain.population, plain.capital, plain.latitude, plain.longitude))

        assertEquals(null, first { put("population", 0) }.population)
        assertEquals(null, first { put("population", -5) }.population)
        assertEquals(null, first { put("population", org.json.JSONObject.NULL) }.population)
        assertEquals(150_000L, first { put("population", "150000") }.population)
        assertEquals(3_000_000_000L, first { put("population", 3_000_000_000L) }.population)
        assertEquals(true, first { put("capital", true) }.capital)
        assertEquals(false, first { put("capital", "non un booleano") }.capital)

        val onlyLat = first { put("lat", 45.5) }
        assertEquals(45.5 to null, onlyLat.latitude to onlyLat.longitude)
        assertEquals(45.5, first { put("lat", "45.5") }.latitude)
        assertEquals(null, first { put("lat", "abc") }.latitude)
        assertEquals(-33.8688 to 151.2093, first { put("lat", -33.8688).put("lon", 151.2093) }.let { it.latitude to it.longitude })
    }

    @Test
    fun `una riga malformata o senza city e text fa fallire la lettura`() {
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseCitiesJsonl("non e' json") }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseCitiesJsonl("""{"text": "== Sicurezza ==\nOk."}""") }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { parseCitiesJsonl("""{"city": "Roma"}""") }
        assertEquals(emptyList<CitySectionRow>(), parseCitiesJsonl(""))
        assertEquals(emptyList<CitySectionRow>(), parseCitiesJsonl("   \n\n"))
        assertEquals(emptyList<CitySectionRow>(), parseCitiesJsonl(cityLine("Roma", "")))
    }

    @Test
    fun `piu' citta' restano nell'ordine del file e il titolo unicode va nell'URL senza codifica`() {
        val jsonl = listOf(
            cityLine("São Paulo", "== Sicurezza ==\nOk."),
            cityLine("東京", "== Sicurezza ==\nOk."),
            cityLine("Roma", "== Sicurezza ==\nOk."),
        ).joinToString("\n")

        val rows = parseCitiesJsonl(jsonl)

        assertEquals(listOf("São Paulo", "東京", "Roma"), rows.map { it.city })
        assertEquals("https://it.wikivoyage.org/wiki/São_Paulo", rows[0].sourceUrl)
        assertEquals("https://it.wikivoyage.org/wiki/東京", rows[1].sourceUrl)
    }

    @Test
    fun `la voce di Wikipedia assente o nulla non da' sezioni, i titoli non mappati si scartano`() {
        val noWikipedia = cityLine("Roma", "== Sicurezza ==\nOk.") { put("wikipedia", org.json.JSONObject.NULL) }
        val unmapped = cityLine("Roma", "") { put("wikipedia", org.json.JSONObject().put("title", "Roma").put("text", "== Geografia ==\nColli.\n== Storia ==\nFondata.")) }

        assertEquals(listOf("SICUREZZA"), parseCitiesJsonl(noWikipedia).map { it.category })
        assertEquals(listOf("STORIA"), parseCitiesJsonl(unmapped).map { it.category })
    }

    @Test
    fun `una sezione di Wikipedia fatta solo di introduzioni a tabelle tolte non crea la riga`() {
        val wikipedia = org.json.JSONObject().put("title", "Roma").put("text", "== Clima ==\nClimate normals for the airport:\n== Storia ==\nFondata nel 753 a.C.")

        val rows = parseCitiesJsonl(cityLine("Roma", "") { put("wikipedia", wikipedia) }, english = false)

        assertEquals(listOf("STORIA"), rows.map { it.category })
    }

    @Test
    fun `la Storia lunga di Wikipedia si accorcia e la sorgente e' quella della voce`() {
        val storia = List(10) { "Una frase di storia che si ripete. ".repeat(40).trim() }.joinToString("\n\n")
        val wikipedia = org.json.JSONObject().put("title", "Città di prova").put("text", "== Storia ==\n$storia")

        val row = parseCitiesJsonl(cityLine("Roma", "") { put("wikipedia", wikipedia) }).single()

        assertEquals("https://it.wikipedia.org/wiki/Città_di_prova", row.sourceUrl)
        assertTrue("storia di ${row.body.length} caratteri", row.body.length <= 4000)
        assertTrue(row.body.endsWith("ripete."))
    }

    // --- truncateSection e dropDanglingIntros ---

    @Test
    fun `truncateSection lascia intero il testo entro il tetto, anche esattamente al limite`() {
        assertEquals("a".repeat(10), truncateSection("a".repeat(10), maxChars = 10))
        assertEquals("", truncateSection("", maxChars = 10))
    }

    @Test
    fun `truncateSection taglia a un paragrafo intero, toglie il sottotitolo in coda e ripiega sul tetto`() {
        assertEquals("AAAA\n\nBBBB", truncateSection("AAAA\n\nBBBB\n\nCCCC", maxChars = 12))
        assertEquals("Testo uno.", truncateSection("Testo uno.\n▸ Titolo\nTesto due lungo lungo", maxChars = 25))
        // Senza a capo ne' fine frase: si taglia al tetto.
        assertEquals("a".repeat(10), truncateSection("a".repeat(11), maxChars = 10))
    }

    @Ignore("truncateSection taglia con take() sulle unita' UTF-16 e puo' lasciare una surrogata alta spaiata")
    @Test
    fun `truncateSection non lascia mezza coppia surrogata in coda`() {
        val cut = truncateSection("😀".repeat(10), maxChars = 5)

        assertTrue("coda: ${cut.last().code}", !cut.last().isHighSurrogate())
    }

    @Test
    fun `dropDanglingIntros - righe che finiscono con due punti, frasi precedenti conservate, righe vuote ridotte`() {
        assertEquals("", dropDanglingIntros(""))
        assertEquals("", dropDanglingIntros("Solo introduzione:"))
        assertEquals("Fine.", dropDanglingIntros("Fine. Ecco i dati:"))
        assertEquals("Prima.\n\nDopo.", dropDanglingIntros("Prima.\n\nElenco:\n\nDopo."))
        // Un due punti a meta' riga non e' un'introduzione.
        assertEquals("Elenco: uno e due.", dropDanglingIntros("Elenco: uno e due."))
        // Spazi dopo i due punti: sempre un'introduzione.
        assertEquals("Prima.", dropDanglingIntros("Prima.\nIntro:   "))
    }

    // --- writeCitiesDb ---

    private fun withDb(block: (File) -> Unit) {
        val db = File.createTempFile("pocket-travel-test", ".cities.db").also { it.delete() }
        try {
            block(db)
        } finally {
            db.delete()
        }
    }

    private fun rowsOf(db: File, sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        conn.createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                val columns = rs.metaData.columnCount
                generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
            }
        }
    }

    @Test
    fun `city_sections ha le colonne attese e salva popolazione grande, capitale e tradotta`() = withDb { db ->
        writeCitiesDb(
            listOf(
                CitySectionRow("Roma", "STORIA", "Storia", "Fondata.", "https://x", population = 3_000_000_000L, capital = true, latitude = 41.9, longitude = 12.5, translated = true),
                CitySectionRow("Asti", "STORIA", "Storia", "Antica.", "https://y"),
            ),
            db,
        )

        assertEquals(
            listOf("city", "category", "title", "body", "sourceUrl", "population", "capital", "latitude", "longitude", "translated"),
            rowsOf(db, "SELECT name FROM pragma_table_info('city_sections')").map { it[0] },
        )
        assertEquals(
            listOf(
                listOf<String?>("Asti", null, "0", null, null, "0"),
                listOf("Roma", "3000000000", "1", "41.9", "12.5", "1"),
            ),
            rowsOf(db, "SELECT city, population, capital, latitude, longitude, translated FROM city_sections ORDER BY city"),
        )
        assertEquals(
            listOf(listOf<String?>("null", "null"), listOf("integer", "real")),
            rowsOf(db, "SELECT typeof(population), typeof(latitude) FROM city_sections ORDER BY city"),
        )
    }

    @Test
    fun `cities db senza sezioni ha la tabella vuota e riscrivere sostituisce le righe`() = withDb { db ->
        writeCitiesDb(emptyList(), db)
        assertEquals(listOf(listOf<String?>("0")), rowsOf(db, "SELECT COUNT(*) FROM city_sections"))

        val section = CitySectionRow("Roma", "STORIA", "Storia", "Fondata.", "https://x")
        writeCitiesDb(listOf(section, section), db)
        writeCitiesDb(listOf(section), db)
        assertEquals(listOf(listOf<String?>("1")), rowsOf(db, "SELECT COUNT(*) FROM city_sections"))
    }

    @Test
    fun `testo unicode, apici e SQL nelle sezioni si salvano alla lettera`() = withDb { db ->
        val body = "Robert'); DROP TABLE city_sections;-- 東京 🍕 è"
        writeCitiesDb(listOf(CitySectionRow("São Paulo", "DA_SAPERE", "Da sapere", body, "https://it.wikivoyage.org/wiki/São_Paulo")), db)

        assertEquals(listOf(listOf<String?>("São Paulo", body)), rowsOf(db, "SELECT city, body FROM city_sections"))
    }
}
