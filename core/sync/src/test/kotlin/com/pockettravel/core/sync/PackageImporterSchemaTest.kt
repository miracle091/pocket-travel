package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

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
    fun `emergency_numbers_none si legge solo se guides db ha la tabella`() = withDb { conn ->
        conn.createStatement().use { statement ->
            assertEquals(false, statement.executeQuery(GuidesImporter.NO_CENTRAL_NUMBER_TABLE_QUERY).next())
            statement.execute("CREATE TABLE emergency_numbers_none (regionId TEXT NOT NULL)")
            statement.execute("INSERT INTO emergency_numbers_none VALUES ('test-region')")
        }
        conn.createStatement().use { statement ->
            assertEquals(true, statement.executeQuery(GuidesImporter.NO_CENTRAL_NUMBER_TABLE_QUERY).next())
            val rs = statement.executeQuery(GuidesImporter.NO_CENTRAL_NUMBER_QUERY)
            assertEquals(true, rs.next())
            assertEquals("test-region", rs.getString("regionId"))
            assertEquals(false, rs.next())
        }
    }

    @Test
    fun `diplomatic_missions si legge solo se guides db ha la tabella`() = withDb { conn ->
        conn.createStatement().use { statement ->
            assertEquals(false, statement.executeQuery(GuidesImporter.DIPLOMATIC_MISSIONS_TABLE_QUERY).next())
            statement.execute(
                "CREATE TABLE diplomatic_missions (wikidata TEXT NOT NULL PRIMARY KEY, sending TEXT NOT NULL, host TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, name TEXT NOT NULL, name_en TEXT, city TEXT, address TEXT, phone TEXT, website TEXT, " +
                    "email TEXT, lat REAL, lon REAL)",
            )
            statement.execute(
                "INSERT INTO diplomatic_missions VALUES ('Q1', 'it', 'es', 'embassy', 'Embajada de Italia', 'Embassy of Italy', 'Madrid', " +
                    "NULL, '+34 91 1234567', 'https://amb.esteri.it', NULL, 40.4, -3.7)",
            )
        }
        conn.createStatement().use { statement ->
            assertEquals(true, statement.executeQuery(GuidesImporter.DIPLOMATIC_MISSIONS_TABLE_QUERY).next())
            val rs = statement.executeQuery(GuidesImporter.DIPLOMATIC_MISSIONS_QUERY)
            assertEquals(true, rs.next())
            assertEquals("Q1", rs.getString("wikidata"))
            assertEquals("it", rs.getString("sending"))
            assertEquals("es", rs.getString("host"))
            assertEquals("embassy", rs.getString("kind"))
            assertEquals("Embassy of Italy", rs.getString("name_en"))
            assertEquals("Madrid", rs.getString("city"))
            assertEquals(null, rs.getString("address"))
            assertEquals("https://amb.esteri.it", rs.getString("website"))
            assertEquals(40.4, rs.getDouble("lat"), 1e-9)
            assertEquals(false, rs.next())
        }
    }

    @Test
    fun `la query poi legacy legge i content db v1 senza colonna phone`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute("CREATE TABLE poi (regionId TEXT, name TEXT, category TEXT, lat REAL, lon REAL, osmTag TEXT)")
            statement.execute("INSERT INTO poi VALUES ('test-region', 'Punto panoramico', 'viewpoint', 45.0, 9.0, 'tourism=viewpoint')")
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(PoiImporter.poiQuery(setOf("regionId", "name", "category", "lat", "lon", "osmTag")))
            assertEquals(true, rs.next())
            assertEquals("Punto panoramico", rs.getString("name"))
            assertEquals("tourism=viewpoint", rs.getString("osmTag"))
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
                    phone TEXT,
                    wheelchair TEXT
                )
                """.trimIndent()
            )
            statement.execute(
                "INSERT INTO poi VALUES ('test-region', 'Ambasciata', 'embassy', 45.4646, 9.1908, 'amenity=embassy', '+39 06 1234567', 'yes')"
            )
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(PoiImporter.poiQuery(setOf("name", "phone", "wheelchair")))
            assertEquals(true, rs.next())
            assertEquals("Ambasciata", rs.getString("name"))
            assertEquals("embassy", rs.getString("category"))
            assertEquals(45.4646, rs.getDouble("lat"), 1e-9)
            assertEquals(9.1908, rs.getDouble("lon"), 1e-9)
            assertEquals("amenity=embassy", rs.getString("osmTag"))
            assertEquals("+39 06 1234567", rs.getString("phone"))
            assertEquals("yes", rs.getString("wheelchair"))
        }
    }

    @Test
    fun `la query poi compatta legge poi_code e le coordinate in microgradi di poi db`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE poi_code (code INTEGER NOT NULL PRIMARY KEY, category TEXT NOT NULL, osmTag TEXT NOT NULL)"
            )
            statement.execute("INSERT INTO poi_code VALUES (0, 'embassy', 'amenity=embassy')")
            statement.execute(
                """
                CREATE TABLE poi (
                    name TEXT NOT NULL,
                    code INTEGER NOT NULL,
                    latE6 INTEGER NOT NULL,
                    lonE6 INTEGER NOT NULL,
                    phone TEXT,
                    wheelchair TEXT
                )
                """.trimIndent()
            )
            statement.execute("INSERT INTO poi VALUES ('Ambasciata', 0, 45464600, 9190800, '+39 06 1234567', 'yes')")
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(PoiImporter.compactPoiQuery(setOf("name", "code", "latE6", "lonE6", "phone", "wheelchair")))
            assertEquals(true, rs.next())
            assertEquals("Ambasciata", rs.getString("name"))
            assertEquals("embassy", rs.getString("category"))
            assertEquals(45.4646, rs.getDouble("lat"), 1e-9)
            assertEquals(9.1908, rs.getDouble("lon"), 1e-9)
            assertEquals("amenity=embassy", rs.getString("osmTag"))
            assertEquals("+39 06 1234567", rs.getString("phone"))
            assertEquals("yes", rs.getString("wheelchair"))
            assertEquals(false, rs.next())
        }
    }

    @Test
    fun `la query poi compatta aggiunge orari e indirizzo solo se il file li ha`() {
        val old = PoiImporter.compactPoiQuery(setOf("name", "code", "latE6", "lonE6", "phone", "wheelchair"))
        assertEquals(false, old.contains("openingHours"))
        val new = PoiImporter.compactPoiQuery(setOf("name", "phone", "wheelchair", "openingHours", "address"))
        assertEquals(true, new.contains("poi.wheelchair, poi.openingHours, poi.address FROM"))
    }

    @Test
    fun `la query poi compatta legge bagni accessibili e posti per disabili solo se il file li ha`() = withDb { conn ->
        val old = PoiImporter.compactPoiQuery(setOf("name", "code", "latE6", "lonE6", "phone", "wheelchair"))
        assertEquals(false, old.contains("toiletsWheelchair") || old.contains("capacityDisabled"))
        conn.createStatement().use { statement ->
            statement.execute("CREATE TABLE poi_code (code INTEGER NOT NULL PRIMARY KEY, category TEXT NOT NULL, osmTag TEXT NOT NULL)")
            statement.execute("INSERT INTO poi_code VALUES (0, 'parking', 'amenity=parking')")
            statement.execute(
                "CREATE TABLE poi (name TEXT NOT NULL, code INTEGER NOT NULL, latE6 INTEGER NOT NULL, lonE6 INTEGER NOT NULL, " +
                    "toiletsWheelchair TEXT, capacityDisabled INTEGER)"
            )
            statement.execute("INSERT INTO poi VALUES ('P1', 0, 45464600, 9190800, 'yes', 3), ('P2', 0, 45464700, 9190900, NULL, NULL)")
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(PoiImporter.compactPoiQuery(setOf("name", "code", "latE6", "lonE6", "toiletsWheelchair", "capacityDisabled")) + " ORDER BY poi.name")
            assertEquals(true, rs.next())
            assertEquals("yes", rs.getString("toiletsWheelchair"))
            assertEquals(3, rs.getInt("capacityDisabled"))
            assertEquals(true, rs.next())
            assertEquals(null, rs.getString("toiletsWheelchair"))
            assertEquals(null, rs.getString("capacityDisabled"))
        }
    }

    @Test
    fun `la query poi seleziona solo le colonne facoltative presenti nel file`() {
        assertEquals(
            "SELECT name, category, lat, lon, osmTag, phone FROM poi",
            PoiImporter.poiQuery(setOf("regionId", "name", "category", "lat", "lon", "osmTag", "phone")),
        )
    }

    @Test
    fun `la query city_sections legge le colonne di cities db`() = withDb { conn ->
        conn.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE city_sections (
                    city TEXT NOT NULL,
                    category TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                "INSERT INTO city_sections VALUES ('Roma', 'COSA_VEDERE', 'Cosa vedere', 'body', 'https://it.wikivoyage.org/wiki/Roma')"
            )
        }
        conn.createStatement().use { statement ->
            val rs = statement.executeQuery(CityImporter.CITY_SECTIONS_QUERY)
            assertEquals(true, rs.next())
            assertEquals("Roma", rs.getString("city"))
            assertEquals("COSA_VEDERE", rs.getString("category"))
            assertEquals("Cosa vedere", rs.getString("title"))
            assertEquals("body", rs.getString("body"))
            assertEquals("https://it.wikivoyage.org/wiki/Roma", rs.getString("sourceUrl"))
        }
    }
}
