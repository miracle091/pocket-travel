package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratePoiTest {

    @Test
    fun `estrae il POI taggato dall'estratto OSM di test`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()

        try {
            val pois = extractPois(parseOsmXml(File("testdata/tiny-region.osm.xml")))
            writePoiDb(pois, "test-region", outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT regionId, name, category, osmTag FROM poi")
                    assertEquals(true, rs.next())
                    assertEquals("test-region", rs.getString("regionId"))
                    assertEquals("Punto panoramico di prova", rs.getString("name"))
                    assertEquals("viewpoint", rs.getString("category"))
                    assertEquals("tourism=viewpoint", rs.getString("osmTag"))
                    assertEquals(false, rs.next())
                }
            }
        } finally {
            outputDb.delete()
        }
    }
}
