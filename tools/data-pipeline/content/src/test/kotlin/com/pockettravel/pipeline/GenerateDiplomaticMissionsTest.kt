package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.sql.DriverManager

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

    private fun sameGuidesContentOfMissions(a: File, b: File): Boolean {
        val sql = "SELECT wikidata, lat, lon, name FROM diplomatic_missions ORDER BY 1"
        return readRows(a, sql) == readRows(b, sql)
    }
}
