package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Aggancio dei TripUpdates GTFS-RT alle partenze: logica pura e tabellone su un transit.db sintetico con gli id GTFS. */
class TransitRealtimeTest {
    private val riga = ZoneId.of("Europe/Riga")
    private val oct1 = LocalDate.parse("2026-10-01")

    // 09:00 dell'1 ottobre a Riga (UTC+3).
    private val nineAm = gtfsEpochSeconds(oct1, 540, riga)

    private fun feed(vararg trips: RealtimeTrip) = RealtimeFeed(null, trips.groupBy { it.tripId })

    private fun trip(stops: List<RealtimeStop> = emptyList(), date: LocalDate? = oct1, cancelled: Boolean = false, delay: Int? = null) =
        RealtimeTrip("T1", date, cancelled, delay, stops)

    private fun key(stopId: String? = "S2", scheduled: Long = nineAm, date: LocalDate = oct1) = RealtimeKey("T1", stopId, date, scheduled)

    @Test
    fun `ora GTFS in secondi Unix, anche oltre mezzanotte`() {
        assertEquals(Instant.parse("2026-10-01T06:00:00Z").epochSecond, nineAm)
        // 24:30 del giorno di servizio 1 ottobre = 00:30 del 2.
        assertEquals(Instant.parse("2026-10-01T21:30:00Z").epochSecond, gtfsEpochSeconds(oct1, 1470, riga))
    }

    @Test
    fun `corsa soppressa, fermata saltata e corsa di un altro giorno`() {
        assertEquals(DepartureRealtime.Cancelled, realtimeOf(key(), feed(trip(cancelled = true))))
        assertEquals(DepartureRealtime.Cancelled, realtimeOf(key(), feed(trip(listOf(RealtimeStop("S2", skipped = true, null, null))))))
        // start_date di domani: non e' la corsa di oggi.
        assertNull(realtimeOf(key(), feed(trip(date = oct1.plusDays(1), cancelled = true))))
        // Due corse con lo stesso trip_id in giorni diversi: vale quella del giorno.
        val both = feed(trip(date = oct1.plusDays(1), cancelled = true), trip(delay = 120))
        assertEquals(DepartureRealtime.Delay(2), realtimeOf(key(), both))
        // Senza start_date vale per la corsa del giorno.
        assertEquals(DepartureRealtime.Delay(2), realtimeOf(key(), feed(trip(date = null, delay = 120))))
        assertNull(realtimeOf(key(), RealtimeFeed(null, emptyMap())))
        // NO_DATA alla fermata: nessuna previsione, nemmeno il ritardo della corsa.
        assertNull(realtimeOf(key(), feed(trip(listOf(RealtimeStop("S2", false, null, null, noData = true)), delay = 300))))
    }

    @Test
    fun `ritardo della fermata, dichiarato o dall'ora prevista`() {
        val atStop = RealtimeStop("S2", skipped = false, delaySeconds = 300, timeEpochSeconds = nineAm + 300)
        assertEquals(DepartureRealtime.Delay(5), realtimeOf(key(), feed(trip(listOf(atStop), delay = 60))))
        val onlyTime = RealtimeStop("S2", skipped = false, delaySeconds = null, timeEpochSeconds = nineAm - 120)
        assertEquals(DepartureRealtime.Delay(-2), realtimeOf(key(), feed(trip(listOf(onlyTime)))))
        // Dall'ora prevista, per difetto: transit.db ha solo i minuti (40 s dopo l'orario e' in orario).
        assertEquals(DepartureRealtime.Delay(0), realtimeOf(key(), feed(trip(listOf(onlyTime.copy(timeEpochSeconds = nineAm + 40))))))
        // Solo l'ora d'arrivo, senza ritardo: non dice la partenza (la sosta non e' un anticipo); vale il ritardo della corsa.
        assertEquals(DepartureRealtime.Delay(1), realtimeOf(key(), feed(trip(listOf(onlyTime.copy(fromArrival = true)), delay = 60))))
        // Un ritardo oltre un giorno e' un errore del feed.
        assertNull(realtimeOf(key(), feed(trip(listOf(atStop.copy(delaySeconds = 90_000))))))
        // 89 secondi arrotondano a 1 minuto, 29 a 0 (in orario).
        assertEquals(DepartureRealtime.Delay(1), realtimeOf(key(), feed(trip(listOf(atStop.copy(delaySeconds = 89))))))
        assertEquals(DepartureRealtime.Delay(0), realtimeOf(key(), feed(trip(listOf(atStop.copy(delaySeconds = 29))))))
    }

    @Test
    fun `senza la fermata vale l'ultimo aggiornamento prima, poi il ritardo della corsa`() {
        // S1 alle 08:50 (+4), S3 alle 09:10 (+10): alla fermata delle 09:00 vale S1.
        val s1 = RealtimeStop("S1", skipped = false, delaySeconds = 240, timeEpochSeconds = nineAm - 600 + 240)
        val s3 = RealtimeStop("S3", skipped = false, delaySeconds = 600, timeEpochSeconds = nineAm + 600 + 600)
        assertEquals(DepartureRealtime.Delay(4), realtimeOf(key(), feed(trip(listOf(s3, s1), delay = 60))))
        // Una fermata saltata non propaga il suo ritardo.
        assertEquals(DepartureRealtime.Delay(4), realtimeOf(key(), feed(trip(listOf(s1, s3.copy(stopId = "S1b", timeEpochSeconds = nineAm - 60 + 600, skipped = true))))))
        // Un NO_DATA dopo l'ultimo aggiornamento: anche le fermate seguenti sono senza dati.
        assertNull(realtimeOf(key(), feed(trip(listOf(s1, RealtimeStop("S1c", false, null, null, noData = true)), delay = 60))))
        // Solo aggiornamenti dopo la fermata (Renfe: la prossima fermata del treno): il treno e' gia' passato.
        assertNull(realtimeOf(key(), feed(trip(listOf(s3), delay = 600))))
        // Nessun aggiornamento ordinabile: il ritardo della corsa.
        assertEquals(DepartureRealtime.Delay(3), realtimeOf(key(), feed(trip(listOf(RealtimeStop("S9", false, null, null)), delay = 180))))
        assertNull(realtimeOf(key(), feed(trip())))
    }

    // --- tabellone ---

    private lateinit var connection: Connection

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
            "CREATE TABLE gtfs_trip(id INTEGER PRIMARY KEY, trip_id TEXT)",
            "CREATE TABLE gtfs_stop(id INTEGER PRIMARY KEY, stop_id TEXT)",
            "INSERT INTO meta VALUES ('feed_id','mdb-1'),('format','2'),('timezone','Europe/Riga'),('window_start','20260929')," +
                "('window_days','30'),('valid_until','20261028'),('gtfs_ids','1')",
            "INSERT INTO stop VALUES (1,'A','Vicina',56950000,24100000,NULL)",
            "INSERT INTO route VALUES (1,'22','Riga - Jurmala',3,NULL,NULL)",
            "INSERT INTO headsign VALUES (1,'Centrs')",
            "INSERT INTO gtfs_stop VALUES (1,'S2')",
            // Tre corse dalla fermata 1: 08:50 (gia' passata in orario), 09:20, 10:00.
            "INSERT INTO pattern_stop VALUES (1, 1, 0)",
            "INSERT INTO trip VALUES (1, 530, 1, 1, 1, 1), (1, 560, 2, 1, 1, 1), (1, 600, 3, 1, 1, 1)",
            "INSERT INTO gtfs_trip VALUES (1,'T1'),(2,'T2'),(3,'T3')",
        )
        connection.prepareStatement("INSERT INTO service VALUES (1, ?)").use { it.setBytes(1, byteArrayOf(0b0000_0100)); it.execute() }
    }

    @After
    fun tearDown() = connection.close()

    private fun exec(vararg sql: String) = connection.createStatement().use { s -> sql.forEach { s.execute(it) } }

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

    private fun items(realtime: RealtimeFeed?) =
        (readFeedBoard(query, TransitFeedInfo("mdb-1", "Riga", "Rigas satiksme"), 56.95, 24.10, Instant.parse("2026-10-01T06:00:00Z"), realtime = realtime) as TransitBoard.Departures).items

    private fun rt(tripId: String, delay: Int? = null, cancelled: Boolean = false) = RealtimeTrip(tripId, oct1, cancelled, delay, emptyList())

    @Test
    fun `senza feed il tabellone resta l'orario`() {
        val offline = items(null)
        assertEquals(listOf(20, 60), offline.map { it.inMinutes })
        assertEquals(listOf(null, null), offline.map { it.realtime })
    }

    @Test
    fun `ritardi e soppressioni accanto all'orario, e la corsa gia' passata in ritardo ricompare`() {
        val feed = RealtimeFeed(null, listOf(rt("T1", delay = 900), rt("T2", cancelled = true), rt("T3", delay = -60)).groupBy { it.tripId })
        val items = items(feed)
        // 08:50 + 15 = 09:05 (fra 5 minuti), 09:20 soppressa, 10:00 - 1 = 09:59.
        assertEquals(listOf(5, 20, 59), items.map { it.inMinutes })
        assertEquals(listOf(530, 560, 600), items.map { it.minuteOfDay })
        assertEquals(listOf(DepartureRealtime.Delay(15), DepartureRealtime.Cancelled, DepartureRealtime.Delay(-1)), items.map { it.realtime })
    }

    @Test
    fun `un transit_db senza id GTFS ignora il feed`() {
        exec("DELETE FROM meta WHERE key = 'gtfs_ids'")
        val feed = RealtimeFeed(null, listOf(rt("T2", cancelled = true)).groupBy { it.tripId })
        assertEquals(listOf(null, null), items(feed).map { it.realtime })
    }

    @Test
    fun `le partenze unite tengono il tempo reale nelle successive, senza le soppresse`() {
        val withLater = RealtimeFeed(null, listOf(rt("T2", delay = 120), rt("T3", delay = 300)).groupBy { it.tripId })
        val row = (combineBoards(listOf(checkNotNull(readFeedBoard(query, null, 56.95, 24.10, Instant.parse("2026-10-01T06:00:00Z"), realtime = withLater)))) as TransitBoard.Departures)
            .items.single()
        assertEquals(DepartureRealtime.Delay(2), row.realtime)
        assertEquals(listOf<DepartureRealtime?>(DepartureRealtime.Delay(5)), row.later.map { it.realtime })
        val cancelledLater = RealtimeFeed(null, listOf(rt("T2", delay = 120), rt("T3", cancelled = true)).groupBy { it.tripId })
        val row2 = (combineBoards(listOf(checkNotNull(readFeedBoard(query, null, 56.95, 24.10, Instant.parse("2026-10-01T06:00:00Z"), realtime = cancelledLater)))) as TransitBoard.Departures)
            .items.single()
        assertEquals(emptyList<TransitDeparture>(), row2.later)
    }

    @Test
    fun `una corsa DELETED non si mostra`() {
        val feed = RealtimeFeed(null, listOf(rt("T2").copy(deleted = true)).groupBy { it.tripId })
        assertEquals(listOf(60), items(feed).map { it.inMinutes })
    }
}
