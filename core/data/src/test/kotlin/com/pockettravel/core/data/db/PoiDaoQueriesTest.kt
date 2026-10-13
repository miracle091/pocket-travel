package com.pockettravel.core.data.db

import com.pockettravel.core.data.accentInsensitiveGlob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Le query di PoiDao e PoiCodeDao che non hanno una costante (le altre le prova PoiRepositoryBoundsTest), eseguite su sqlite-jdbc
 * sullo schema Room esportato: il testo SQL e' quello degli @Query (vedi [queriesOf]), con i valori sempre legati.
 */
class PoiDaoQueriesTest {
    private lateinit var connection: Connection
    private val poiQueries = queriesOf("PoiDao.kt")
    private val codeQueries = queriesOf("PoiCodeDao.kt")

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createRoomSchema(currentRoomVersion(), connection)
    }

    @After
    fun tearDown() = connection.close()

    private fun exec(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun addPoi(
        regionId: String, name: String, category: String, osmTag: String, lat: Double, lon: Double, extra: Boolean = false,
        country: String? = null, nameIt: String? = null, nameEn: String? = null,
    ) {
        exec("INSERT OR IGNORE INTO poi_region(regionId) VALUES (?)", regionId)
        exec("INSERT OR IGNORE INTO poi_code(category, osmTag) VALUES (?, ?)", category, osmTag)
        exec(
            "INSERT INTO poi(regionRef, code, latE6, lonE6, extra, name, country, nameIt, nameEn) VALUES (" +
                "(SELECT ref FROM poi_region WHERE regionId = ?), (SELECT code FROM poi_code WHERE category = ? AND osmTag = ?), ?, ?, ?, ?, ?, ?, ?)",
            regionId, category, osmTag, Math.round(lat * 1_000_000), Math.round(lon * 1_000_000), if (extra) 1 else 0, name, country, nameIt, nameEn,
        )
    }

    private fun query(name: String, params: Map<String, Any?>): List<PoiEntity> =
        runNamed(connection, poiQueries.getValue(name), params, ::entity)

    private fun entity(rs: ResultSet) = PoiEntity(
        id = rs.getLong("id"), regionId = rs.getString("regionId"), name = rs.getString("name"), category = rs.getString("category"),
        lat = rs.getDouble("lat"), lon = rs.getDouble("lon"), osmTag = rs.getString("osmTag"), phone = rs.getString("phone"),
        wheelchair = rs.getString("wheelchair"), openingHours = rs.getString("openingHours"), address = rs.getString("address"),
        website = rs.getString("website"), email = rs.getString("email"), country = rs.getString("country"), nameEn = rs.getString("nameEn"),
        nameIt = rs.getString("nameIt"), toiletsWheelchair = rs.getString("toiletsWheelchair"),
        capacityDisabled = rs.getInt("capacityDisabled").takeUnless { rs.wasNull() }, extra = rs.getInt("extra") == 1,
    )

    private fun count(table: String) = connection.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getLong(1) } }

    @Test
    fun `tutte le query degli @Query sono state lette dal sorgente`() {
        assertEquals(
            setOf(
                "poisForRegion", "poisInBounds", "spreadInBounds", "spreadInWideBounds", "categoryTagsInRegion", "transportCounts", "embassiesOf",
                "searchByName", "nearest", "deleteForRegion",
            ),
            poiQueries.keys,
        )
        assertEquals(setOf("ensureRegion", "regionRef", "ensureCode", "codeOf"), codeQueries.keys)
        assertTrue(poiQueries.values.none { "\$" in it })
    }

    @Test
    fun `poisForRegion rende i campi con le coordinate in gradi e solo la regione chiesta`() {
        addPoi("roma", "Museo", "museum", "tourism=museum", 41.9, 12.5, country = "IT", nameIt = "Museo", nameEn = "Museum")
        addPoi("milano", "Duomo", "attraction", "tourism=attraction", 45.46, 9.19, extra = true)

        val rome = query("poisForRegion", mapOf("regionId" to "roma")).single()
        assertEquals("roma", rome.regionId)
        assertEquals(41.9, rome.lat, 1e-9)
        assertEquals(12.5, rome.lon, 1e-9)
        assertEquals("tourism=museum", rome.osmTag)
        assertEquals("IT", rome.country)
        assertEquals("Museum", rome.nameEn)
        assertEquals(false, rome.extra)
        assertEquals(true, query("poisForRegion", mapOf("regionId" to "milano")).single().extra)
        assertEquals(emptyList<PoiEntity>(), query("poisForRegion", mapOf("regionId" to "napoli")))
    }

    @Test
    fun `embassiesOf tiene solo le ambasciate del paese nella regione e le ordina per nome`() {
        addPoi("francia", "Ambassade d'Italie", "embassy", "amenity=embassy", 48.8, 2.3, country = "IT")
        addPoi("francia", "Ambasciata dell'Italia", "embassy", "amenity=embassy", 43.7, 7.2, country = "IT")
        addPoi("francia", "Émbassade", "embassy", "amenity=embassy", 43.7, 7.2, country = "IT")
        addPoi("francia", "Ambassade de Suisse", "embassy", "amenity=embassy", 48.8, 2.3, country = "CH")
        // Stesso paese ma non un'ambasciata, e un'ambasciata italiana in un'altra regione.
        addPoi("francia", "Ristorante Italia", "restaurant", "amenity=restaurant", 48.8, 2.3, country = "IT")
        addPoi("belgio", "Ambassade d'Italie", "embassy", "amenity=embassy", 50.8, 4.3, country = "IT")

        val result = query("embassiesOf", mapOf("regionId" to "francia", "country" to "IT"))

        // Ordine binario di SQLite: la maiuscola accentata viene dopo le lettere ASCII.
        assertEquals(listOf("Ambasciata dell'Italia", "Ambassade d'Italie", "Émbassade"), result.map { it.name })
        assertEquals(emptyList<PoiEntity>(), query("embassiesOf", mapOf("regionId" to "francia", "country" to "it")))
        assertEquals(emptyList<PoiEntity>(), query("embassiesOf", mapOf("regionId" to "francia", "country" to "")))
    }

    @Test
    fun `embassiesOf con un valore da SQL injection e' solo un paese che non esiste`() {
        addPoi("francia", "Ambassade", "embassy", "amenity=embassy", 48.8, 2.3, country = "IT")
        val result = query("embassiesOf", mapOf("regionId" to "francia", "country" to "IT' OR '1'='1"))
        assertEquals(emptyList<PoiEntity>(), result)
        assertEquals(1, count("poi"))
    }

    private fun transport(regionId: String) = runNamed(connection, poiQueries.getValue("transportCounts"), mapOf("regionId" to regionId)) {
        Triple(it.getString("osmTag"), it.getString("category"), it.getInt("count"))
    }

    @Test
    fun `transportCounts conta per tag e categoria solo i trasporti della regione`() {
        repeat(3) { addPoi("italia", "Stazione $it", "station", "railway=station", 45.0 + it, 9.0) }
        addPoi("italia", "Fermata", "station", "railway=halt", 45.0, 9.0)
        repeat(2) { addPoi("italia", "Metro $it", "subway_station", "railway=station", 45.0, 9.0 + it) }
        addPoi("italia", "Autostazione", "bus_station", "amenity=bus_station", 45.0, 9.0)
        addPoi("italia", "Porto", "ferry_terminal", "amenity=ferry_terminal", 45.0, 9.0)
        addPoi("italia", "Aeroporto", "aerodrome", "aeroway=aerodrome", 45.0, 9.0)
        addPoi("italia", "Bar", "cafe", "amenity=cafe", 45.0, 9.0)
        addPoi("francia", "Gare", "station", "railway=station", 48.0, 2.0)

        assertEquals(
            setOf(
                Triple("railway=station", "station", 3),
                Triple("railway=halt", "station", 1),
                Triple("railway=station", "subway_station", 2),
                Triple("amenity=bus_station", "bus_station", 1),
                Triple("amenity=ferry_terminal", "ferry_terminal", 1),
                Triple("aeroway=aerodrome", "aerodrome", 1),
            ),
            transport("italia").toSet(),
        )
        assertEquals(emptyList<Triple<String, String, Int>>(), transport("mai"))
    }

    private fun search(regionIds: List<String>, text: String, limit: Int = 50) =
        query("searchByName", mapOf("regionIds" to regionIds, "pattern" to accentInsensitiveGlob(text), "limit" to limit)).map { it.name }

    @Test
    fun `searchByName trova senza badare a maiuscole e accenti in nome locale italiano e inglese`() {
        addPoi("lettonia", "Rīga Centrale", "station", "railway=station", 56.9, 24.1)
        addPoi("lettonia", "Stazione", "station", "railway=station", 56.9, 24.2, nameIt = "Stazione di Rīga")
        addPoi("lettonia", "Bahnhof", "station", "railway=station", 56.9, 24.3, nameEn = "Riga Station")
        addPoi("lettonia", "Altro", "station", "railway=station", 56.9, 24.4)

        assertEquals(setOf("Rīga Centrale", "Stazione", "Bahnhof"), search(listOf("lettonia"), "riga").toSet())
        assertEquals(setOf("Rīga Centrale", "Stazione", "Bahnhof"), search(listOf("lettonia"), "RĪGA").toSet())
        assertEquals(listOf("Altro"), search(listOf("lettonia"), "ALT"))
    }

    @Test
    fun `searchByName esclude il pacchetto extra e le regioni non chieste e rispetta il limite`() {
        addPoi("a", "Caffe Uno", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "Caffe Due", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "Caffe Extra", "cafe", "amenity=cafe", 1.0, 1.0, extra = true)
        addPoi("b", "Caffe Tre", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("c", "Caffe Quattro", "cafe", "amenity=cafe", 1.0, 1.0)

        assertEquals(setOf("Caffe Uno", "Caffe Due", "Caffe Tre"), search(listOf("a", "b"), "caffe").toSet())
        assertEquals(2, search(listOf("a", "b"), "caffe", limit = 2).size)
        assertEquals(emptyList<String>(), search(listOf("a", "b"), "caffe", limit = 0))
        assertEquals(emptyList<String>(), search(emptyList(), "caffe"))
        assertEquals(emptyList<String>(), search(listOf("sconosciuta"), "caffe"))
    }

    @Test
    fun `searchByName tratta i caratteri speciali di GLOB e di SQL come testo`() {
        addPoi("a", "50% di sconto", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "Caffe [Uno]", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "Caffe*Due", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "Caffe Tre?", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "D'Angelo", "cafe", "amenity=cafe", 1.0, 1.0)

        assertEquals(listOf("Caffe [Uno]"), search(listOf("a"), "[uno]"))
        assertEquals(listOf("Caffe*Due"), search(listOf("a"), "e*d"))
        assertEquals(listOf("Caffe Tre?"), search(listOf("a"), "tre?"))
        assertEquals(listOf("D'Angelo"), search(listOf("a"), "d'angelo"))
        assertEquals(listOf("50% di sconto"), search(listOf("a"), "50%"))
        assertEquals(emptyList<String>(), search(listOf("a"), "x' OR 1=1 --"))
        assertEquals(5, count("poi"))
    }

    @Test
    fun `nearest ordina per distanza con la correzione della longitudine e resta nel rettangolo`() {
        // A 60 N un grado di longitudine vale meta' di uno di latitudine: lonScale = cos^2(60) = 0,25.
        addPoi("n", "Nord", "cafe", "amenity=cafe", 60.0010, 10.0000)
        addPoi("n", "Est", "cafe", "amenity=cafe", 60.0000, 10.0016)
        addPoi("n", "Lontano", "cafe", "amenity=cafe", 60.0100, 10.0000)
        addPoi("n", "Extra", "cafe", "amenity=cafe", 60.0000, 10.0000, extra = true)
        addPoi("m", "Altra regione", "cafe", "amenity=cafe", 60.0000, 10.0001)
        addPoi("x", "Non chiesta", "cafe", "amenity=cafe", 60.0000, 10.0000)

        fun nearest(limit: Int = 10, regions: List<String> = listOf("n", "m")) = query(
            "nearest",
            mapOf(
                "regionIds" to regions, "lat" to 60.0, "lon" to 10.0, "lonScale" to 0.25, "minLat" to 59.995, "maxLat" to 60.005,
                "minLon" to 9.99, "maxLon" to 10.01, "limit" to limit,
            ),
        ).map { it.name }

        // Est a 0,0016 gradi di longitudine pesa 0,0008 di latitudine: piu' vicino di Nord (0,0010). "Lontano" e' fuori dal rettangolo.
        assertEquals(listOf("Altra regione", "Est", "Nord"), nearest())
        assertEquals(listOf("Altra regione"), nearest(limit = 1))
        assertEquals(emptyList<String>(), nearest(regions = emptyList()))
    }

    @Test
    fun `nearest include i POI esattamente sul bordo del rettangolo`() {
        addPoi("n", "Sul bordo", "cafe", "amenity=cafe", 60.005, 10.01)
        val result = query(
            "nearest",
            mapOf(
                "regionIds" to listOf("n"), "lat" to 60.0, "lon" to 10.0, "lonScale" to 1.0, "minLat" to 59.995, "maxLat" to 60.005,
                "minLon" to 9.99, "maxLon" to 10.01, "limit" to 5,
            ),
        )
        assertEquals(listOf("Sul bordo"), result.map { it.name })
    }

    private fun names() =
        connection.createStatement().use { s -> s.executeQuery("SELECT name FROM poi ORDER BY name").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    private fun deleteFor(regionId: String, extra: Boolean?) =
        runNamed(connection, poiQueries.getValue("deleteForRegion"), mapOf("regionId" to regionId, "extra" to extra)) { }

    @Test
    fun `deleteForRegion con extra nullo toglie tutto, altrimenti solo base o solo extra`() {
        fun fill() {
            exec("DELETE FROM poi")
            addPoi("a", "Base", "cafe", "amenity=cafe", 1.0, 1.0)
            addPoi("a", "Extra", "cafe", "amenity=cafe", 1.0, 1.0, extra = true)
            addPoi("b", "Altra", "cafe", "amenity=cafe", 1.0, 1.0)
        }

        fill()
        deleteFor("a", null)
        assertEquals(listOf("Altra"), names())

        fill()
        deleteFor("a", false)
        assertEquals(listOf("Altra", "Extra"), names())

        fill()
        deleteFor("a", true)
        assertEquals(listOf("Altra", "Base"), names())

        fill()
        deleteFor("mai", null)
        assertEquals(3, names().size)
    }

    @Test
    fun `categoryTagsInRegion elenca solo le coppie usate dalla regione`() {
        addPoi("a", "Bar", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("a", "Bar 2", "cafe", "amenity=cafe", 1.0, 1.0)
        addPoi("b", "Farmacia", "pharmacy", "amenity=pharmacy", 1.0, 1.0)

        val tags = runNamed(connection, poiQueries.getValue("categoryTagsInRegion"), mapOf("regionId" to "a")) {
            CategoryTag(it.getString("category"), it.getString("osmTag"))
        }
        assertEquals(listOf(CategoryTag("cafe", "amenity=cafe")), tags)
    }

    // ---- PoiCodeDao ----

    private fun code(name: String, params: Map<String, Any?>) = runNamed(connection, codeQueries.getValue(name), params) { it.getLong(1) }

    @Test
    fun `ensureRegion e regionRef danno sempre lo stesso riferimento`() {
        code("ensureRegion", mapOf("regionId" to "italia"))
        code("ensureRegion", mapOf("regionId" to "italia"))
        code("ensureRegion", mapOf("regionId" to "perù 東"))

        assertEquals(2, count("poi_region"))
        val italy = code("regionRef", mapOf("regionId" to "italia")).single()
        val peru = code("regionRef", mapOf("regionId" to "perù 東")).single()
        assertTrue(italy != peru)
        assertEquals(emptyList<Long>(), code("regionRef", mapOf("regionId" to "Italia")))
    }

    @Test
    fun `ensureCode e codeOf distinguono le coppie e non le duplicano`() {
        code("ensureCode", mapOf("category" to "station", "osmTag" to "railway=station"))
        code("ensureCode", mapOf("category" to "station", "osmTag" to "railway=station"))
        code("ensureCode", mapOf("category" to "subway_station", "osmTag" to "railway=station"))
        code("ensureCode", mapOf("category" to "", "osmTag" to ""))

        assertEquals(3, count("poi_code"))
        val train = code("codeOf", mapOf("category" to "station", "osmTag" to "railway=station")).single()
        val metro = code("codeOf", mapOf("category" to "subway_station", "osmTag" to "railway=station")).single()
        assertTrue(train != metro)
        assertEquals(1, code("codeOf", mapOf("category" to "", "osmTag" to "")).size)
        assertEquals(emptyList<Long>(), code("codeOf", mapOf("category" to "Station", "osmTag" to "railway=station")))
    }
}
