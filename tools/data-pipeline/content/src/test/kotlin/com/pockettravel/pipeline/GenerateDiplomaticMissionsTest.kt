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
}
