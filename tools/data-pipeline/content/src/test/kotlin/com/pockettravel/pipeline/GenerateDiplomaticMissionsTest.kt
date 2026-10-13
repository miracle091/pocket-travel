package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate

class GenerateDiplomaticMissionsTest {
    private val tsvLine = listOf("Q1", "it", "fr", "embassy", "Ambasciata d'Italia", "Embassy of Italy", "Parigi", "", "", "https://x.example", "", "48.85", "2.35")
        .joinToString("\t")

    private fun tempFile(suffix: String) = File.createTempFile("pocket-travel-test", suffix).also { it.delete() }

    private fun count(db: File): Int = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM diplomatic_missions").getInt(1) }
    }

    @Test
    fun `scrive la tabella dal TSV con NULL per i campi vuoti e le coordinate come numeri`() {
        val tsv = tempFile(".tsv")
        val db = tempFile(".guides.db")
        try {
            tsv.writeText(tsvLine + "\n")
            writeDiplomaticMissions(tsv, null, db)

            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT name, address, lat, typeof(lat), lon FROM diplomatic_missions WHERE wikidata = 'Q1'")
                    assertEquals("Ambasciata d'Italia", rs.getString(1))
                    assertNull(rs.getString(2))
                    assertEquals(48.85, rs.getDouble(3), 0.0)
                    assertEquals("real", rs.getString(4))
                    assertEquals(2.35, rs.getDouble(5), 0.0)
                    val index = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'diplomatic_missions_pair'")
                    assertEquals(true, index.next())
                }
            }
        } finally {
            listOf(tsv, db).forEach { it.delete() }
        }
    }

    @Test
    fun `senza TSV ricopia la tabella pubblicata, e senza nemmeno quella non crea la tabella`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val withCopy = tempFile(".guides.db")
        val without = tempFile(".guides.db")
        try {
            tsv.writeText(tsvLine + "\n")
            writeDiplomaticMissions(tsv, null, published)

            writeDiplomaticMissions(null, published, withCopy)
            assertEquals(1, count(withCopy))
            assertEquals(true, sameGuidesContentOfMissions(published, withCopy))

            writeDiplomaticMissions(null, null, without)
            assertEquals(false, without.exists() && readRows(without, "SELECT wikidata FROM diplomatic_missions") != null)
        } finally {
            listOf(tsv, published, withCopy, without).forEach { it.delete() }
        }
    }

    @Test
    fun `salta le righe malformate e scarta i valori non validi senza fermare le altre`() {
        val tsv = tempFile(".tsv")
        val db = tempFile(".guides.db")
        fun line(id: String, site: String, lat: String, lon: String) =
            listOf(id, "it", "fr", "embassy", "Ambasciata", "", "", "", "", site, "", lat, lon).joinToString("\t")
        try {
            tsv.writeText(
                listOf(
                    line("Q1", "https://x.example", "1e", "2.35"),
                    line("Q2", "javascript:alert(1)", "48.85", "--"),
                    line("Q3", "http://y.example", "95", "2.35"),
                    "Q4\tit\tfr",
                    listOf("Q5", "it", "", "embassy", "Ambasciata", "", "", "", "", "", "", "", "").joinToString("\t"),
                    line("Q6", "https://ok.example", "48.85", "2.35"),
                ).joinToString("\n") + "\n",
            )
            writeDiplomaticMissions(tsv, null, db)

            assertEquals(4, count(db))
            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rows = statement.executeQuery("SELECT wikidata, website, lat, lon FROM diplomatic_missions ORDER BY 1")
                    val seen = mutableListOf<List<Any?>>()
                    while (rows.next()) {
                        seen += listOf(rows.getString(1), rows.getString(2), rows.getObject(3), rows.getObject(4))
                    }
                    assertEquals(
                        listOf(
                            listOf("Q1", "https://x.example", null, null),
                            listOf("Q2", null, null, null),
                            listOf("Q3", "http://y.example", null, null),
                            listOf("Q6", "https://ok.example", 48.85, 2.35),
                        ),
                        seen,
                    )
                }
            }
        } finally {
            listOf(tsv, db).forEach { it.delete() }
        }
    }

    @Test
    fun `solo le missioni cambiate non pubblicano un nuovo guides db prima di 14 giorni`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val fresh = tempFile(".guides.db")
        val guides = listOf(RegionGuide("italia", "https://it.wikivoyage.org/wiki/Italia", listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "testo"))))
        val day0 = LocalDate.of(2026, 10, 1)
        fun build(db: File, today: LocalDate, tsvText: String, base: File? = null, list: List<RegionGuide> = guides) {
            tsv.writeText(tsvText)
            writeGuidesDb(list, db)
            writeDiplomaticMissions(tsv, base, db, today)
        }
        try {
            build(published, day0, tsvLine + "\n")
            assertEquals(day0, readMissionsDate(published))

            // Missioni diverse, guide uguali: entro 14 giorni si tiene il pubblicato, il 15esimo no.
            val changed = tsvLine.replace("Parigi", "Lione") + "\n"
            build(fresh, day0.plusDays(14), changed, published)
            assertEquals(true, keepPublishedGuides(fresh, published, day0.plusDays(14)))
            assertEquals(false, keepPublishedGuides(fresh, published, day0.plusDays(15)))

            // Guide cambiate: si pubblica subito, con le missioni fresche.
            fresh.delete()
            build(fresh, day0.plusDays(1), changed, published, listOf(guides[0].copy(sections = listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "nuovo")))))
            assertEquals(false, keepPublishedGuides(fresh, published, day0.plusDays(1)))
            assertEquals(day0.plusDays(1), readMissionsDate(fresh))

            // Contenuto identico (missioni comprese): si tiene sempre il pubblicato, anche se vecchio.
            fresh.delete()
            build(fresh, day0.plusDays(60), tsvLine + "\n", published)
            assertEquals(true, keepPublishedGuides(fresh, published, day0.plusDays(60)))

            // Pubblicato senza data (guides.db precedente): si considera vecchio.
            DriverManager.getConnection("jdbc:sqlite:${published.path}").use { it.createStatement().use { s -> s.execute("DROP TABLE guides_meta") } }
            fresh.delete()
            build(fresh, day0.plusDays(1), changed, published)
            assertEquals(false, keepPublishedGuides(fresh, published, day0.plusDays(1)))
        } finally {
            listOf(tsv, published, fresh).forEach { it.delete() }
        }
    }

    @Test
    fun `le missioni ricopiate dal pubblicato ne mantengono la data`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val copy = tempFile(".guides.db")
        try {
            tsv.writeText(tsvLine + "\n")
            writeDiplomaticMissions(tsv, null, published, LocalDate.of(2026, 9, 1))
            writeDiplomaticMissions(null, published, copy, LocalDate.of(2026, 10, 1))
            assertEquals(LocalDate.of(2026, 9, 1), readMissionsDate(copy))
        } finally {
            listOf(tsv, published, copy).forEach { it.delete() }
        }
    }

    private fun sameGuidesContentOfMissions(a: File, b: File): Boolean {
        val sql = "SELECT wikidata, lat, lon, name FROM diplomatic_missions ORDER BY 1"
        return readRows(a, sql) == readRows(b, sql)
    }

    private fun missionLine(
        id: String = "Q1",
        site: String = "",
        lat: String = "",
        lon: String = "",
        name: String = "Ambasciata",
        extra: List<String> = emptyList(),
    ) = (listOf(id, "it", "fr", "embassy", name, "", "", "", "", site, "", lat, lon) + extra).joinToString("\t")

    private fun readRowsOf(tsvText: String): List<List<String?>> {
        val tsv = tempFile(".tsv")
        try {
            tsv.writeText(tsvText)
            return readMissionsTsv(tsv)
        } finally {
            tsv.delete()
        }
    }

    @Test
    fun `readMissionsTsv salta righe vuote, con colonne in piu' o in meno, e mette null ai campi vuoti`() {
        val rows = readRowsOf(
            "\n" + missionLine("Q1") + "\n   \n" + missionLine("Q2", extra = listOf("in piu'")) + "\n" +
                "Q3\tit\n" + missionLine("Q4") + "\n",
        )

        assertEquals(listOf("Q1", "Q4"), rows.map { it[0] })
        assertEquals(listOf("Q1", "it", "fr", "embassy", "Ambasciata", null, null, null, null, null, null, null, null), rows[0])
    }

    @Test
    fun `readMissionsTsv richiede i primi cinque campi e conserva nomi unicode`() {
        val name = "大使館 – Ambasciata d'Italia"
        val rows = readRowsOf(
            listOf(
                missionLine("Q1", name = name),
                missionLine("Q2", name = ""),
                listOf("", "it", "fr", "embassy", "x", "", "", "", "", "", "", "", "").joinToString("\t"),
                listOf("Q4", "", "fr", "embassy", "x", "", "", "", "", "", "", "", "").joinToString("\t"),
                listOf("Q5", "it", "fr", "", "x", "", "", "", "", "", "", "", "").joinToString("\t"),
            ).joinToString("\n"),
        )

        assertEquals(listOf("Q1"), rows.map { it[0] })
        assertEquals(name, rows.single()[4])
    }

    @Test
    fun `le coordinate ai limiti sono valide, oltre i limiti o non finite si scartano in coppia`() {
        fun coordinates(lat: String, lon: String): Pair<String?, String?> =
            readRowsOf(missionLine(lat = lat, lon = lon)).single().let { it[11] to it[12] }

        assertEquals("90" to "180", coordinates("90", "180"))
        assertEquals("-90" to "-180", coordinates("-90", "-180"))
        assertEquals("0" to "0", coordinates("0", "0"))
        assertEquals(null to null, coordinates("90.0001", "10"))
        assertEquals(null to null, coordinates("10", "-180.5"))
        assertEquals(null to null, coordinates("NaN", "10"))
        assertEquals(null to null, coordinates("10", "Infinity"))
        assertEquals(null to null, coordinates("10", ""))
        assertEquals(null to null, coordinates("", "10"))
        assertEquals(null to null, coordinates("abc", "def"))
    }

    @Test
    fun `il sito si tiene solo se e' http o https, senza distinguere le maiuscole`() {
        fun website(site: String): String? = readRowsOf(missionLine(site = site)).single()[9]

        assertEquals("HTTPS://X.EXAMPLE", website("HTTPS://X.EXAMPLE"))
        assertEquals("http://x.example", website("http://x.example"))
        assertEquals(null, website("ftp://x.example"))
        assertEquals(null, website("www.x.example"))
        assertEquals(null, website("javascript:alert(1)"))
        assertEquals(null, website(""))
    }

    @Test
    fun `un wikidata ripetuto tiene l'ultima riga`() {
        val tsv = tempFile(".tsv")
        val db = tempFile(".guides.db")
        try {
            tsv.writeText(missionLine("Q1", name = "Prima") + "\n" + missionLine("Q1", name = "Seconda") + "\n")
            writeDiplomaticMissions(tsv, null, db)

            assertEquals(listOf(listOf("Seconda")), readRows(db, "SELECT name FROM diplomatic_missions"))
        } finally {
            listOf(tsv, db).forEach { it.delete() }
        }
    }

    @Test
    fun `la tabella ha le colonne e i tipi attesi`() {
        val db = tempFile(".guides.db")
        try {
            writeDiplomaticMissionsTable(emptyList(), db)

            val columns = readRows(db, "SELECT name, type, \"notnull\", pk FROM pragma_table_info('diplomatic_missions')")
            assertEquals(
                listOf(
                    listOf("wikidata", "TEXT", "1", "1"), listOf("sending", "TEXT", "1", "0"), listOf("host", "TEXT", "1", "0"),
                    listOf("kind", "TEXT", "1", "0"), listOf("name", "TEXT", "1", "0"), listOf("name_en", "TEXT", "0", "0"),
                    listOf("city", "TEXT", "0", "0"), listOf("address", "TEXT", "0", "0"), listOf("phone", "TEXT", "0", "0"),
                    listOf("website", "TEXT", "0", "0"), listOf("email", "TEXT", "0", "0"), listOf("lat", "REAL", "0", "0"),
                    listOf("lon", "REAL", "0", "0"),
                ),
                columns,
            )
        } finally {
            db.delete()
        }
    }

    @Test
    fun `una coordinata non numerica nella riga diventa NULL invece di far fallire la scrittura`() {
        val db = tempFile(".guides.db")
        try {
            writeDiplomaticMissionsTable(
                listOf(listOf("Q1", "it", "fr", "embassy", "x", null, null, null, null, null, null, "non-un-numero", "2.5")),
                db,
            )

            assertEquals(listOf(listOf(null, "2.5")), readRows(db, "SELECT lat, lon FROM diplomatic_missions"))
        } finally {
            db.delete()
        }
    }

    @Test
    fun `un TSV vuoto equivale a un TSV assente e ricopia le missioni pubblicate`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val copy = tempFile(".guides.db")
        val none = tempFile(".guides.db")
        try {
            tsv.writeText(missionLine() + "\n")
            writeDiplomaticMissions(tsv, null, published)
            tsv.writeText("")

            writeDiplomaticMissions(tsv, published, copy)
            writeDiplomaticMissions(tsv, null, none)

            assertEquals(1, count(copy))
            assertEquals(false, none.exists())
        } finally {
            listOf(tsv, published, copy, none).forEach { it.delete() }
        }
    }

    @Test
    fun `se tutte le righe del TSV sono scartate la tabella resta vuota e porta la data di oggi`() {
        val tsv = tempFile(".tsv")
        val db = tempFile(".guides.db")
        try {
            tsv.writeText("Q1\tit\n")
            writeDiplomaticMissions(tsv, null, db, LocalDate.of(2026, 10, 1))

            assertEquals(0, count(db))
            assertEquals(LocalDate.of(2026, 10, 1), readMissionsDate(db))
        } finally {
            listOf(tsv, db).forEach { it.delete() }
        }
    }

    @Test
    fun `le missioni ricopiate da un pubblicato senza data non scrivono guides_meta`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val copy = tempFile(".guides.db")
        try {
            tsv.writeText(missionLine() + "\n")
            writeDiplomaticMissions(tsv, null, published, LocalDate.of(2026, 9, 1))
            DriverManager.getConnection("jdbc:sqlite:${published.path}").use { it.createStatement().use { s -> s.execute("DROP TABLE guides_meta") } }

            writeDiplomaticMissions(null, published, copy, LocalDate.of(2026, 10, 1))

            assertEquals(1, count(copy))
            assertEquals(null, readMissionsDate(copy))
            assertEquals(null, readRows(copy, "SELECT * FROM guides_meta"))
        } finally {
            listOf(tsv, published, copy).forEach { it.delete() }
        }
    }

    @Test
    fun `readMissionsDate e' null senza tabella, con tabella vuota o con data illeggibile`() {
        val db = tempFile(".guides.db")
        try {
            writeSqliteTable(db, "altro", "CREATE TABLE altro (x TEXT)", "INSERT INTO altro (x) VALUES (?)", listOf("a")) { s, v -> s.setString(1, v) }
            assertEquals(null, readMissionsDate(db))

            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { it.createStatement().use { s -> s.execute("CREATE TABLE guides_meta (missions_date TEXT)") } }
            assertEquals(null, readMissionsDate(db))

            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { it.createStatement().use { s -> s.execute("INSERT INTO guides_meta VALUES ('ieri')") } }
            assertEquals(null, readMissionsDate(db))
        } finally {
            db.delete()
        }
    }

    @Test
    fun `keepPublishedGuides - missioni recenti non bastano se i consigli di viaggio sono cambiati oltre la settimana`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val fresh = tempFile(".guides.db")
        val day0 = LocalDate.of(2026, 10, 1)
        val wikivoyage = GuideSectionRow("SICUREZZA", "Stay safe", "Watch your bags.")
        fun advice(text: String) = GuideSectionRow("SICUREZZA", "Safety", text, "https://$TRAVEL_ADVICE_HOST/destinations/it")
        fun build(db: File, adviceText: String, fetched: LocalDate, tsvText: String) {
            writeGuidesDb(listOf(RegionGuide("italia", "https://example.org/it", listOf(wikivoyage, advice(adviceText)))), db)
            tsv.writeText(tsvText)
            writeDiplomaticMissions(tsv, null, db, day0)
            writeTravelAdviceMeta(mapOf("italia" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, fetched))), db)
        }
        try {
            build(published, "old", day0, missionLine() + "\n")
            build(fresh, "new", day0.plusDays(8), missionLine(name = "Altro nome") + "\n")

            // Missioni di 3 giorni e consigli pubblicati di 3 giorni: si tiene il pubblicato.
            assertEquals(true, keepPublishedGuides(fresh, published, day0.plusDays(3)))
            // Missioni di 8 giorni (ancora entro 14), ma consigli pubblicati di 8: si pubblica.
            assertEquals(false, keepPublishedGuides(fresh, published, day0.plusDays(8)))
        } finally {
            listOf(tsv, published, fresh).forEach { it.delete() }
        }
    }

    @Test
    fun `keepPublishedGuides - un contenuto identico vince anche con la data delle missioni molto diversa`() {
        val tsv = tempFile(".tsv")
        val published = tempFile(".published.db")
        val same = tempFile(".guides.db")
        val day0 = LocalDate.of(2026, 10, 1)
        val guides = listOf(RegionGuide("italia", "https://example.org/it", listOf(GuideSectionRow("SICUREZZA", "Sicurezza", "testo"))))
        try {
            tsv.writeText(missionLine() + "\n")
            writeGuidesDb(guides, published)
            writeDiplomaticMissions(tsv, null, published, day0)
            writeGuidesDb(guides, same)
            writeDiplomaticMissions(tsv, null, same, day0.plusDays(100))

            assertEquals(true, keepPublishedGuides(same, published, day0.plusDays(100)))
        } finally {
            listOf(tsv, published, same).forEach { it.delete() }
        }
    }
}
