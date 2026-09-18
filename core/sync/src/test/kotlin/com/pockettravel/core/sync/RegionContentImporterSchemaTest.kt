package com.pockettravel.core.sync

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica a livello di schema che le query di RegionContentImporter corrispondano
 * esattamente alle colonne che tools/data-pipeline scrive in content.db (guide_sections+poi
 * nello stesso file) — via JDBC puro (org.xerial:sqlite-jdbc), perche'
 * android.database.sqlite.SQLiteDatabase (usato dal codice reale) richiede un device/emulatore
 * reale e non gira in un test JVM.
 */
class RegionContentImporterSchemaTest {

    @Test
    fun `la query guide_sections legge le colonne prodotte dalla pipeline`() {
        val dbFile = File(createTempDirectory("pocket-travel-test").toFile(), "content.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
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
                val rs = statement.executeQuery(RegionContentImporter.GUIDE_SECTIONS_QUERY)
                assertEquals(true, rs.next())
                assertEquals("TRASPORTI", rs.getString("category"))
                assertEquals("Get around", rs.getString("title"))
                assertEquals("body", rs.getString("body"))
                assertEquals("https://example.org", rs.getString("sourceUrl"))
            }
        }
    }

    @Test
    fun `la query poi legge le colonne prodotte dalla pipeline`() {
        val dbFile = File(createTempDirectory("pocket-travel-test").toFile(), "content.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
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
                val rs = statement.executeQuery(RegionContentImporter.POI_QUERY)
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

    @Test
    fun `la query poi legacy (senza colonna phone) legge le colonne prodotte da pacchetti gia' pubblicati`() {
        val dbFile = File(createTempDirectory("pocket-travel-test").toFile(), "content.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE poi (
                        regionId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        category TEXT NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        osmTag TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "INSERT INTO poi VALUES ('test-region', 'Punto panoramico', 'viewpoint', 45.4646, 9.1908, 'tourism=viewpoint')"
                )
            }
            conn.createStatement().use { statement ->
                val rs = statement.executeQuery(RegionContentImporter.POI_QUERY_LEGACY)
                assertEquals(true, rs.next())
                assertEquals("Punto panoramico", rs.getString("name"))
                assertEquals("viewpoint", rs.getString("category"))
                assertEquals(45.4646, rs.getDouble("lat"), 1e-9)
                assertEquals(9.1908, rs.getDouble("lon"), 1e-9)
                assertEquals("tourism=viewpoint", rs.getString("osmTag"))
            }
        }
    }

    @Test
    fun `la query emergency_numbers legge le colonne prodotte dalla pipeline`() {
        val dbFile = File(createTempDirectory("pocket-travel-test").toFile(), "content.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
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
                statement.execute(
                    "INSERT INTO emergency_numbers VALUES ('test-region', '112', '113', '118', '115')"
                )
            }
            conn.createStatement().use { statement ->
                val rs = statement.executeQuery(RegionContentImporter.EMERGENCY_NUMBERS_QUERY)
                assertEquals(true, rs.next())
                assertEquals("112", rs.getString("general"))
                assertEquals("113", rs.getString("police"))
                assertEquals("118", rs.getString("ambulance"))
                assertEquals("115", rs.getString("fire"))
            }
        }
    }

    @Test
    fun `guide_sections e poi convivono nello stesso file content db`() {
        val dbFile = File(createTempDirectory("pocket-travel-test").toFile(), "content.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use { conn ->
            conn.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)"
                )
                statement.execute("CREATE TABLE poi (regionId TEXT, name TEXT, category TEXT, lat REAL, lon REAL, osmTag TEXT, phone TEXT)")
            }
            conn.createStatement().use { statement ->
                assertEquals(false, statement.executeQuery(RegionContentImporter.GUIDE_SECTIONS_QUERY).next())
                assertEquals(false, statement.executeQuery(RegionContentImporter.POI_QUERY).next())
            }
        }
    }
}
