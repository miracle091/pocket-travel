package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerateEmergencyNumbersTest {

    @Test
    fun `scrive la riga dei numeri di emergenza per una regione nota`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()

        try {
            val numbers = EmergencyNumbers(general = "112", police = "113", ambulance = "118", fire = "115")
            writeEmergencyNumbersDb(numbers, "test-region", outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT regionId, general, police, ambulance, fire FROM emergency_numbers")
                    assertEquals(true, rs.next())
                    assertEquals("test-region", rs.getString("regionId"))
                    assertEquals("112", rs.getString("general"))
                    assertEquals("113", rs.getString("police"))
                    assertEquals("118", rs.getString("ambulance"))
                    assertEquals("115", rs.getString("fire"))
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `nessuna riga per una regione senza numeri mappati`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()

        try {
            writeEmergencyNumbersDb(null, "regione-sconosciuta", outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT * FROM emergency_numbers")
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }
}
