package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
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
    fun `emergencyNumbersLine salta Generale se la regione non ha un numero unico`() {
        assertEquals("Numeri di emergenza: Polizia 110, Ambulanza 119, Vigili del fuoco 119", emergencyNumbersLine("giappone"))
    }

    @Test
    fun `emergencyNumbersLine e' null per una regione senza numeri mappati`() {
        assertEquals(null, emergencyNumbersLine("iraq"))
        assertEquals(null, emergencyNumbersLine("regione-sconosciuta"))
    }
}
