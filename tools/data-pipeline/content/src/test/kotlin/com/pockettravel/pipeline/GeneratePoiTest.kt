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
        val confine = """<node id="1" lat="47.0" lon="10.0"><tag k="amenity" v="cafe"/><tag k="name" v="Bar al confine"/><tag k="contact:phone" v="+43 1"/><tag k="wheelchair" v="limited"/></node>"""
        val files = listOf(
            chunk("a.xml", confine + """<node id="2" lat="46.0" lon="9.0"><tag k="highway" v="bus_stop"/></node>"""),
            chunk("b.xml", confine + """<node id="3" lat="48.0" lon="11.0"><tag k="shop" v="bakery"/></node>"""),
        )

        val pois = readPois(files, poiTagKeys)

        assertEquals(listOf("Bar al confine", "bakery"), pois.map { it.name })
        assertEquals("+43 1", pois.first().phone)
        assertEquals("limited", pois.first().wheelchair)
        assertEquals(null, pois.last().wheelchair)
        assertEquals("shop=bakery", pois.last().osmTag)
        dir.deleteRecursively()
    }

    @Test
    fun `aree e relazioni col loro centro, parcheggi privati, metro, uffici informazioni e parchi`() {
        val xml = File.createTempFile("pocket-travel-test", ".osm.xml")
        try {
            xml.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6">
                  <way id="1"><center lat="44.05" lon="12.55"/><tag k="amenity" v="parking"/><tag k="access" v="private"/></way>
                  <way id="2"><center lat="44.06" lon="12.56"/><tag k="amenity" v="parking"/><tag k="access" v="yes"/></way>
                  <node id="1" lat="45.46" lon="9.18"><tag k="railway" v="station"/><tag k="station" v="subway"/><tag k="name" v="Duomo"/></node>
                  <node id="2" lat="44.06" lon="12.57"><tag k="tourism" v="information"/><tag k="information" v="office"/></node>
                  <node id="3" lat="44.06" lon="12.58"><tag k="tourism" v="information"/><tag k="information" v="board"/></node>
                  <way id="4"><center lat="44.07" lon="12.57"/><tag k="leisure" v="park"/><tag k="name" v="Parco Marecchia"/></way>
                  <way id="5"><center lat="44.07" lon="12.58"/><tag k="leisure" v="park"/></way>
                  <relation id="1"><center lat="44.02" lon="12.61"/><tag k="aeroway" v="aerodrome"/><tag k="iata" v="RMI"/></relation>
                  <way id="3"><tag k="amenity" v="parking"/></way>
                </osm>
                """.trimIndent(),
            )
            val pois = readPois(listOf(xml), listOf("amenity", "tourism", "leisure", "railway", "aeroway"))

            // Way 5: parco senza nome, scartato. Way 3 senza <center>: niente coordinate, scartata. Way 1 e nodo 1 hanno lo stesso id ma
            // tipi diversi: entrambi presenti.
            assertEquals(
                listOf("parking_private", "parking", "subway_station", "information_office", "information", "park", "aerodrome"),
                pois.map { it.category },
            )
            assertEquals(44.02, pois.last().lat, 1e-9)
            assertEquals("aeroway=aerodrome", pois.last().osmTag)
        } finally {
            xml.delete()
        }
    }
}
