package com.pockettravel.pipeline

import com.pockettravel.core.poi.PoiPackage
import com.pockettravel.core.poi.poiPackageOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `orari e indirizzo per cibo, farmacie e negozi, non per i musei`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        val xml = File(dir, "a.xml")
        xml.writeText(
            """<?xml version="1.0"?><osm version="0.6">""" +
                """<node id="1" lat="44.06" lon="12.57"><tag k="amenity" v="restaurant"/><tag k="name" v="Da Mario"/>""" +
                """<tag k="opening_hours" v="Mo-Sa 12:00-15:00,19:00-23:00; Su off"/><tag k="addr:street" v="Via Roma"/>""" +
                """<tag k="addr:housenumber" v="12"/><tag k="addr:city" v="Rimini"/><tag k="contact:website" v="https://damario.example"/>""" +
                """<tag k="email" v="info@damario.example"/></node>""" +
                """<node id="2" lat="44.07" lon="12.58"><tag k="amenity" v="cafe"/><tag k="name" v="Bar senza via"/><tag k="addr:city" v="Rimini"/></node>""" +
                """<node id="3" lat="44.08" lon="12.59"><tag k="tourism" v="museum"/><tag k="name" v="Museo"/><tag k="opening_hours" v="Tu-Su 10:00-18:00"/>""" +
                """<tag k="addr:street" v="Via Po"/></node>""" +
                """<node id="4" lat="44.09" lon="12.60"><tag k="amenity" v="pharmacy"/><tag k="brand" v="Farmacia Comunale"/>""" +
                """<tag k="opening_hours" v="Mo-Fr 08:30-19:30"/><tag k="addr:street" v="Corso d'Augusto"/><tag k="addr:housenumber" v="5"/></node>""" +
                """<node id="5" lat="44.10" lon="12.61"><tag k="shop" v="supermarket"/><tag k="brand" v="Conad"/><tag k="name" v="Conad City"/>""" +
                """<tag k="opening_hours" v="Mo-Su 08:00-21:00"/></node>""" +
                """<node id="6" lat="44.11" lon="12.62"><tag k="tourism" v="attraction"/><tag k="brand" v="Marchio"/></node>""" +
                // Pensilina di una fermata: scartata; il bivacco resta.
                """<node id="7" lat="44.12" lon="12.63"><tag k="amenity" v="shelter"/><tag k="shelter_type" v="public_transport"/></node>""" +
                """<node id="8" lat="44.13" lon="12.64"><tag k="amenity" v="shelter"/><tag k="shelter_type" v="basic_hut"/></node>""" +
                "</osm>",
        )
        val outputDb = File(dir, "poi.db")
        try {
            val pois = readPois(listOf(xml), poiTagKeys)
            val (ristorante, bar, museo, farmacia, supermercato) = pois
            assertEquals("Mo-Sa 12:00-15:00,19:00-23:00; Su off", ristorante.openingHours)
            assertEquals("Via Roma 12, Rimini", ristorante.address)
            // Solo la citta', senza via: niente indirizzo.
            assertEquals(null, bar.address)
            // Un museo non porta i dettagli, anche se OSM li ha.
            assertEquals(listOf(null, null), listOf(museo.openingHours, museo.address))
            // Senza nome, il marchio.
            assertEquals(listOf("Farmacia Comunale", "Mo-Fr 08:30-19:30", "Corso d'Augusto 5"), listOf(farmacia.name, farmacia.openingHours, farmacia.address))
            assertEquals(listOf("Conad City", "Mo-Su 08:00-21:00"), listOf(supermercato.name, supermercato.openingHours))
            // Il marchio solo per le categorie con i dettagli.
            assertEquals("attraction", pois[5].name)
            assertEquals(listOf("amenity=shelter"), pois.drop(6).map { it.osmTag })

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
    fun `ambasciate con office=diplomatic, indirizzo, orari, sito ed email`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        val xml = File(dir, "a.xml")
        xml.writeText(
            """<?xml version="1.0"?><osm version="0.6">""" +
                """<node id="1" lat="41.9" lon="12.5"><tag k="office" v="diplomatic"/><tag k="diplomatic" v="consulate"/><tag k="country" v="it;sm"/>""" +
                """<tag k="name" v="Consolato di Prova"/><tag k="addr:street" v="Via Veneto"/><tag k="addr:housenumber" v="1"/>""" +
                """<tag k="opening_hours" v="Mo-Fr 09:00-12:00"/><tag k="contact:website" v="https://consolato.example"/>""" +
                """<tag k="email" v="info@consolato.example"/><tag k="phone" v="+39 06 1"/></node>""" +
                """<node id="2" lat="41.8" lon="12.4"><tag k="amenity" v="restaurant"/><tag k="name" v="Da Mario"/><tag k="website" v="https://damario.example"/></node>""" +
                """<node id="3" lat="41.7" lon="12.3"><tag k="tourism" v="hotel"/><tag k="name" v="Hotel Prova"/><tag k="addr:street" v="Via Po"/>""" +
                """<tag k="opening_hours" v="24/7"/><tag k="contact:website" v="https://hotel.example"/><tag k="contact:email" v="info@hotel.example"/></node>""" +
                "</osm>",
        )
        try {
            val (consolato, ristorante, hotel) = readPois(listOf(xml), poiTagKeys + "office")
            assertEquals("embassy", consolato.category)
            assertEquals("office=diplomatic", consolato.osmTag)
            assertEquals("Via Veneto 1", consolato.address)
            assertEquals("Mo-Fr 09:00-12:00", consolato.openingHours)
            assertEquals("https://consolato.example", consolato.website)
            assertEquals("info@consolato.example", consolato.email)
            assertEquals("IT", consolato.country)
            assertEquals(null, hotel.country)
            // Sito ed email per ambasciate e alloggi, non per i ristoranti.
            assertEquals(null, ristorante.website)
            assertEquals(listOf("Via Po", "24/7", "https://hotel.example", "info@hotel.example"), listOf(hotel.address, hotel.openingHours, hotel.website, hotel.email))
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

    @Test
    fun `ponti, piazze, complessi religiosi e parchi nazionali dalle loro chiavi, aree protette di classe 2 come parchi nazionali`() {
        val xml = File.createTempFile("pocket-travel-test", ".osm.xml")
        try {
            xml.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6">
                  <way id="1"><center lat="41.04" lon="29.03"/><tag k="man_made" v="bridge"/><tag k="name" v="15 Temmuz Şehitler Köprüsü"/><tag k="wikidata" v="Q4484"/></way>
                  <relation id="2"><center lat="35.40" lon="138.80"/><tag k="boundary" v="protected_area"/><tag k="protect_class" v="2"/><tag k="name" v="富士箱根伊豆国立公園"/></relation>
                  <relation id="3"><center lat="35.40" lon="138.90"/><tag k="boundary" v="protected_area"/><tag k="protect_class" v="5"/><tag k="name" v="Paesaggio"/></relation>
                  <node id="4" lat="48.85" lon="2.36"><tag k="place" v="square"/><tag k="name" v="Place des Vosges"/><tag k="wikidata" v="Q898629"/></node>
                  <relation id="5"><center lat="35.69" lon="139.74"/><tag k="landuse" v="religious"/><tag k="name" v="靖国神社"/><tag k="wikidata" v="Q242803"/></relation>
                  <way id="6"><center lat="45.43" lon="12.33"/><tag k="tourism" v="attraction"/><tag k="man_made" v="bridge"/><tag k="name" v="Ponte di Rialto"/></way>
                </osm>
                """.trimIndent(),
            )
            val pois = readPois(listOf(xml), poiTagKeys + listOf("railway", "aeroway", "office", "man_made", "landuse", "boundary", "place"))

            // Un'attrazione resta un'attrazione anche se e' un ponte: le chiavi nuove vengono dopo quelle di sempre.
            assertEquals(
                listOf("man_made=bridge", "boundary=national_park", "boundary=protected_area", "place=square", "landuse=religious", "tourism=attraction"),
                pois.map { it.osmTag },
            )
        } finally {
            xml.delete()
        }
    }

    @Test
    fun `nomi in inglese e italiano solo se diversi dal nome locale`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        val xml = File(dir, "a.xml")
        xml.writeText(
            """<?xml version="1.0"?><osm version="0.6">""" +
                """<node id="1" lat="34.99" lon="135.78"><tag k="amenity" v="place_of_worship"/><tag k="name" v="清水寺"/>""" +
                """<tag k="name:en" v="Kiyomizu-dera"/><tag k="name:it" v="Kiyomizu-dera"/></node>""" +
                """<node id="2" lat="43.93" lon="12.44"><tag k="amenity" v="restaurant"/><tag k="name" v="Da Mario"/><tag k="name:en" v="Da Mario"/></node>""" +
                "</osm>",
        )
        try {
            val (tempio, ristorante) = readPois(listOf(xml), poiTagKeys)
            assertEquals(listOf("Kiyomizu-dera", "Kiyomizu-dera"), listOf(tempio.nameEn, tempio.nameIt))
            assertEquals(listOf(null, null), listOf(ristorante.nameEn, ristorante.nameIt))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `bagni accessibili e posti per disabili finiscono in colonne facoltative di poi db`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        val xml = File(dir, "a.xml")
        val outputDb = File(dir, "poi.db")
        xml.writeText(
            """<?xml version="1.0"?><osm version="0.6">""" +
                """<node id="1" lat="43.93" lon="12.44"><tag k="amenity" v="restaurant"/><tag k="name" v="Da Mario"/><tag k="toilets:wheelchair" v="yes"/></node>""" +
                """<node id="2" lat="43.93" lon="12.45"><tag k="amenity" v="parking"/><tag k="name" v="P1"/><tag k="capacity:disabled" v="3"/></node>""" +
                """<node id="3" lat="43.93" lon="12.46"><tag k="amenity" v="parking"/><tag k="name" v="P2"/><tag k="capacity:disabled" v="yes"/></node>""" +
                """<node id="4" lat="43.93" lon="12.47"><tag k="amenity" v="cafe"/><tag k="name" v="Bar"/></node>""" +
                "</osm>",
        )
        try {
            val pois = readPois(listOf(xml), poiTagKeys)
            assertEquals(listOf("yes", null, null, null), pois.map { it.toiletsWheelchair })
            // "yes" non e' un numero: posti per disabili ignoti, non zero.
            assertEquals(listOf(null, 3, null, null), pois.map { it.capacityDisabled })
            writePoiDb(pois, outputDb)
            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().use { statement ->
                    val rs = statement.executeQuery("SELECT name, toiletsWheelchair, capacityDisabled FROM poi ORDER BY name")
                    val rows = generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getObject(3)) else null }.toList()
                    assertEquals(
                        listOf(Triple("Bar", null, null), Triple("Da Mario", "yes", null), Triple("P1", null, 3), Triple("P2", null, null)),
                        rows,
                    )
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stalli per disabili da parking_space=disabled o capacity disabled, gli altri stalli restano generici`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi").toFile()
        val xml = File(dir, "a.xml")
        xml.writeText(
            """<?xml version="1.0"?><osm version="0.6">""" +
                """<node id="1" lat="43.93" lon="12.44"><tag k="amenity" v="parking_space"/><tag k="parking_space" v="disabled"/></node>""" +
                """<node id="2" lat="43.93" lon="12.45"><tag k="amenity" v="parking_space"/><tag k="capacity:disabled" v="2"/></node>""" +
                """<node id="3" lat="43.93" lon="12.46"><tag k="amenity" v="parking_space"/><tag k="capacity:disabled" v="0"/></node>""" +
                "</osm>",
        )
        try {
            val categories = readPois(listOf(xml), poiTagKeys).map { it.category }
            assertEquals(listOf("parking_disabled", "parking_disabled", "parking_space"), categories)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `un poi base vuoto o sotto la meta' di quello pubblicato viene rifiutato`() {
        // Prima pubblicazione: basta che ci sia qualcosa.
        checkPoiCount(1, null)
        checkPoiCount(100, 200)
        checkPoiCount(500, 200)
        assertThrows(IllegalStateException::class.java) { checkPoiCount(0, null) }
        assertThrows(IllegalStateException::class.java) { checkPoiCount(0, 200) }
        assertThrows(IllegalStateException::class.java) { checkPoiCount(99, 200) }
    }

    // --- readPois: casi limite ---

    private fun readOsm(body: String, keys: List<String> = poiTagKeys): List<Poi> {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi-extra").toFile()
        try {
            val xml = File(dir, "a.xml").apply { writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?><osm version=\"0.6\">$body</osm>") }
            return readPois(listOf(xml), keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun node(id: Int, tags: Map<String, String>, lat: String = "44.0", lon: String = "12.0") =
        """<node id="$id" lat="$lat" lon="$lon">${tags.entries.joinToString("") { """<tag k="${it.key}" v="${it.value}"/>""" }}</node>"""

    private fun rowsOf(db: File, sql: String): List<List<String?>> =
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            conn.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val columns = rs.metaData.columnCount
                    generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
                }
            }
        }

    @Test
    fun `un estratto vuoto non da' POI e i tag fuori da un elemento si ignorano`() {
        assertEquals(emptyList<Poi>(), readOsm(""))
        assertEquals(emptyList<Poi>(), readOsm("""<tag k="amenity" v="cafe"/>"""))
    }

    @Test
    fun `un estratto con DOCTYPE, malformato o vuoto fa fallire la lettura`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi-bad").toFile()
        try {
            val doctype = File(dir, "doctype.xml").apply {
                writeText("""<?xml version="1.0"?><!DOCTYPE osm [<!ENTITY x "y">]><osm version="0.6"><node id="1" lat="1" lon="1"><tag k="amenity" v="&x;"/></node></osm>""")
            }
            val broken = File(dir, "rotto.xml").apply { writeText("""<?xml version="1.0"?><osm version="0.6"><node id="1" lat="1" """) }
            val empty = File(dir, "vuoto.xml").apply { writeText("") }

            listOf(doctype, broken, empty).forEach { file ->
                assertThrows(org.xml.sax.SAXException::class.java) { readPois(listOf(file), poiTagKeys) }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `elementi senza coordinate e tag non POI si saltano, i tag non restano da un elemento al successivo`() {
        val pois = readOsm(
            """<node id="1"><tag k="amenity" v="cafe"/></node>""" +
                node(2, mapOf("amenity" to "cafe", "name" to "Bar Uno")) +
                node(3, mapOf("shop" to "bakery")) +
                node(4, mapOf("highway" to "bus_stop", "name" to "Fermata")),
        )

        assertEquals(listOf("Bar Uno", "bakery"), pois.map { it.name })
        assertEquals(listOf("amenity=cafe", "shop=bakery"), pois.map { it.osmTag })
    }

    @Test
    fun `tra le chiavi POI vince la prima dell'elenco presente nell'elemento`() {
        val both = node(1, mapOf("amenity" to "cafe", "shop" to "bakery"))

        assertEquals(listOf("amenity=cafe"), readOsm(both, listOf("amenity", "shop")).map { it.osmTag })
        assertEquals(listOf("shop=bakery"), readOsm(both, listOf("shop", "amenity")).map { it.osmTag })
        assertEquals(emptyList<Poi>(), readOsm(both, listOf("historic")))
        assertEquals(emptyList<Poi>(), readOsm(both, emptyList()))
    }

    @Test
    fun `una chiave prima che non da' un POI pubblicato lascia il posto alla successiva`() {
        val keys = listOf("amenity", "shop", "tourism", "leisure", "historic", "boundary", "place")
        // New York Botanical Garden e Parco della pace di Hiroshima: tourism=yes accanto a leisure=garden/park.
        val giardino = node(1, mapOf("tourism" to "yes", "leisure" to "garden", "name" to "New York Botanical Garden"))
        // Piazza dei Miracoli: area protetta di classe 98 (non un parco nazionale) accanto a place=square.
        val piazza = node(2, mapOf("boundary" to "protected_area", "protect_class" to "98", "place" to "square", "name" to "Piazza dei Miracoli"))
        // Nessuna chiave da' un POI pubblicato: resta la prima, come prima (non pubblicata).
        val niente = node(3, mapOf("tourism" to "yes", "boundary" to "protected_area", "name" to "Area"))

        assertEquals(
            listOf("leisure=garden", "place=square", "tourism=yes"),
            readOsm(giardino + piazza + niente, keys).map { it.osmTag },
        )
    }

    @Test
    fun `piazze pedonali, vie di piazze, siti, cime e catene montuose con le chiavi nuove`() {
        val keys = poiTagKeys + listOf("railway", "aeroway", "office", "man_made", "landuse", "boundary", "place", "highway", "natural", "type")
        val bastille = node(1, mapOf("highway" to "pedestrian", "type" to "multipolygon", "name" to "Place de la Bastille"))
        val vosges = node(2, mapOf("type" to "associatedStreet", "name" to "Place des Vosges"))
        val nikko = node(3, mapOf("type" to "site", "name" to "Santuari e templi di Nikko"))
        val olimpo = node(4, mapOf("natural" to "mountain_range", "name" to "Olimpo"))
        val cima = node(5, mapOf("natural" to "peak", "name" to "Fuji"))
        // Una cima con un belvedere resta un belvedere.
        val belvedere = node(6, mapOf("tourism" to "viewpoint", "natural" to "peak", "name" to "Monte Baldo"))

        val pois = readOsm(bastille + vosges + nikko + olimpo + cima + belvedere, keys)

        assertEquals(
            listOf(
                "highway=pedestrian", "type=associatedStreet", "type=site", "natural=mountain_range", "natural=peak",
                "tourism=viewpoint",
            ),
            pois.map { it.osmTag },
        )
        assertEquals(
            List(6) { true },
            pois.map { poiPackageOf(it.name, it.category, it.osmTag) == PoiPackage.BASE },
        )
    }

    @Test
    fun `nome, entita' XML e caratteri unicode si conservano`() {
        val pois = readOsm(
            """<node id="1" lat="35.0" lon="135.0"><tag k="amenity" v="restaurant"/><tag k="name" v="Pane &amp; Vino &#x1F355; &#26481;&#20140; &quot;Il Gatto&quot;"/></node>""",
        )

        assertEquals("Pane & Vino 🍕 東京 \"Il Gatto\"", pois.single().name)
    }

    @Test
    fun `senza nome si usa il tipo, e il marchio solo per le categorie con i dettagli`() {
        val pois = readOsm(
            node(1, mapOf("amenity" to "cafe")) +
                node(2, mapOf("tourism" to "museum", "brand" to "Marchio")) +
                node(3, mapOf("amenity" to "pharmacy", "brand" to "Lloyds")),
        )

        assertEquals(listOf("cafe", "museum", "Lloyds"), pois.map { it.name })
    }

    @Test
    fun `il telefono storico vince su contact_phone e il resto dei campi facoltativi resta nullo`() {
        val both = readOsm(node(1, mapOf("amenity" to "cafe", "phone" to "+39 1", "contact:phone" to "+39 2"))).single()
        val onlyContact = readOsm(node(1, mapOf("amenity" to "cafe", "contact:phone" to "+39 2"))).single()
        val none = readOsm(node(1, mapOf("amenity" to "cafe"))).single()

        assertEquals("+39 1", both.phone)
        assertEquals("+39 2", onlyContact.phone)
        assertEquals(null, none.phone)
        assertEquals(listOf(null, null, null, null, null, null), listOf(none.wheelchair, none.website, none.email, none.country, none.nameEn, none.toiletsWheelchair))
    }

    @Test
    fun `i nomi tradotti si tagliano, non esistono senza nome locale e non sono mai vuoti`() {
        val pois = readOsm(
            node(1, mapOf("amenity" to "cafe", "name" to "Locale", "name:en" to "  Local  ", "name:it" to "")) +
                node(2, mapOf("amenity" to "cafe", "name:en" to "Solo inglese")) +
                node(3, mapOf("amenity" to "cafe", "name" to "Uguale", "name:it" to "Uguale")),
        )

        assertEquals(listOf("Local", null, null), pois.map { it.nameEn })
        assertEquals(listOf(null, null, null), pois.map { it.nameIt })
    }

    @Test
    fun `il paese di un'ambasciata e' un codice a due lettere maiuscolo, il primo se ce ne sono piu'`() {
        fun countryOf(value: String?): String? {
            val tags = mutableMapOf("office" to "diplomatic", "name" to "Rappresentanza")
            if (value != null) tags["country"] = value
            return readOsm(node(1, tags), poiTagKeys + "office").single().country
        }

        assertEquals("IT", countryOf("it"))
        assertEquals("IT", countryOf(" it ; fr"))
        assertEquals(null, countryOf("ITA"))
        assertEquals(null, countryOf("I1"))
        assertEquals(null, countryOf(""))
        assertEquals(null, countryOf(null))
        // Un ristorante con tag country non ha paese.
        assertEquals(null, readOsm(node(1, mapOf("amenity" to "restaurant", "country" to "IT"))).single().country)
    }

    @Test
    fun `posti per disabili - numeri positivi con spazi si, zero negativi e testo no`() {
        fun capacityOf(value: String): Int? = readOsm(node(1, mapOf("amenity" to "parking", "capacity:disabled" to value))).single().capacityDisabled

        assertEquals(5, capacityOf(" 5 "))
        assertEquals(null, capacityOf("0"))
        assertEquals(null, capacityOf("-2"))
        assertEquals(null, capacityOf("abc"))
        assertEquals(null, capacityOf("2.5"))
    }

    @Test
    fun `parcheggi - solo gli accessi riservati sono privati`() {
        fun categoryOf(access: String?): String {
            val tags = mutableMapOf("amenity" to "parking")
            if (access != null) tags["access"] = access
            return readOsm(node(1, tags)).single().category
        }

        listOf("private", "customers", "no", "permit", "residents").forEach { assertEquals(it, "parking_private", categoryOf(it)) }
        listOf("yes", "destination", "public", null).forEach { assertEquals(it, "parking", categoryOf(it)) }
    }

    @Test
    fun `stazioni della metro da station=subway o subway=yes, uffici informazioni e giardini senza nome`() {
        val keys = listOf("amenity", "tourism", "leisure", "railway")
        val pois = readOsm(
            node(1, mapOf("railway" to "station", "subway" to "yes")) +
                node(2, mapOf("railway" to "tram_stop", "station" to "subway")) +
                node(3, mapOf("railway" to "station")) +
                node(4, mapOf("tourism" to "information", "information" to "visitor_centre")) +
                node(5, mapOf("tourism" to "information", "information" to "guidepost")) +
                node(6, mapOf("tourism" to "information")) +
                node(7, mapOf("leisure" to "garden")) +
                node(8, mapOf("leisure" to "park", "name" to "Parco")),
            keys,
        )

        assertEquals(
            listOf("subway_station", "subway_station", "station", "information_office", "information", "information", "garden", "park"),
            pois.map { it.category },
        )
    }

    @Test
    fun `un'area protetta di classe 2 diventa parco nazionale, le altre classi restano aree protette`() {
        val pois = readOsm(
            node(1, mapOf("boundary" to "protected_area", "protect_class" to "2", "name" to "Parco")) +
                node(2, mapOf("boundary" to "protected_area", "protect_class" to "4", "name" to "Riserva")) +
                node(3, mapOf("boundary" to "protected_area", "name" to "Senza classe")) +
                node(4, mapOf("boundary" to "national_park", "name" to "Gia' parco")),
            listOf("boundary"),
        )

        assertEquals(
            listOf("boundary=national_park", "boundary=protected_area", "boundary=protected_area", "boundary=national_park"),
            pois.map { it.osmTag },
        )
    }

    @Test
    fun `ways e relazioni ripetuti in due chunk contano una volta, nodi con lo stesso id di una way no`() {
        val dir = kotlin.io.path.createTempDirectory("pocket-travel-poi-chunks").toFile()
        try {
            val way = """<way id="5"><center lat="44.0" lon="12.0"/><tag k="amenity" v="cafe"/></way>"""
            val relation = """<relation id="5"><center lat="44.0" lon="12.0"/><tag k="amenity" v="cafe"/></relation>"""
            val a = File(dir, "a.xml").apply { writeText("""<?xml version="1.0"?><osm>$way${node(5, mapOf("amenity" to "cafe"))}</osm>""") }
            val b = File(dir, "b.xml").apply { writeText("""<?xml version="1.0"?><osm>$way$relation</osm>""") }

            assertEquals(3, readPois(listOf(a, b), poiTagKeys).size)
            assertEquals(emptyList<Poi>(), readPois(emptyList(), poiTagKeys))
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- checkPoiCount: soglie ---

    @Test
    fun `checkPoiCount - la soglia e' la meta' esatta dei pubblicati e il messaggio dice i numeri`() {
        checkPoiCount(2, 4)
        checkPoiCount(2, 3)
        checkPoiCount(1, 0)
        checkPoiCount(1, null)
        val error = assertThrows(IllegalStateException::class.java) { checkPoiCount(1, 3) }
        assertEquals(true, error.message.orEmpty().let { "1 POI base contro 3" in it && "50%" in it })
        val empty = assertThrows(IllegalStateException::class.java) { checkPoiCount(0, null) }
        assertEquals(true, empty.message.orEmpty().contains("nessun POI base"))
    }

    // --- writePoiDb: schema e contenuto ---

    private fun poi(
        name: String = "Bar",
        category: String = "cafe",
        osmTag: String = "amenity=cafe",
        lat: Double = 44.0,
        lon: Double = 12.0,
        capacityDisabled: Int? = null,
    ) = Poi(name, category, lat, lon, osmTag, phone = null, capacityDisabled = capacityDisabled)

    private fun withDb(block: (File) -> Unit) {
        val db = File.createTempFile("pocket-travel-test", ".poi.db").also { it.delete() }
        try {
            block(db)
        } finally {
            db.delete()
        }
    }

    @Test
    fun `lo schema di poi e poi_code ha le colonne attese`() {
        withDb { db ->
            writePoiDb(listOf(poi()), db)

            assertEquals(
                listOf(
                    "name", "code", "latE6", "lonE6", "phone", "wheelchair", "openingHours", "address", "website", "email",
                    "country", "nameEn", "nameIt", "toiletsWheelchair", "capacityDisabled",
                ),
                rowsOf(db, "SELECT name FROM pragma_table_info('poi')").map { it[0] },
            )
            assertEquals(listOf("code", "category", "osmTag"), rowsOf(db, "SELECT name FROM pragma_table_info('poi_code')").map { it[0] })
            assertEquals(listOf(listOf<String?>(POI_DB_FORMAT_VERSION.toString())), rowsOf(db, "PRAGMA user_version"))
        }
    }

    @Test
    fun `i codici seguono l'ordine di prima comparsa e distinguono osmTag diversi della stessa categoria`() {
        withDb { db ->
            writePoiDb(
                listOf(
                    poi(name = "a"),
                    poi(name = "b", category = "embassy", osmTag = "amenity=embassy"),
                    poi(name = "c", category = "embassy", osmTag = "office=diplomatic"),
                    poi(name = "d"),
                ),
                db,
            )

            assertEquals(
                listOf(listOf<String?>("0", "cafe", "amenity=cafe"), listOf("1", "embassy", "amenity=embassy"), listOf("2", "embassy", "office=diplomatic")),
                rowsOf(db, "SELECT code, category, osmTag FROM poi_code ORDER BY code"),
            )
            assertEquals(listOf("0", "1", "2", "0"), rowsOf(db, "SELECT code FROM poi ORDER BY rowid").map { it[0] })
        }
    }

    @Test
    fun `le coordinate si salvano in microgradi interi, anche negative e ai limiti`() {
        withDb { db ->
            writePoiDb(
                listOf(
                    poi(name = "sydney", lat = -33.8688, lon = 151.2093),
                    poi(name = "giu", lat = 44.0612344, lon = 12.0),
                    poi(name = "su", lat = 44.0612346, lon = -180.0),
                    poi(name = "polo", lat = 90.0, lon = 180.0),
                ),
                db,
            )

            assertEquals(
                listOf(
                    listOf<String?>("sydney", "-33868800", "151209300"),
                    listOf("giu", "44061234", "12000000"),
                    listOf("su", "44061235", "-180000000"),
                    listOf("polo", "90000000", "180000000"),
                ),
                rowsOf(db, "SELECT name, latE6, lonE6 FROM poi ORDER BY rowid"),
            )
            assertEquals(listOf("integer"), rowsOf(db, "SELECT DISTINCT typeof(latE6) FROM poi").map { it[0] })
        }
    }

    @Test
    fun `senza POI le tabelle esistono vuote e il marcatore di formato c'e' comunque`() {
        withDb { db ->
            writePoiDb(emptyList(), db)

            assertEquals(listOf(listOf<String?>("0", "0")), rowsOf(db, "SELECT (SELECT COUNT(*) FROM poi), (SELECT COUNT(*) FROM poi_code)"))
            assertEquals(listOf(listOf<String?>("1")), rowsOf(db, "PRAGMA user_version"))
        }
    }

    @Test
    fun `riscrivere poi db sullo stesso file sostituisce il contenuto`() {
        withDb { db ->
            writePoiDb(listOf(poi(name = "vecchio"), poi(name = "vecchio 2")), db)
            writePoiDb(listOf(poi(name = "nuovo", category = "bakery", osmTag = "shop=bakery")), db)

            assertEquals(listOf("nuovo"), rowsOf(db, "SELECT name FROM poi").map { it[0] })
            assertEquals(listOf("bakery"), rowsOf(db, "SELECT category FROM poi_code").map { it[0] })
        }
    }

    @Test
    fun `nomi con apici, SQL e unicode si salvano alla lettera e i campi assenti sono NULL`() {
        withDb { db ->
            val tricky = "Robert'); DROP TABLE poi;-- 東京 🍕"
            writePoiDb(listOf(poi(name = tricky, capacityDisabled = 4), poi(name = "")), db)

            assertEquals(listOf(tricky, ""), rowsOf(db, "SELECT name FROM poi ORDER BY rowid").map { it[0] })
            assertEquals(listOf(listOf<String?>("4", null), listOf(null, null)), rowsOf(db, "SELECT capacityDisabled, phone FROM poi ORDER BY rowid"))
            assertEquals(listOf("integer", "null"), rowsOf(db, "SELECT typeof(capacityDisabled) FROM poi ORDER BY rowid").map { it[0] })
        }
    }
}
