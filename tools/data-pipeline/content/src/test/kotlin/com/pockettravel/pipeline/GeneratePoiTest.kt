package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class GeneratePoiTest {

    private val poiTagKeys = listOf("amenity", "shop", "tourism", "leisure", "historic")

    @Test
    fun `estrae il POI taggato dall'estratto OSM di test`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".poi.db")
        outputDb.delete()

        try {
            val pois = readPois(listOf(File("testdata/tiny-region.osm.xml")), poiTagKeys)
            writePoiDb(pois, outputDb)

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery(
                        "SELECT poi.name, poi_code.category, poi_code.osmTag FROM poi JOIN poi_code ON poi.code = poi_code.code"
                    )
                    assertEquals(true, rs.next())
                    assertEquals("Punto panoramico di prova", rs.getString("name"))
                    assertEquals("viewpoint", rs.getString("category"))
                    assertEquals("tourism=viewpoint", rs.getString("osmTag"))
                    assertEquals(false, rs.next())
                }
                // Marcatore di formato letto da PoiImporter, vedi POI_DB_FORMAT_VERSION.
                val version = conn.createStatement().executeQuery("PRAGMA user_version")
                version.next()
                assertEquals(1, version.getInt(1))
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `la tabella dei codici ha una riga per ogni coppia category-osmTag distinta, niente colonna regionId`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".poi.db")
        outputDb.delete()

        try {
            val pois = readPois(listOf(File("testdata/tiny-region.osm.xml")), poiTagKeys)
            writePoiDb(pois + pois, outputDb) // stessi POI due volte: stesso code, non due righe in poi_code.

            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val codes = statement.executeQuery("SELECT COUNT(*) FROM poi_code")
                    codes.next()
                    assertEquals(1, codes.getInt(1))
                    val rows = statement.executeQuery("SELECT COUNT(*) FROM poi")
                    rows.next()
                    assertEquals(2, rows.getInt(1))
                    assertEquals(
                        false,
                        statement.executeQuery("PRAGMA table_info(poi)").let { info ->
                            var hasRegionId = false
                            while (info.next()) if (info.getString("name") == "regionId") hasRegionId = true
                            hasRegionId
                        },
                    )
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
    fun `orari e indirizzo solo per cibo e bevande`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        val xml = File(dir, "a.xml")
        xml.writeText(
            """<?xml version="1.0"?><osm version="0.6">""" +
                """<node id="1" lat="44.06" lon="12.57"><tag k="amenity" v="restaurant"/><tag k="name" v="Da Mario"/>""" +
                """<tag k="opening_hours" v="Mo-Sa 12:00-15:00,19:00-23:00; Su off"/><tag k="addr:street" v="Via Roma"/>""" +
                """<tag k="addr:housenumber" v="12"/><tag k="addr:city" v="Rimini"/><tag k="contact:website" v="https://damario.example"/>""" +
                """<tag k="email" v="info@damario.example"/></node>""" +
                """<node id="2" lat="44.07" lon="12.58"><tag k="amenity" v="cafe"/><tag k="name" v="Bar senza via"/><tag k="addr:city" v="Rimini"/></node>""" +
                """<node id="3" lat="44.08" lon="12.59"><tag k="shop" v="bakery"/><tag k="name" v="Forno"/><tag k="opening_hours" v="Mo-Sa 07:00-13:00"/>""" +
                """<tag k="addr:street" v="Via Po"/><tag k="website" v="https://forno.example"/></node>""" +
                "</osm>",
        )
        val outputDb = File(dir, "poi.db")
        try {
            val pois = readPois(listOf(xml), poiTagKeys)
            val (ristorante, bar, forno) = pois
            assertEquals("Mo-Sa 12:00-15:00,19:00-23:00; Su off", ristorante.openingHours)
            assertEquals("Via Roma 12, Rimini", ristorante.address)
            // Solo la citta', senza via: niente indirizzo.
            assertEquals(null, bar.address)
            // Un negozio non porta i dettagli, anche se OSM li ha.
            assertEquals(listOf(null, null), listOf(forno.openingHours, forno.address))

            writePoiDb(pois, outputDb)
            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                val rs = conn.createStatement().executeQuery("SELECT openingHours, address FROM poi WHERE name = 'Da Mario'")
                assertEquals(true, rs.next())
                assertEquals("Via Roma 12, Rimini", rs.getString("address"))
                assertEquals("Mo-Sa 12:00-15:00,19:00-23:00; Su off", rs.getString("openingHours"))
            }
        } finally {
            dir.deleteRecursively()
        }
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
