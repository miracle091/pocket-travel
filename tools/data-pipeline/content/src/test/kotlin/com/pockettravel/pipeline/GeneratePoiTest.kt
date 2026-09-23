package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratePoiTest {

    private val poiTagKeys = listOf("amenity", "shop", "tourism", "leisure", "historic")

    @Test
    fun `estrae il POI taggato dall'estratto OSM di test`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".poi.db")
        outputDb.delete()

        try {
            val pois = readPois(listOf(File("testdata/tiny-region.osm.xml")), poiTagKeys)
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

    @Test
    fun `un nodo presente in due chunk conta una volta e i nodi senza tag POI sono ignorati`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        fun chunk(name: String, body: String) = File(dir, name).apply { writeText("<?xml version=\"1.0\"?><osm version=\"0.6\">$body</osm>") }
        val confine = """<node id="1" lat="47.0" lon="10.0"><tag k="amenity" v="cafe"/><tag k="name" v="Bar al confine"/><tag k="contact:phone" v="+43 1"/></node>"""
        val files = listOf(
            chunk("a.xml", confine + """<node id="2" lat="46.0" lon="9.0"><tag k="highway" v="bus_stop"/></node>"""),
            chunk("b.xml", confine + """<node id="3" lat="48.0" lon="11.0"><tag k="shop" v="bakery"/></node>"""),
        )

        val pois = readPois(files, poiTagKeys)

        assertEquals(listOf("Bar al confine", "bakery"), pois.map { it.name })
        assertEquals("+43 1", pois.first().phone)
        assertEquals("shop=bakery", pois.last().osmTag)
        dir.deleteRecursively()
    }
}
