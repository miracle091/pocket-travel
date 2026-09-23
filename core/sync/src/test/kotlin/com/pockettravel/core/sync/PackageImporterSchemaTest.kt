package com.pockettravel.core.sync

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica a livello di schema che le query di GuidesImporter e PoiImporter corrispondano alle
 * colonne che tools/data-pipeline scrive in guides.db e poi.db — via JDBC puro
 * (org.xerial:sqlite-jdbc), perche' android.database.sqlite.SQLiteDatabase (usato dal codice
 * reale) richiede un device/emulatore e non gira in un test JVM.
 */
class PackageImporterSchemaTest {

    private fun withDb(block: (java.sql.Connection) -> Unit) {
        val dbFile = File(createTempDirectory("pocket-travel-test").toFile(), "test.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use(block)
    }

    @Test
    fun `la query guide_sections legge le colonne di guides db`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE guide_sections (
                    regionId TEXT NOT NULL,
                    category TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                "INSERT INTO guide_sections VALUES ('test-region', 'TRASPORTI', 'Get around', 'body', 'https://example.org')"
            )
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(GuidesImporter.GUIDE_SECTIONS_QUERY)
            assertEquals(true, rs.next())
            assertEquals("test-region", rs.getString("regionId"))
            assertEquals("TRASPORTI", rs.getString("category"))
            assertEquals("Get around", rs.getString("title"))
            assertEquals("body", rs.getString("body"))
            assertEquals("https://example.org", rs.getString("sourceUrl"))
        }
    }

    @Test
    fun `la query emergency_numbers legge le colonne di guides db`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE emergency_numbers (
                    regionId TEXT NOT NULL,
                    general TEXT,
                    police TEXT NOT NULL,
                    ambulance TEXT NOT NULL,
                    fire TEXT NOT NULL
                )
                """.trimIndent()
            )
            statement.execute("INSERT INTO emergency_numbers VALUES ('test-region', '112', '113', '118', '115')")
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(GuidesImporter.EMERGENCY_NUMBERS_QUERY)
            assertEquals(true, rs.next())
            assertEquals("test-region", rs.getString("regionId"))
            assertEquals("112", rs.getString("general"))
            assertEquals("113", rs.getString("police"))
            assertEquals("118", rs.getString("ambulance"))
            assertEquals("115", rs.getString("fire"))
        }
    }

    @Test
    fun `la query poi legge le colonne di poi db`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE poi (
                    regionId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    osmTag TEXT NOT NULL,
                    phone TEXT
                )
                """.trimIndent()
            )
            statement.execute(
                "INSERT INTO poi VALUES ('test-region', 'Ambasciata', 'embassy', 45.4646, 9.1908, 'amenity=embassy', '+39 06 1234567')"
            )
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(PoiImporter.POI_QUERY)
            assertEquals(true, rs.next())
            assertEquals("Ambasciata", rs.getString("name"))
            assertEquals("embassy", rs.getString("category"))
            assertEquals(45.4646, rs.getDouble("lat"), 1e-9)
            assertEquals(9.1908, rs.getDouble("lon"), 1e-9)
            assertEquals("amenity=embassy", rs.getString("osmTag"))
            assertEquals("+39 06 1234567", rs.getString("phone"))
        }
    }
}
