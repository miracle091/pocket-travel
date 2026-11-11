package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Vista della corsa, aggancio HSL per chiavi e avvisi del tabellone su un transit.db sintetico con le tabelle nuove
 * (trip_stop, gtfs_route, chiavi delle corse).
 *
 * Fermata 1 (banchina della stazione 5) a Riga; la corsa 1/2/3 ha il pattern completo 10: Prima (2, offset -10, senza
 * discesa), la fermata 1 (offset 0), Capolinea (3, offset 15, senza salita). Partenze alla fermata 1: 08:50, 09:20, 10:00.
 */
class TransitTripTest {
    private val riga = ZoneId.of("Europe/Riga")
    private val oct1 = LocalDate.parse("2026-10-01")
    private val now = Instant.parse("2026-10-01T06:00:00Z") // 09:00 a Riga
    private val info = TransitFeedInfo("mdb-1", "Riga", "Rigas satiksme")

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
            "CREATE TABLE trip(pattern INTEGER, start INTEGER, id INTEGER, route INTEGER, service INTEGER, headsign INTEGER, full_pattern INTEGER, PRIMARY KEY (pattern, start, id)) WITHOUT ROWID",
            "CREATE TABLE pattern_stop(stop INTEGER, pattern INTEGER, offset INTEGER, PRIMARY KEY (stop, pattern, offset)) WITHOUT ROWID",
            "CREATE TABLE trip_stop(pattern INTEGER PRIMARY KEY, stops BLOB NOT NULL)",
            "CREATE TABLE gtfs_trip(id INTEGER PRIMARY KEY, trip_id TEXT, direction_id INTEGER, start_time INTEGER)",
            "CREATE TABLE gtfs_stop(id INTEGER PRIMARY KEY, stop_id TEXT)",
            "CREATE TABLE gtfs_route(id INTEGER PRIMARY KEY, route_id TEXT)",
            "INSERT INTO meta VALUES ('feed_id','mdb-1'),('format','2'),('timezone','Europe/Riga'),('window_start','20260929')," +
                "('window_days','30'),('valid_until','20261028'),('gtfs_ids','1'),('gtfs_routes','1'),('trip_stops','1')",
            "INSERT INTO stop VALUES (1,'A','Vicina',56950000,24100000,5),(5,NULL,'Stazione',56950100,24100100,NULL)," +
                "(2,'B','Prima',56960000,24200000,NULL),(3,'C','Capolinea',56970000,24300000,NULL)",
            "INSERT INTO route VALUES (1,'22','Riga - Jurmala',3,'FF0000',NULL),(2,'50','Cinquanta',3,NULL,NULL)",
            "INSERT INTO headsign VALUES (1,'Centrs')",
            "INSERT INTO gtfs_stop VALUES (1,'S1'),(2,'S2'),(3,'S3'),(5,'ST')",
            "INSERT INTO gtfs_route VALUES (1,'R22'),(2,'R50')",
            // Pattern 1: solo la fermata 1 (tabellone); pattern 2: un'altra linea dalla stessa fermata, alle 15:00.
            "INSERT INTO pattern_stop VALUES (1, 1, 0), (1, 2, 0)",
            "INSERT INTO trip VALUES (1, 530, 1, 1, 1, 1, 10), (1, 560, 2, 1, 1, 1, 10), (1, 600, 3, 1, 1, 1, 10), (2, 900, 4, 2, 1, NULL, NULL)",
            "INSERT INTO gtfs_trip VALUES (1,'T1',1,32400),(2,'T2',1,33000),(3,'T3',1,35400),(4,'T4',0,53400)",
        )
        connection.prepareStatement("INSERT INTO service VALUES (1, ?)").use { it.setBytes(1, byteArrayOf(0b0000_0100)); it.execute() }
        connection.prepareStatement("INSERT INTO trip_stop VALUES (10, ?)").use {
            it.setBytes(1, blob(intArrayOf(1, 2, -10, 2), intArrayOf(2, 1, 0, 0), intArrayOf(3, 3, 15, 1)))
            it.execute()
        }
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

    // Il blob di trip_stop come lo scrive la pipeline; ogni fermata: stop_sequence, id fermata, offset, flag.
    private fun blob(vararg stops: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
        }
        fun zigzag(n: Int) = varint((n shl 1) xor (n shr 31))
        var seq = 0
        var stop = 0
        var offset = 0
        for (fields in stops) {
            val (s, id, off) = fields
            val flags = fields[3]
            varint(s - seq)
            zigzag(id - stop)
            zigzag(off - offset)
            out.write(flags)
            seq = s
            stop = id
            offset = off
        }
        return out.toByteArray()
    }

    private fun board(realtime: RealtimeFeed? = null) =
        readFeedBoard(query, info, 56.95, 24.10, now, realtime = realtime) as TransitBoard.Departures

    private fun at(epochMinute: Int, delay: Int = 0) = gtfsEpochSeconds(oct1, epochMinute, riga) + delay

    private fun rtStop(id: String?, delay: Int, minute: Int, sequence: Int? = null) =
        RealtimeStop(id, skipped = false, delaySeconds = delay, timeEpochSeconds = at(minute, delay), stopSequence = sequence)

    private fun feedOf(vararg trips: RealtimeTrip, without: List<RealtimeTrip> = emptyList(), alerts: List<TransitAlert> = emptyList()) =
        RealtimeFeed(null, trips.groupBy { it.tripId }, without, alerts)

    private fun rtTrip(id: String, stops: List<RealtimeStop> = emptyList(), delay: Int? = null) = RealtimeTrip(id, oct1, false, delay, stops)

    // --- blob ---

    @Test
    fun `il blob di trip_stop - byte e valori, con delta negativi`() {
        val bytes = byteArrayOf(0x01, 0x04, 0x13, 0x02, 0x01, 0x01, 0x14, 0x00, 0x01, 0x04, 0x1E, 0x01)
        val stops = checkNotNull(decodeTripStops(bytes))
        assertEquals(listOf(1, 2, 3), stops.map { it.seq })
        assertEquals(listOf(2, 1, 3), stops.map { it.stop })
        assertEquals(listOf(-10, 0, 15), stops.map { it.offset })
        assertEquals(listOf(2, 0, 1), stops.map { it.flags })
        assertEquals(listOf(true, true, false), stops.map { it.pickup })
        assertEquals(listOf(false, true, true), stops.map { it.dropOff })
        // Quello che scrive il test e' lo stesso blob.
        assertEquals(bytes.toList(), blob(intArrayOf(1, 2, -10, 2), intArrayOf(2, 1, 0, 0), intArrayOf(3, 3, 15, 1)).toList())
    }

    @Test
    fun `il blob - varint a piu' byte e blob troncato`() {
        // seq 300 (AC 02), fermata 1 (zigzag 2), offset 1000 (zigzag 2000 = D0 0F), flag 0.
        val stop = checkNotNull(decodeTripStops(byteArrayOf(0xAC.toByte(), 0x02, 0x02, 0xD0.toByte(), 0x0F, 0x00))).single()
        assertEquals(listOf(300, 1, 1000, 0), listOf(stop.seq, stop.stop, stop.offset, stop.flags))
        assertNull(decodeTripStops(byteArrayOf(0x01, 0x04, 0x13)))
        assertNull(decodeTripStops(byteArrayOf(0x01, 0x04, 0x93.toByte())))
        assertEquals(emptyList<RawTripStop>(), decodeTripStops(ByteArray(0)))
    }

    @Test
    fun `il blob - gli stessi byte fissati dal test di encodeTripStops della pipeline`() {
        val bytes = intArrayOf(0x01, 0x0A, 0x09, 0x01, 0x01, 0x03, 0x1E, 0x00, 0xC6, 0x01, 0xF2, 0x0A, 0x0B, 0x02)
        val stops = checkNotNull(decodeTripStops(ByteArray(bytes.size) { bytes[it].toByte() }))
        assertEquals(
            listOf(listOf(1, 5, -5, 1), listOf(2, 3, 10, 0), listOf(200, 700, 4, 2)),
            stops.map { listOf(it.seq, it.stop, it.offset, it.flags) },
        )
    }

    // --- identita' della corsa e vista ---

    @Test
    fun `la partenza porta la corsa solo se la rete ha trip_stops, anche nelle successive`() {
        val items = board().items
        assertEquals(listOf(2, 3), items.map { it.trip?.trip })
        assertEquals(TripRef("mdb-1", 2, oct1, 1L, 560, 1, 560), items[0].trip)
        val combined = combineBoards(listOf(board())) as TransitBoard.Departures
        val row = combined.items.single()
        assertEquals(2, row.trip?.trip)
        assertEquals(listOf(3), row.later.map { it.trip?.trip })

        exec("DELETE FROM meta WHERE key = 'trip_stops'")
        assertEquals(listOf(null, null), board().items.map { it.trip })
    }

    @Test
    fun `la vista della corsa - orari del tabellone, capolinea, flag e fermata evidenziata`() {
        val departure = board().items.first()
        val view = checkNotNull(readTripView(query, checkNotNull(departure.trip), now, null))
        assertEquals("22", view.line)
        assertEquals(TransitMode.BUS, view.mode)
        assertEquals("Centrs", view.headsign)
        assertNotNull(view.color)
        assertEquals(listOf("Prima", "Vicina", "Capolinea"), view.stops.map { it.name })
        // La fermata prima della salita ha l'offset negativo: 09:10; quella del tabellone ha l'ora del tabellone.
        assertEquals(listOf(550, 560, 575), view.stops.map { it.minuteOfDay })
        assertEquals(listOf(10, 20, 35), view.stops.map { it.inMinutes })
        assertEquals(departure.inMinutes, view.stops[1].inMinutes)
        assertEquals(listOf(true, true, false), view.stops.map { it.pickup })
        assertEquals(listOf(false, true, true), view.stops.map { it.dropOff })
        assertEquals(listOf(false, true, false), view.stops.map { it.isBoardStop })
        assertEquals(56.96, view.stops[0].latitude, 1e-9)
        assertEquals(24.2, view.stops[0].longitude, 1e-9)
        assertEquals(listOf(null, null, null), view.stops.map { it.realtime })
    }

    @Test
    fun `la vista - il ritardo si propaga alle fermate, tabellone e vista concordano`() {
        val feed = feedOf(rtTrip("T2", listOf(rtStop("S2", 120, 550))))
        val departure = board(feed).items.first()
        val view = checkNotNull(readTripView(query, checkNotNull(departure.trip), now, feed))
        assertEquals(List(3) { DepartureRealtime.Delay(2) }, view.stops.map { it.realtime })
        assertEquals(listOf(12, 22, 37), view.stops.map { it.inMinutes })
        assertEquals(departure.inMinutes, view.stops[1].inMinutes)
        // Ora programmata invariata.
        assertEquals(listOf(550, 560, 575), view.stops.map { it.minuteOfDay })
    }

    @Test
    fun `la vista aggancia le fermate per stop_sequence se il feed non ha lo stop_id`() {
        val feed = feedOf(rtTrip("T2", listOf(rtStop(null, 300, 560, sequence = 2))))
        val view = checkNotNull(readTripView(query, checkNotNull(board().items.first().trip), now, feed))
        // Prima della fermata aggiornata niente (la corsa non e' ancora passata di li'); da li' in poi 5 minuti.
        assertEquals(listOf(null, DepartureRealtime.Delay(5), DepartureRealtime.Delay(5)), view.stops.map { it.realtime })
    }

    @Test
    fun `la vista di una corsa soppressa o saltata`() {
        val feed = feedOf(RealtimeTrip("T2", oct1, true, null, emptyList()))
        val view = checkNotNull(readTripView(query, checkNotNull(board().items.first().trip), now, feed))
        assertEquals(List(3) { DepartureRealtime.Cancelled }, view.stops.map { it.realtime })
        val skipped = feedOf(rtTrip("T2", listOf(RealtimeStop("S3", skipped = true, delaySeconds = null, timeEpochSeconds = null))))
        val view2 = checkNotNull(readTripView(query, checkNotNull(board().items.first().trip), now, skipped))
        assertEquals(DepartureRealtime.Cancelled, view2.stops[2].realtime)
    }

    @Test
    fun `la vista e' nulla senza trip_stops, senza la corsa o con un blob guasto`() {
        val ref = checkNotNull(board().items.first().trip)
        assertNull(readTripView(query, ref.copy(trip = 99), now, null))
        exec("UPDATE trip SET full_pattern = NULL WHERE id = 2")
        assertNull(readTripView(query, ref, now, null))
        exec("UPDATE trip SET full_pattern = 10 WHERE id = 2")
        exec("UPDATE trip_stop SET stops = x'0104'")
        assertNull(readTripView(query, ref, now, null))
        exec("DELETE FROM meta WHERE key = 'trip_stops'")
        assertNull(readTripView(query, ref, now, null))
    }

    // --- Helsinki: corse senza trip_id ---

    private fun hslTrip(direction: Int? = 1, start: String = "09:10:00", route: String = "R22", delay: Int = 180) =
        RealtimeTrip("", oct1, false, delay, emptyList(), routeId = route, directionId = direction, startTime = start)

    @Test
    fun `l'ora GTFS in secondi`() {
        assertEquals(33000, gtfsTimeSeconds("09:10:00"))
        assertEquals(90_000 + 61, gtfsTimeSeconds("25:01:01"))
        assertNull(gtfsTimeSeconds("9:10"))
        assertNull(gtfsTimeSeconds(null))
        assertNull(gtfsTimeSeconds("ab:cd:ef"))
    }

    @Test
    fun `HSL - la corsa senza trip_id si aggancia per linea, direzione, data e ora di partenza`() {
        exec("INSERT INTO meta VALUES ('trip_keys','1')")
        val items = board(feedOf(without = listOf(hslTrip()))).items
        assertEquals(DepartureRealtime.Delay(3), items[0].realtime)
        assertEquals(23, items[0].inMinutes)
        assertNull(items[1].realtime)
        // La vista usa lo stesso aggancio.
        val view = checkNotNull(readTripView(query, checkNotNull(items[0].trip), now, feedOf(without = listOf(hslTrip()))))
        assertEquals(List(3) { DepartureRealtime.Delay(3) }, view.stops.map { it.realtime })
    }

    @Test
    fun `HSL - nessun aggancio con direzione, linea, ora o giorno diversi o senza trip_keys`() {
        exec("INSERT INTO meta VALUES ('trip_keys','1')")
        for (other in listOf(hslTrip(direction = 0), hslTrip(route = "R50"), hslTrip(start = "09:11:00"), hslTrip().copy(startDate = oct1.plusDays(1)))) {
            assertEquals(listOf(null, null), board(feedOf(without = listOf(other))).items.map { it.realtime })
        }
        exec("DELETE FROM meta WHERE key = 'trip_keys'")
        assertEquals(listOf(null, null), board(feedOf(without = listOf(hslTrip()))).items.map { it.realtime })
    }

    @Test
    fun `HSL - il trip_id vince sulle chiavi`() {
        exec("INSERT INTO meta VALUES ('trip_keys','1')")
        val feed = feedOf(rtTrip("T2", delay = 60), without = listOf(hslTrip()))
        assertEquals(DepartureRealtime.Delay(1), board(feed).items[0].realtime)
    }

    // --- avvisi ---

    private fun text(value: String) = TransitText(listOf(TransitTranslation(value, "it")))

    private fun alert(
        header: String,
        vararg entities: TransitInformedEntity,
        periods: List<TransitActivePeriod> = emptyList(),
        effect: Int? = null,
    ) = TransitAlert(periods, entities.toList(), null, effect, null, null, text(header), null)

    private fun headers(feed: RealtimeFeed) = board(feed).alerts.map { it.header?.pick("it") }

    @Test
    fun `avvisi per fermata, anche della stazione e della banchina`() {
        val feed = feedOf(
            alerts = listOf(
                alert("banchina", TransitInformedEntity(stopId = "S1")),
                alert("stazione", TransitInformedEntity(stopId = "ST")),
                alert("altrove", TransitInformedEntity(stopId = "S2")),
            ),
        )
        assertEquals(listOf("banchina", "stazione"), headers(feed).sortedBy { it })
    }

    @Test
    fun `avvisi per linea, anche senza partenze in finestra, e per corsa`() {
        val feed = feedOf(
            alerts = listOf(
                alert("linea 22", TransitInformedEntity(routeId = "R22")),
                alert("linea 50 alle 15", TransitInformedEntity(routeId = "R50")),
                alert("linea 99", TransitInformedEntity(routeId = "R99")),
                alert("corsa in elenco", TransitInformedEntity(tripId = "T2")),
                alert("corsa fuori finestra", TransitInformedEntity(tripId = "T4")),
                alert("linea e fermata", TransitInformedEntity(routeId = "R22", stopId = "S2")),
                alert("mezzo", TransitInformedEntity(routeType = 3)),
                alert("altro mezzo", TransitInformedEntity(routeType = 1)),
            ),
        )
        assertEquals(setOf("linea 22", "linea 50 alle 15", "corsa in elenco", "mezzo"), headers(feed).toSet())
    }

    @Test
    fun `un avviso per tutta la rete vale sempre, uno vuoto o con la sola corsa per chiavi mai`() {
        val feed = feedOf(
            alerts = listOf(
                alert("rete", TransitInformedEntity(agencyId = "RS")),
                alert("vuoto", TransitInformedEntity()),
                alert("corsa per chiavi", TransitInformedEntity(tripRouteId = "R22", tripStartTime = "09:10:00")),
            ),
        )
        assertEquals(listOf("rete"), headers(feed))
    }

    @Test
    fun `avvisi attivi solo nel periodo`() {
        val stop = TransitInformedEntity(stopId = "S1")
        val before = Instant.parse("2026-10-01T05:00:00Z")
        val after = Instant.parse("2026-10-01T07:00:00Z")
        val feed = feedOf(
            alerts = listOf(
                alert("finito", stop, periods = listOf(TransitActivePeriod(null, before))),
                alert("futuro", stop, periods = listOf(TransitActivePeriod(after, null))),
                alert("in corso", stop, periods = listOf(TransitActivePeriod(before.minusSeconds(60), before), TransitActivePeriod(before, after))),
                alert("aperto", stop, periods = listOf(TransitActivePeriod(null, null))),
                alert("senza periodo", stop),
            ),
        )
        val alerts = board(feed).alerts
        assertEquals(setOf("in corso", "aperto", "senza periodo"), alerts.mapNotNull { it.header?.pick("it") }.toSet())
        val running = alerts.single { it.header?.pick("it") == "in corso" }
        assertEquals(before, running.start)
        assertEquals(after, running.end)
        assertEquals("mdb-1", running.network)
    }

    @Test
    fun `nessun avviso se la rete non ha gli id GTFS o il feed non ne ha`() {
        val feed = feedOf(alerts = listOf(alert("fermata", TransitInformedEntity(stopId = "S1"))))
        assertEquals(1, board(feed).alerts.size)
        assertEquals(emptyList<BoardAlert>(), board(feedOf()).alerts)
        assertEquals(emptyList<BoardAlert>(), board(null).alerts)
        exec("DELETE FROM meta WHERE key = 'gtfs_ids'")
        assertEquals(emptyList<BoardAlert>(), board(feed).alerts)
        // Senza gtfs_routes le linee non si agganciano, le fermate si'.
        exec("INSERT INTO meta VALUES ('gtfs_ids','1')")
        exec("DELETE FROM meta WHERE key = 'gtfs_routes'")
        val routes = feedOf(alerts = listOf(alert("linea", TransitInformedEntity(routeId = "R22")), alert("fermata", TransitInformedEntity(stopId = "S1"))))
        assertEquals(listOf("fermata"), headers(routes))
    }

    @Test
    fun `avvisi senza duplicati, dal piu' grave, al massimo venti, uniti tra le reti`() {
        val stop = TransitInformedEntity(stopId = "S1")
        val feed = feedOf(
            alerts = listOf(
                alert("generico", stop),
                alert("deviazione", stop, effect = 4),
                alert("sospeso", stop, effect = 1),
                alert("sospeso", stop, effect = 1),
                alert("sconosciuto", stop, effect = 8),
            ),
        )
        assertEquals(listOf("sospeso", "deviazione", "generico", "sconosciuto"), headers(feed))

        val many = feedOf(alerts = List(25) { alert("avviso $it", stop, effect = 3) })
        assertEquals(TRANSIT_BOARD_MAX_ALERTS, board(many).alerts.size)

        val one = board(feed)
        val combined = combineBoards(listOf(one, one)) as TransitBoard.Departures
        assertEquals(one.alerts, combined.alerts)
        assertTrue(combined.alerts.isNotEmpty())
        assertFalse(combined.alerts.any { it.header == null })
    }
}
