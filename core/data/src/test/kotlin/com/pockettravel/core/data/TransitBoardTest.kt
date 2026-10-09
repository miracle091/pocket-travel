package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate

/** Query e logica del tabellone su un transit.db sintetico (sqlite-jdbc al posto di android.database.sqlite). */
class TransitBoardTest {
    private lateinit var connection: Connection

    // Punto di riferimento (Riga) e stazione con due banchine: una vicina, una a ~200 m ma col parent della stazione.
    private val lat = 56.95
    private val lon = 24.10

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
            "INSERT INTO meta VALUES ('feed_id','mdb-1'),('format','2'),('timezone','Europe/Riga'),('window_start','20260929')," +
                "('window_days','30'),('valid_until','20261028')",
            // 1 e 2: banchine vicine (2 con parent 10); 10: stazione; 3: banchina a ~200 m della stazione; 4: lontana.
            "INSERT INTO stop VALUES (1,'A','Vicina',56950000,24100000,NULL),(2,'B','Banchina',56950300,24100000,10)," +
                "(10,NULL,'Stazione',56950500,24100000,NULL),(3,'C','Banchina lontana',56951800,24100000,10),(4,'D','Lontana',56990000,24200000,NULL)",
            "INSERT INTO route VALUES (1,'22','Riga - Jurmala',3,'FF0000',NULL),(2,'','Linea lunga',900,'',NULL),(3,'M1','Metro',1,'FFFFFF','000000')",
            "INSERT INTO headsign VALUES (1,'Centrs'),(2,'Aeroports')",
        )
        // Servizi: 1 = solo l'1 ottobre (giorno 2), 2 = solo il 30 settembre (giorno 1), 3 = solo il 2 ottobre (giorno 3).
        service(1, byteArrayOf(0b0000_0100))
        service(2, byteArrayOf(0b0000_0010))
        service(3, byteArrayOf(0b0000_1000))
    }

    @After
    fun tearDown() = connection.close()

    private fun exec(vararg sql: String) = connection.createStatement().use { s -> sql.forEach { s.execute(it) } }

    private fun service(id: Int, days: ByteArray) {
        connection.prepareStatement("INSERT INTO service VALUES (?, ?)").use { it.setInt(1, id); it.setBytes(2, days); it.execute() }
    }

    // Una corsa con un solo passaggio: pattern proprio (id della corsa), partenza al minuto del passaggio.
    private fun trip(id: Int, route: Int, service: Int, headsign: Int?, stop: Int, minute: Int) {
        exec("INSERT INTO trip (pattern, start, id, route, service, headsign) VALUES ($id, $minute, $id, $route, $service, ${headsign ?: "NULL"})", "INSERT INTO pattern_stop VALUES ($stop, $id, 0)")
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

    private val feed = TransitFeedInfo("mdb-1", "Riga", "Rigas satiksme (CC0 1.0)")

    // 1 ottobre 2026, ora di Riga (UTC+3): 09:00 = 06:00Z.
    private fun at(iso: String) = Instant.parse(iso)

    private fun board(now: String) = readFeedBoard(query, feed, lat, lon, at(now))

    private fun departures(now: String) = (board(now) as TransitBoard.Departures).items

    @Test
    fun finestraDiTreOreConEstremiInclusi() {
        trip(1, 1, 1, 1, stop = 1, minute = 539) // 08:59, gia' partito
        trip(2, 1, 1, 1, stop = 1, minute = 540) // adesso
        trip(3, 1, 1, 2, stop = 1, minute = 720) // 12:00, esattamente +180
        trip(4, 1, 1, 1, stop = 1, minute = 721) // fuori dalla finestra
        val items = departures("2026-10-01T06:00:00Z")
        assertEquals(listOf(0, 180), items.map { it.inMinutes })
        assertEquals(listOf("Centrs", "Aeroports"), items.map { it.headsign })
        assertEquals(listOf(540, 720), items.map { it.minuteOfDay })
    }

    @Test
    fun corseDelloStessoPatternConLOffsetDellaFermata() {
        // Pattern 50: parte dalla fermata lontana (4) e passa dalla vicina (1) dopo 7 minuti.
        exec(
            "INSERT INTO pattern_stop VALUES (4, 50, 0), (1, 50, 7)",
            "INSERT INTO trip VALUES (50, 530, 1, 1, 1, 1), (50, 560, 2, 1, 1, 1), (50, 720, 3, 1, 1, 1)",
        )
        // Alle 09:00: 530+7 = 08:57 gia' passata, 560+7 = 09:27 fra 27 minuti, 720+7 = 12:07 oltre le tre ore.
        assertEquals(listOf(27), departures("2026-10-01T06:00:00Z").map { it.inMinutes })
    }

    @Test
    fun laFonteHaLaDataDegliOrari() {
        trip(1, 1, 1, 1, stop = 1, minute = 545)
        val board = board("2026-10-01T06:00:00Z") as TransitBoard.Departures
        assertEquals(LocalDate.parse("2026-09-29"), board.feeds.single().dataDate)
        // Solo in memoria: feeds.json resta com'era.
        assertEquals(false, TransitFeedInfo.encode(board.feeds).contains("dataDate"))
    }

    @Test
    fun unFormatoDiversoSiIgnora() {
        trip(1, 1, 1, 1, stop = 1, minute = 545)
        exec("UPDATE meta SET value = '3' WHERE key = 'format'")
        assertNull(board("2026-10-01T06:00:00Z"))
    }

    @Test
    fun ilFusoDellaReteDecideOggiEAdesso() {
        trip(1, 1, 1, 1, stop = 1, minute = 545)
        // 06:00Z e' le 09:00 a Riga: il servizio del 1 ottobre e' attivo, la partenza delle 09:05 c'e'.
        assertEquals(listOf(5), departures("2026-10-01T06:00:00Z").map { it.inMinutes })
        // 22:00Z e' l'1:00 del 2 ottobre a Riga: il servizio del 1 ottobre non c'e' piu' oggi.
        assertTrue(departures("2026-10-01T22:00:00Z").isEmpty())
    }

    @Test
    fun ilBitDelGiornoDeiServiziAttiviAdOggi() {
        trip(1, 1, 3, 1, stop = 1, minute = 545) // servizio del 2 ottobre
        assertTrue(departures("2026-10-01T06:00:00Z").isEmpty())
        assertEquals(1, departures("2026-10-02T06:00:00Z").size)
    }

    @Test
    fun ilServizioDiIeriOltreMezzanotte() {
        // 00:10 dell'1 ottobre a Riga = 21:10Z del 30 settembre. Il servizio del 30 settembre parte alle 24:20.
        trip(1, 1, 2, 1, stop = 1, minute = 1460)
        trip(2, 1, 2, 1, stop = 1, minute = 1445) // 00:05, gia' partito
        val items = departures("2026-09-30T21:10:00Z")
        assertEquals(1, items.size)
        assertEquals(10, items[0].inMinutes)
        assertEquals(20, items[0].minuteOfDay) // 00:20 sull'orologio
    }

    @Test
    fun laFinestraAttraversaLaMezzanotteFinoAlServizioDiDomani() {
        // 23:30 dell'1 ottobre a Riga = 20:30Z: la partenza delle 00:10 del 2 ottobre e' del servizio di domani.
        trip(1, 1, 3, 1, stop = 1, minute = 10)
        trip(2, 1, 1, 1, stop = 1, minute = 1420) // 23:40 di oggi
        val items = departures("2026-10-01T20:30:00Z")
        assertEquals(listOf(10, 40), items.map { it.inMinutes })
        assertEquals(listOf(1420, 10), items.map { it.minuteOfDay })
    }

    @Test
    fun banchineEStazioneSiRaggruppanoPerParent() {
        // Il punto e' a 55 m dalla stazione (10), a 33 m dalla fermata 2 (parent 10): la banchina 3, a 200 m ma della stessa
        // stazione, entra; la fermata 4, lontana e senza parent, no.
        trip(1, 1, 1, 1, stop = 3, minute = 545)
        trip(2, 1, 1, 1, stop = 4, minute = 546)
        trip(3, 1, 1, 1, stop = 10, minute = 547)
        assertEquals(listOf(5, 7), departures("2026-10-01T06:00:00Z").map { it.inMinutes })
    }

    @Test
    fun unaStazioneGrandePrendeLaFermataPiuVicinaDelSuoMezzoEntro400Metri() {
        // Punto 230 m a sud della fermata 1 (come Riga Centrale e la fermata "Riga"): niente entro 150 m.
        val south = lat - 230 / 111_320.0
        val now = at("2026-10-01T06:00:00Z")
        trip(1, 1, 1, 1, stop = 1, minute = 545) // bus
        trip(2, 3, 1, 1, stop = 2, minute = 546) // metro, alla banchina 2 della stazione 10 (263 m)
        trip(3, 1, 1, 1, stop = 3, minute = 547) // bus, alla banchina 3 della stazione 10 (430 m)
        assertNull(readFeedBoard(query, feed, south, lon, now))
        // Un'autostazione: la fermata 1 dei bus, la piu' vicina, senza la stazione 10.
        val bus = readFeedBoard(query, feed, south, lon, now, setOf(TransitMode.BUS)) as TransitBoard.Departures
        assertEquals(listOf(5), bus.items.map { it.inMinutes })
        // Una stazione della metro: la banchina 2, piu' lontana ma della metro, con tutta la stazione 10.
        val metro = readFeedBoard(query, feed, south, lon, now, setOf(TransitMode.TRAIN, TransitMode.METRO)) as TransitBoard.Departures
        assertEquals(listOf(6, 7), metro.items.map { it.inMinutes })
        // Nessuna fermata del mezzo entro 400 m, o nessuna fermata: come un punto qualsiasi.
        assertNull(readFeedBoard(query, feed, south, lon, now, setOf(TransitMode.FERRY)))
        assertNull(readFeedBoard(query, feed, lat - 410 / 111_320.0, lon, now, setOf(TransitMode.BUS)))
        // Con fermate entro 150 m il gruppo e' quello di sempre, di qualsiasi mezzo.
        assertEquals(listOf(5, 6, 7), (readFeedBoard(query, feed, lat, lon, now, setOf(TransitMode.FERRY)) as TransitBoard.Departures).items.map { it.inMinutes })
    }

    @Test
    fun nessunaFermataVicinaDaNull() {
        trip(1, 1, 1, 1, stop = 4, minute = 545)
        assertNull(readFeedBoard(query, feed, 57.5, 25.0, at("2026-10-01T06:00:00Z")))
        assertEquals(TransitBoard.NoStops, combineBoards(emptyList()))
    }

    @Test
    fun oreScadutiOPrimaDellaFinestra() {
        trip(1, 1, 1, 1, stop = 1, minute = 545)
        val expired = LocalDate.of(2026, 10, 28)
        assertEquals(TransitBoard.Expired(expired, listOf(feed.copy(dataDate = LocalDate.of(2026, 9, 29)))), board("2026-11-05T06:00:00Z"))
        assertEquals(TransitBoard.Expired(expired, listOf(feed.copy(dataDate = LocalDate.of(2026, 9, 29)))), board("2026-09-01T06:00:00Z"))
        // L'ultimo giorno valido c'e' ancora, e i giorni che restano sono zero.
        val last = board("2026-10-28T06:00:00Z") as TransitBoard.Departures
        assertEquals(0, last.daysLeft)
        assertTrue(last.expiresSoon)
    }

    @Test
    fun scadutaDaPocoLePartenzeSiStimanoDallaSettimanaPrima() {
        trip(1, 1, 2, 1, stop = 1, minute = 545) // servizio del 30 settembre, 09:05
        exec("UPDATE meta SET value = '20261005' WHERE key = 'valid_until'")
        // 7 ottobre, due giorni dopo la scadenza: le partenze sono quelle del 30 settembre, stimate.
        val board = board("2026-10-07T06:00:00Z") as TransitBoard.Departures
        assertEquals(listOf(5), board.items.map { it.inMinutes })
        assertTrue(board.items.single().estimated)
        assertEquals(listOf(TransitBoard.Expired(LocalDate.of(2026, 10, 5), board.feeds, estimated = true)), board.expired)
        assertFalse(board.expiresSoon)
        // Una rete valida accanto: la scadenza del tabellone e' la sua, la rete stimata resta tra le scadute.
        val valid = TransitBoard.Departures(listOf(dep(20)), LocalDate.of(2026, 12, 1), 55, emptyList())
        val combined = combineBoards(listOf(board, valid)) as TransitBoard.Departures
        assertEquals(listOf(5, 20), combined.items.map { it.inMinutes })
        assertEquals(LocalDate.of(2026, 12, 1), combined.validUntil)
        assertEquals(board.expired, combined.expired)
        // Oltre i tre giorni: scaduta, nessuna partenza.
        assertTrue(board("2026-10-09T06:00:00Z") is TransitBoard.Expired)
        // Prima della scadenza le partenze non sono stimate.
        assertFalse(departures("2026-09-30T06:00:00Z").single().estimated)
    }

    @Test
    fun laFinestraCheInizieDomaniNonEScaduta() {
        // Fuso della rete avanti rispetto a chi ha costruito: window_start (29 settembre) e' domani. Alle 23:30 del
        // 28 settembre a Riga (20:30Z) oggi non c'e' servizio, ma la partenza delle 00:10 del 29 si vede.
        service(4, byteArrayOf(0b0000_0001))
        trip(1, 1, 4, 1, stop = 1, minute = 10)
        trip(2, 1, 4, 1, stop = 1, minute = 1420)
        val board = board("2026-09-28T20:30:00Z") as TransitBoard.Departures
        assertEquals(listOf(40), board.items.map { it.inMinutes })
        // Da piu' di un giorno prima dell'inizio resta Expired.
        assertTrue(board("2026-09-27T20:30:00Z") is TransitBoard.Expired)
    }

    @Test
    fun colonneDellaLinea() {
        trip(1, 1, 1, 1, stop = 1, minute = 545) // 22, bus, rosso senza colore del testo
        trip(2, 2, 1, null, stop = 1, minute = 546) // solo long_name, tram esteso 900, senza colore
        trip(3, 3, 1, 1, stop = 1, minute = 547) // metro, bianco con testo nero
        val (bus, tram, metro) = departures("2026-10-01T06:00:00Z")
        assertEquals("22", bus.line)
        assertEquals(TransitMode.BUS, bus.mode)
        assertEquals(0xFFFF0000.toInt(), bus.color)
        assertEquals(0xFFFFFFFF.toInt(), bus.textColor)
        assertEquals("Linea lunga", tram.line)
        assertEquals(TransitMode.TRAM, tram.mode)
        assertNull(tram.headsign)
        assertNull(tram.color)
        assertNull(tram.textColor)
        assertEquals(TransitMode.METRO, metro.mode)
        assertEquals(0xFF000000.toInt(), metro.textColor)
    }

    @Test
    fun accessibilitaDiFermateECorseSoloSeIlFileLaHa() {
        trip(1, 1, 1, 1, stop = 1, minute = 545)
        // transit.db senza le colonne wheelchair (meta senza la chiave): niente da leggere, e nessun errore.
        val old = board("2026-10-01T06:00:00Z") as TransitBoard.Departures
        assertNull(old.stopWheelchair)
        assertNull(old.items.single().wheelchair)

        exec(
            "ALTER TABLE stop ADD COLUMN wheelchair INTEGER",
            "ALTER TABLE trip ADD COLUMN wheelchair INTEGER",
            "INSERT INTO meta VALUES ('wheelchair','1')",
            "UPDATE trip SET wheelchair = 1 WHERE id = 1",
        )
        trip(2, 1, 1, 1, stop = 1, minute = 546)
        exec("UPDATE trip SET wheelchair = 2 WHERE id = 2")
        trip(3, 1, 1, 1, stop = 1, minute = 547) // NULL: non indicato
        val known = board("2026-10-01T06:00:00Z") as TransitBoard.Departures
        assertEquals(listOf(true, false, null), known.items.map { it.wheelchair })
        // Nessuna fermata del gruppo indica l'accessibilita'.
        assertNull(known.stopWheelchair)

        // Una banchina inaccessibile e la stazione accessibile: il gruppo ha almeno una fermata accessibile.
        exec("UPDATE stop SET wheelchair = 2 WHERE id = 1")
        assertEquals(false, (board("2026-10-01T06:00:00Z") as TransitBoard.Departures).stopWheelchair)
        exec("UPDATE stop SET wheelchair = 1 WHERE id = 10")
        assertEquals(true, (board("2026-10-01T06:00:00Z") as TransitBoard.Departures).stopWheelchair)
    }

    @Test
    fun laViaDelleReti() {
        val soon = TransitBoard.Departures(listOf(dep(20), dep(5)), LocalDate.of(2026, 10, 3), 2, listOf(feed))
        val late = TransitBoard.Departures(List(12) { dep(it) }, LocalDate.of(2026, 12, 1), 61, emptyList())
        val old = TransitBoard.Expired(LocalDate.of(2026, 9, 1), listOf(feed))
        val combined = combineBoards(listOf(soon, late, old)) as TransitBoard.Departures
        assertEquals(10, combined.items.size)
        // Una rete scaduta accanto a reti valide: le partenze delle altre restano, e il tabellone sa quale manca.
        assertEquals(listOf(old), combined.expired)
        assertEquals(emptyList<TransitBoard.Expired>(), (combineBoards(listOf(soon, late)) as TransitBoard.Departures).expired)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 5, 6, 7, 8), combined.items.map { it.inMinutes })
        assertEquals(LocalDate.of(2026, 10, 3), combined.validUntil)
        assertTrue(combined.expiresSoon)
        // Fermate: basta una rete che dice si' per dire si'; un no vale solo se nessuna dice si'.
        val no = soon.copy(stopWheelchair = false)
        val yes = late.copy(stopWheelchair = true)
        assertNull((combineBoards(listOf(soon, late)) as TransitBoard.Departures).stopWheelchair)
        assertEquals(false, (combineBoards(listOf(no, late)) as TransitBoard.Departures).stopWheelchair)
        assertEquals(true, (combineBoards(listOf(no, yes)) as TransitBoard.Departures).stopWheelchair)
        assertEquals(wheelchairOf("1"), true)
        assertEquals(wheelchairOf("2"), false)
        assertNull(wheelchairOf("0"))
        // Tutte scadute: la data piu' recente.
        val expired = combineBoards(listOf(TransitBoard.Expired(LocalDate.of(2026, 9, 1), emptyList()), TransitBoard.Expired(LocalDate.of(2026, 9, 9), emptyList())))
        assertEquals(LocalDate.of(2026, 9, 9), (expired as TransitBoard.Expired).validUntil)
    }

    private fun dep(inMinutes: Int) = TransitDeparture("1", TransitMode.BUS, null, null, null, inMinutes, inMinutes)

    @Test
    fun bitDeiGiorniDelServizio() {
        val days = byteArrayOf(0x01, 0x80.toByte())
        assertTrue(isServiceActive(days, 0))
        assertFalse(isServiceActive(days, 1))
        assertTrue(isServiceActive(days, 15))
        assertFalse(isServiceActive(days, 16)) // oltre la maschera
        assertFalse(isServiceActive(days, -1))
        assertFalse(isServiceActive(days, 15, windowDays = 10)) // oltre window_days
    }

    @Test
    fun tipiDiMezzoGtfsBaseEdEstesi() {
        assertEquals(TransitMode.TRAM, transitModeOf(0))
        assertEquals(TransitMode.METRO, transitModeOf(1))
        assertEquals(TransitMode.TRAIN, transitModeOf(2))
        assertEquals(TransitMode.BUS, transitModeOf(3))
        assertEquals(TransitMode.FERRY, transitModeOf(4))
        assertEquals(TransitMode.TROLLEYBUS, transitModeOf(11))
        assertEquals(TransitMode.TRAIN, transitModeOf(109))
        assertEquals(TransitMode.BUS, transitModeOf(204))
        assertEquals(TransitMode.METRO, transitModeOf(401))
        assertEquals(TransitMode.BUS, transitModeOf(715))
        assertEquals(TransitMode.TRAM, transitModeOf(900))
        assertEquals(TransitMode.FERRY, transitModeOf(1000))
        assertEquals(TransitMode.OTHER, transitModeOf(1700))
    }

    @Test
    fun coloriGtfs() {
        assertEquals(0xFF00AA11.toInt(), parseGtfsColor("00AA11"))
        assertEquals(0xFF00AA11.toInt(), parseGtfsColor("#00aa11"))
        assertNull(parseGtfsColor(""))
        assertNull(parseGtfsColor("12345"))
        assertNull(parseGtfsColor("zzzzzz"))
        assertNull(parseGtfsColor(null))
    }

    @Test
    fun informazioniDelleReti() {
        val feeds = listOf(feed, TransitFeedInfo("mdb-2", "Milano", "ATM", "https://example.org/licenza"))
        assertEquals(feeds, TransitFeedInfo.decode(TransitFeedInfo.encode(feeds)))
    }
}
