package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class GenerateEmergencyNumbersTest {

    @Test
    fun `scrive una riga per ogni regione con numeri mappati e nessuna per le altre`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            writeEmergencyNumbersTable(listOf("italia", "regione-sconosciuta", "giappone", "iraq"), outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT regionId, general, police, ambulance, fire FROM emergency_numbers ORDER BY regionId")
                    assertEquals(true, rs.next())
                    assertEquals("giappone", rs.getString("regionId"))
                    assertEquals(null, rs.getString("general"))
                    assertEquals("110", rs.getString("police"))
                    assertEquals(true, rs.next())
                    assertEquals("italia", rs.getString("regionId"))
                    assertEquals("112", rs.getString("general"))
                    assertEquals("113", rs.getString("police"))
                    assertEquals("118", rs.getString("ambulance"))
                    assertEquals("115", rs.getString("fire"))
                    assertEquals(false, rs.next())
                }
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT regionId FROM emergency_numbers_none")
                    assertEquals(true, rs.next())
                    assertEquals("iraq", rs.getString("regionId"))
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `senza regioni le due tabelle esistono e sono vuote`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db")
        outputDb.delete()

        try {
            writeEmergencyNumbersTable(emptyList(), outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    for (table in listOf("emergency_numbers", "emergency_numbers_none")) {
                        val rs = statement.executeQuery("SELECT COUNT(*) FROM $table")
                        assertEquals(true, rs.next())
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `emergencyNumbersLine salta Generale se la regione non ha un numero unico`() {
        assertEquals("Numeri di emergenza: Polizia 110, Ambulanza 119, Vigili del fuoco 119", emergencyNumbersLine("giappone"))
    }

    @Test
    fun `emergencyNumbersLine e' null per una regione senza numeri mappati`() {
        assertEquals(null, emergencyNumbersLine("iraq"))
        assertEquals(null, emergencyNumbersLine("regione-sconosciuta"))
    }

    // --- dati e riga di testo ---

    private fun emergencyRows(): List<List<String>> =
        requireNotNull(EmergencyNumbers::class.java.getResource("/emergency-numbers.tsv")) { "emergency-numbers.tsv mancante" }
            .readText().lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }.toList()

    private fun regionIdsOfRegionsScript(): Set<String> {
        val script = File("../scripts/regions.sh").takeIf { it.exists() } ?: File("scripts/regions.sh")
        return script.readLines().map { it.trim() }
            .filter { it.startsWith("\"") && it.count { c -> c == '|' } >= 10 }
            .map { it.trim('"').substringBefore('|') }.toSet()
    }

    @Test
    fun `il tsv ha sette colonne per riga, regionId minuscoli senza duplicati e numeri a cifre`() {
        val rows = emergencyRows()
        assertTrue("troppe poche righe: ${rows.size}", rows.size > 300)
        rows.forEach { row ->
            assertEquals("${row.firstOrNull()}: colonne", 7, row.size)
            assertTrue("regionId non valido: ${row[0]}", row[0].matches(Regex("[a-z0-9-]+")))
            // Le righe senza numero centralizzato non hanno nessuno dei tre numeri; le altre li hanno tutti e tre.
            val police = row.subList(2, 5)
            assertTrue("${row[0]}: numeri a meta'", police.all { it.isEmpty() } || police.all { it.isNotEmpty() })
            row.subList(1, 5).forEach { number -> assertTrue("${row[0]}: numero '$number'", number.matches(Regex("([0-9]+( [0-9]+)*)?"))) }
            if (row[2].isEmpty()) assertEquals("${row[0]}: generale senza gli altri numeri", "", row[1])
        }
        assertEquals("regionId duplicati", rows.size, rows.map { it[0] }.toSet().size)
    }

    @Test
    fun `ogni regione del tsv dei numeri di emergenza e' ancora in regions_sh`() {
        val known = regionIdsOfRegionsScript()
        assertTrue("nessuna regione letta da regions.sh", known.size > 100)

        val stale = emergencyRows().map { it[0] }.filterNot { it in known }

        assertEquals("righe di regioni che non esistono piu'", emptyList<String>(), stale)
    }

    @Test
    fun `emergencyNumbersLine in inglese ha le etichette inglesi e salta General come in italiano`() {
        assertEquals("Emergency numbers: Police 110, Ambulance 119, Fire 119", emergencyNumbersLine("giappone", english = true))
        assertEquals("Emergency numbers: General 112, Police 113, Ambulance 118, Fire 115", emergencyNumbersLine("italia", english = true))
        assertEquals(null, emergencyNumbersLine("iraq", english = true))
    }

    @Test
    fun `la riga di testo e la tabella dicono gli stessi numeri per ogni regione con numeri`() {
        val rows = emergencyRows().filter { it[2].isNotEmpty() }
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db").also { it.delete() }
        try {
            writeEmergencyNumbersTable(rows.map { it[0] }, outputDb)

            val written = readRows(outputDb, "SELECT regionId, general, police, ambulance, fire FROM emergency_numbers ORDER BY rowid").orEmpty()
            assertEquals(rows.size, written.size)
            written.forEach { (regionId, general, police, ambulance, fire) ->
                val line = emergencyNumbersLine(regionId.orEmpty()).orEmpty()
                assertTrue("$regionId: '$line' senza $police/$ambulance/$fire", "Polizia $police, Ambulanza $ambulance, Vigili del fuoco $fire" in line)
                assertEquals("$regionId: Generale", general != null, "Generale " in line)
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `le regioni senza numero centralizzato stanno solo nella tabella none e l'ordine segue quello richiesto`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".guides.db").also { it.delete() }
        try {
            writeEmergencyNumbersTable(listOf("iraq", "italia", "giappone", "iraq"), outputDb)

            assertEquals(listOf("italia", "giappone"), readRows(outputDb, "SELECT regionId FROM emergency_numbers ORDER BY rowid").orEmpty().map { it[0] })
            assertEquals(listOf("iraq", "iraq"), readRows(outputDb, "SELECT regionId FROM emergency_numbers_none ORDER BY rowid").orEmpty().map { it[0] })
        } finally {
            outputDb.delete()
        }
    }
}
