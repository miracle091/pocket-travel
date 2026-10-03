package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

/**
 * CityImporter legge le colonne per posizione (indici 0-6): come PackageImporterSchemaTest, si esegue
 * la query via JDBC (android.database.sqlite non gira nei test JVM) e si controlla l'ordine delle colonne.
 * Il caso con population e capital (cities.db recenti) non e' coperto da PackageImporterSchemaTest.
 */
class CityImporterQueryTest {

    private fun withDb(block: (Connection) -> Unit) {
        val dbFile = File(createTempDirectory("pocket-travel-city-query-test").toFile(), "cities.db")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.path}").use(block)
    }

    private fun columnNames(rs: java.sql.ResultSet): List<String> = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }

    @Test
    fun `la query con popolazione legge population e capital dopo le cinque colonne di base`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE city_sections (
                    city TEXT NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL, population INTEGER, capital INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            statement.execute("INSERT INTO city_sections VALUES ('Roma', 'VEDERE', 'Colosseo', 'b', 'https://e.org/1', 2800000, 1)")
            statement.execute("INSERT INTO city_sections VALUES ('Borgo', 'VEDERE', 'Piazza', 'b', 'https://e.org/2', NULL, 0)")
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(CityImporter.CITY_SECTIONS_WITH_POPULATION_QUERY + " ORDER BY city DESC")
            assertEquals(
                listOf("city", "category", "title", "body", "sourceUrl", "population", "capital"),
                columnNames(rs),
            )
            assertTrue(rs.next())
            assertEquals("Roma", rs.getString(1))
            assertEquals(2_800_000L, rs.getLong(6))
            assertFalse(rs.wasNull())
            assertEquals(1, rs.getInt(7))
            assertTrue(rs.next())
            assertEquals("Borgo", rs.getString(1))
            rs.getLong(6)
            assertTrue("population NULL deve restare nulla", rs.wasNull())
            assertEquals(0, rs.getInt(7))
        }
    }

    @Test
    fun `la query di base funziona sui cities db senza population e capital`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE city_sections (city TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)",
            )
            statement.execute("INSERT INTO city_sections VALUES ('Roma', 'VEDERE', 'Colosseo', 'b', 'https://e.org/1')")
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(CityImporter.CITY_SECTIONS_QUERY)
            assertEquals(5, rs.metaData.columnCount)
            assertTrue(rs.next())
            assertEquals("Colosseo", rs.getString(3))
            // La query con popolazione su un file vecchio fallirebbe: per questo l'importer controlla PRAGMA table_info.
            val failure = runCatching { statement.executeQuery(CityImporter.CITY_SECTIONS_WITH_POPULATION_QUERY) }.exceptionOrNull()
            assertTrue(failure is java.sql.SQLException)
        }
    }
}
