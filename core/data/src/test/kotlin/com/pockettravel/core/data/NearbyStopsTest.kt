package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** Fermate vicine a un punto su un transit.db sintetico (sqlite-jdbc) e unione delle reti. */
class NearbyStopsTest {
    private lateinit var connection: Connection

    private val lat = 56.95
    private val lon = 24.10
    private val now = Instant.parse("2026-10-01T06:00:00Z") // 09:00 a Riga
    private val feed = TransitFeedInfo("mdb-1", "Riga", "Rigas satiksme (CC0 1.0)")

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec(
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
            "CREATE TABLE stop(id INTEGER PRIMARY KEY, code TEXT, name TEXT, latE6 INTEGER, lonE6 INTEGER, parent INTEGER)",
            "CREATE TABLE route(id INTEGER PRIMARY KEY, short_name TEXT, long_name TEXT, type INTEGER, color TEXT, text_color TEXT)",
            "CREATE TABLE headsign(id INTEGER PRIMARY KEY, text TEXT)",
            "CREATE TABLE service(id INTEGER PRIMARY KEY, days BLOB)",
            "CREATE TABLE trip(pattern INTEGER, start INTEGER, id INTEGER, route INTEGER, service INTEGER, headsign INTEGER, PRIMARY KEY (pattern, start, id)) WITHOUT ROWID",
            "CREATE TABLE pattern_stop(stop INTEGER, pattern INTEGER, offset INTEGER, PRIMARY KEY (stop, pattern, offset)) WITHOUT ROWID",
            "INSERT INTO meta VALUES ('format','2'),('timezone','Europe/Riga'),('window_start','20260929'),('window_days','30'),('valid_until','20261028')",
            // 1: nel punto; 2 e 3: banchine della stazione 10 (a ~55 m); 5: a ~445 m; 6: a ~670 m.
            "INSERT INTO stop VALUES (1,'A','Vicina',56950000,24100000,NULL),(2,'B','Banchina',56950300,24100000,10)," +
                "(10,NULL,'Stazione',56950500,24100000,NULL),(3,'C','Banchina 2',56951800,24100000,10)," +
                "(5,'E','Bus nord',56954000,24100000,NULL),(6,'F','Oltre',56956000,24100000,NULL)",
            "INSERT INTO route VALUES (1,'22','Riga - Jurmala',3,'FF0000',NULL),(3,'M1','Metro',1,'FFFFFF','000000')",
            "INSERT INTO headsign VALUES (1,'Centrs')",
        )
        service(1, byteArrayOf(0b0000_0100)) // solo il 1 ottobre
        service(2, byteArrayOf(0b0000_0010)) // solo il 30 settembre
    }

    @After
    fun tearDown() = connection.close()

    private fun exec(vararg sql: String) = connection.createStatement().use { s -> sql.forEach { s.execute(it) } }

    private fun service(id: Int, days: ByteArray) {
        connection.prepareStatement("INSERT INTO service VALUES (?, ?)").use { it.setInt(1, id); it.setBytes(2, days); it.execute() }
    }

    private fun trip(id: Int, route: Int, service: Int, stop: Int, minute: Int) {
        exec("INSERT INTO trip VALUES ($id, $minute, $id, $route, $service, 1)", "INSERT INTO pattern_stop VALUES ($stop, $id, 0)")
    }

    private val query = object : TransitQuery {
        override fun <T> query(sql: String, read: (TransitRow) -> T): List<T> {
            val result = mutableListOf<T>()
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val row = object : TransitRow {
                        override fun int(column: Int) = rs.getInt(column + 1)
                        override fun string(column: Int): String? = rs.getString(column + 1)
                        override fun bytes(column: Int): ByteArray? = rs.getBytes(column + 1)
                    }
                    while (rs.next()) result += read(row)
                }
            }
            return result
        }
    }

    private fun nearby(radius: Double = NEARBY_STOPS_RADIUS_M) =
        readFeedNearbyStops(query, feed, lat, lon, radius, now)

    @Test
    fun `una stazione e le sue banchine sono una voce, dalla piu' vicina`() {
        trip(1, 1, 1, stop = 2, minute = 545)
        trip(2, 1, 1, stop = 3, minute = 550)
        val stops = checkNotNull(nearby())
        assertEquals(listOf("Vicina", "Stazione", "Bus nord"), stops.map { it.name })
        val station = stops[1]
        assertEquals(56.9505, station.latitude, 1e-6)
        assertEquals(55.6, station.distanceMeters, 1.0)
        assertEquals(listOf(5, 10), station.next.map { it.inMinutes })
        assertEquals(listOf("mdb-1"), station.networks)
    }

    @Test
    fun `il raggio limita le fermate`() {
        assertEquals(listOf("Vicina", "Stazione"), checkNotNull(nearby(100.0)).map { it.name })
        assertEquals(listOf("Vicina", "Stazione", "Bus nord", "Oltre"), checkNotNull(nearby(800.0)).map { it.name })
    }

    @Test
    fun `i mezzi ci sono anche senza partenze nella finestra`() {
        trip(1, 3, 2, stop = 5, minute = 545) // servizio di ieri
        trip(2, 1, 1, stop = 5, minute = 1000) // oltre le tre ore
        val busNord = checkNotNull(nearby()).first { it.name == "Bus nord" }
        assertEquals(setOf(TransitMode.METRO, TransitMode.BUS), busNord.modes)
        assertTrue(busNord.next.isEmpty())
    }

    @Test
    fun `le partenze sono in ordine e al massimo due`() {
        trip(1, 1, 1, stop = 1, minute = 560)
        trip(2, 1, 1, stop = 1, minute = 545)
        trip(3, 1, 1, stop = 1, minute = 550)
        assertEquals(listOf(5, 10), checkNotNull(nearby()).first().next.map { it.inMinutes })
    }

    @Test
    fun `una corsa che serve due fermate vicine compare in entrambe`() {
        exec("INSERT INTO trip VALUES (7, 545, 7, 1, 1, 1)", "INSERT INTO pattern_stop VALUES (1, 7, 0),(5, 7, 0)")
        val stops = checkNotNull(nearby())
        assertEquals(1, stops.first { it.name == "Vicina" }.next.size)
        assertEquals(1, stops.first { it.name == "Bus nord" }.next.size)
    }

    @Test
    fun `una rete scaduta o in un altro formato si salta`() {
        exec("UPDATE meta SET value = '20260101' WHERE key = 'valid_until'")
        assertNull(nearby())
        exec("UPDATE meta SET value = '20261028' WHERE key = 'valid_until'", "UPDATE meta SET value = '3' WHERE key = 'format'")
        assertNull(nearby())
    }

    @Test
    fun `senza fermate nel raggio la lista e' vuota`() {
        assertEquals(emptyList<NearbyStop>(), readFeedNearbyStops(query, feed, 40.0, 10.0, NEARBY_STOPS_RADIUS_M, now))
    }

    private fun stop(name: String, network: String, distance: Double, vararg minutes: Int, lonShift: Double = 0.0, mode: TransitMode = TransitMode.BUS) =
        NearbyStop(
            name, 56.95, 24.10 + lonShift, distance, setOf(mode),
            minutes.map { TransitDeparture("1", mode, null, null, null, 0, it, network = network) }, listOf(network),
        )

    @Test
    fun `reti diverse con lo stesso nome vicine si uniscono`() {
        val merged = mergeNearbyStops(
            listOf(
                listOf(stop("Centrs", "a", 80.0, 3, 20), stop("Altra", "a", 300.0)),
                listOf(stop("  centrs ", "b", 60.0, 5, 8, 30, mode = TransitMode.TRAM)),
            ),
        )
        assertEquals(listOf("centrs", "altra"), merged.map { it.name.trim().lowercase() })
        val centrs = merged.first()
        assertEquals(60.0, centrs.distanceMeters, 0.0)
        assertEquals(setOf(TransitMode.BUS, TransitMode.TRAM), centrs.modes)
        assertEquals(listOf("a", "b"), centrs.networks.sorted())
        assertEquals(listOf(3, 5), centrs.next.map { it.inMinutes })
    }

    @Test
    fun `lo stesso nome lontano piu' di cento metri o nella stessa rete resta separato`() {
        val far = mergeNearbyStops(listOf(listOf(stop("Centrs", "a", 80.0)), listOf(stop("Centrs", "b", 90.0, lonShift = 0.002))))
        assertEquals(2, far.size)
        val sameNetwork = mergeNearbyStops(listOf(listOf(stop("Centrs", "a", 80.0), stop("Centrs", "a", 90.0))))
        assertEquals(2, sameNetwork.size)
    }
}
