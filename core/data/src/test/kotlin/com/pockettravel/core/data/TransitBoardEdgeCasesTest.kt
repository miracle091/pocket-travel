package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

/** Casi limite delle funzioni pure dei trasporti, della ricerca delle fermate (sqlite-jdbc) e dell'unione delle reti. */
class TransitBoardEdgeCasesTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute("CREATE TABLE stop(id INTEGER PRIMARY KEY, code TEXT, name TEXT, latE6 INTEGER, lonE6 INTEGER, parent INTEGER)")
        }
    }

    @After
    fun tearDown() = connection.close()

    private fun stop(id: Int, latE6: Long, lonE6: Long, parent: Int? = null) {
        connection.prepareStatement("INSERT INTO stop VALUES (?, NULL, NULL, ?, ?, ?)").use {
            it.setInt(1, id)
            it.setLong(2, latE6)
            it.setLong(3, lonE6)
            if (parent == null) it.setNull(4, java.sql.Types.INTEGER) else it.setInt(4, parent)
            it.execute()
        }
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

    // nearbyStopIds

    @Test
    fun `senza fermate nella tabella la lista e' vuota`() {
        assertEquals(emptyList<Long>(), nearbyStopIds(query, 45.0, 9.0))
    }

    @Test
    fun `una fermata a 100 metri conta, una a 200 no`() {
        stop(1, 45_000_900, 9_000_000)
        stop(2, 45_001_800, 9_000_000)

        assertEquals(listOf(1L), nearbyStopIds(query, 45.0, 9.0))
    }

    @Test
    fun `una fermata nel rettangolo ma oltre il raggio non conta`() {
        // A circa 162 m in diagonale: dentro il rettangolo di latitudine e longitudine, fuori dal raggio di 150 m.
        stop(1, 45_001_000, 9_001_500)

        assertEquals(emptyList<Long>(), nearbyStopIds(query, 45.0, 9.0))
    }

    @Test
    fun `la fermata vicina porta con se la stazione e tutte le banchine, in ordine di id`() {
        stop(5, 45_000_100, 9_000_000, parent = 20)
        stop(20, 45_007_000, 9_000_000)
        stop(21, 45_007_200, 9_000_000, parent = 20)
        stop(30, 45_007_000, 9_010_000)

        assertEquals(listOf(5L, 20L, 21L), nearbyStopIds(query, 45.0, 9.0))
    }

    @Test
    fun `una fermata vicina senza parent porta le banchine che la hanno come parent`() {
        stop(40, 45_000_000, 9_000_000)
        stop(41, 45_009_000, 9_000_000, parent = 40)

        assertEquals(listOf(40L, 41L), nearbyStopIds(query, 45.0, 9.0))
    }

    @Test
    fun `due fermate vicine della stessa stazione non ripetono gli id`() {
        stop(1, 45_000_100, 9_000_000, parent = 9)
        stop(2, 45_000_200, 9_000_000, parent = 9)
        stop(9, 45_000_150, 9_000_000)

        assertEquals(listOf(1L, 2L, 9L), nearbyStopIds(query, 45.0, 9.0))
    }

    @Test
    fun `coordinate negative e vicine ai poli non fanno fallire la ricerca`() {
        stop(1, -33_450_000, -70_600_000)
        stop(2, 89_990_000, 10_000_000)

        assertEquals(listOf(1L), nearbyStopIds(query, -33.45, -70.6))
        assertEquals(listOf(2L), nearbyStopIds(query, 89.99, 10.0))
        assertEquals(emptyList<Long>(), nearbyStopIds(query, 0.0, 0.0))
    }

    // transitModeOf

    @Test
    fun `i tipi di mezzo di base fra 5 e 12`() {
        assertEquals(TransitMode.CABLE, transitModeOf(5))
        assertEquals(TransitMode.CABLE, transitModeOf(6))
        assertEquals(TransitMode.CABLE, transitModeOf(7))
        assertEquals(TransitMode.OTHER, transitModeOf(8))
        assertEquals(TransitMode.OTHER, transitModeOf(10))
        assertEquals(TransitMode.MONORAIL, transitModeOf(12))
        assertEquals(TransitMode.OTHER, transitModeOf(13))
    }

    @Test
    fun `gli estremi delle centinaia dei tipi estesi`() {
        assertEquals(TransitMode.OTHER, transitModeOf(99))
        assertEquals(TransitMode.TRAIN, transitModeOf(100))
        assertEquals(TransitMode.TRAIN, transitModeOf(199))
        assertEquals(TransitMode.BUS, transitModeOf(200))
        assertEquals(TransitMode.BUS, transitModeOf(299))
        assertEquals(TransitMode.TRAIN, transitModeOf(300))
        assertEquals(TransitMode.METRO, transitModeOf(400))
        assertEquals(TransitMode.METRO, transitModeOf(499))
        assertEquals(TransitMode.METRO, transitModeOf(500))
        assertEquals(TransitMode.OTHER, transitModeOf(1600))
        assertEquals(TransitMode.BUS, transitModeOf(700))
        assertEquals(TransitMode.BUS, transitModeOf(799))
        assertEquals(TransitMode.TROLLEYBUS, transitModeOf(800))
        assertEquals(TransitMode.TROLLEYBUS, transitModeOf(899))
        assertEquals(TransitMode.TRAM, transitModeOf(999))
        assertEquals(TransitMode.FERRY, transitModeOf(1099))
        assertEquals(TransitMode.OTHER, transitModeOf(1100))
        assertEquals(TransitMode.CABLE, transitModeOf(1300))
        assertEquals(TransitMode.CABLE, transitModeOf(1499))
        assertEquals(TransitMode.OTHER, transitModeOf(1500))
    }

    @Test
    fun `i tipi estesi di traghetto, ferrovia suburbana e metropolitana`() {
        // 1200 e' "Ferry Service" nei tipi estesi di Google, 1000 e' il trasporto per acqua in generale.
        assertEquals(TransitMode.FERRY, transitModeOf(1200))
        assertEquals(TransitMode.FERRY, transitModeOf(1299))
        assertEquals(TransitMode.OTHER, transitModeOf(1199))
        assertEquals(TransitMode.TRAIN, transitModeOf(300))
        assertEquals(TransitMode.TRAIN, transitModeOf(399))
        assertEquals(TransitMode.METRO, transitModeOf(500))
        assertEquals(TransitMode.METRO, transitModeOf(699))
        // Aereo, taxi e vari non hanno un mezzo corrispondente.
        assertEquals(TransitMode.OTHER, transitModeOf(1100))
        assertEquals(TransitMode.OTHER, transitModeOf(1500))
        assertEquals(TransitMode.OTHER, transitModeOf(1700))
    }

    @Test
    fun `un tipo negativo o enorme vale altro`() {
        assertEquals(TransitMode.OTHER, transitModeOf(-1))
        assertEquals(TransitMode.OTHER, transitModeOf(Int.MAX_VALUE))
        assertEquals(TransitMode.OTHER, transitModeOf(Int.MIN_VALUE))
    }

    // colori

    @Test
    fun `il colore GTFS ignora gli spazi attorno e un solo cancelletto`() {
        assertEquals(0xFFFF0000.toInt(), parseGtfsColor("  ff0000  "))
        assertEquals(0xFFFF0000.toInt(), parseGtfsColor(" #FF0000"))
        assertEquals(0xFFFFFFFF.toInt(), parseGtfsColor("FFFFFF"))
        assertEquals(0xFF000000.toInt(), parseGtfsColor("000000"))
        assertNull(parseGtfsColor("#"))
        assertNull(parseGtfsColor("   "))
        assertNull(parseGtfsColor("##00aa11"))
        assertNull(parseGtfsColor("00aa112"))
        assertNull(parseGtfsColor("00 a11"))
    }

    @Test
    fun `il colore GTFS accetta solo sei cifre esadecimali, senza segno`() {
        assertNull(parseGtfsColor("-12345"))
        assertNull(parseGtfsColor("+12345"))
        assertNull(parseGtfsColor("#-12345"))
        assertNull(parseGtfsColor("12345g"))
        // Cifre non ASCII: toIntOrNull(16) le accetterebbe.
        assertNull(parseGtfsColor("١٢٣٤٥٦"))
        assertEquals(0xFF123456.toInt(), parseGtfsColor("123456"))
        assertEquals(0xFFABCDEF.toInt(), parseGtfsColor("#abCDef"))
    }

    @Test
    fun `il testo e' nero sui colori chiari e bianco sugli scuri`() {
        assertEquals(0xFF000000.toInt(), contrastingTextColor(0xFFFFFFFF.toInt()))
        assertEquals(0xFF000000.toInt(), contrastingTextColor(0xFFFFFF00.toInt()))
        assertEquals(0xFF000000.toInt(), contrastingTextColor(0xFFCCCCCC.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), contrastingTextColor(0xFF000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), contrastingTextColor(0xFFFF0000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), contrastingTextColor(0xFF000080.toInt()))
    }

    @Test
    fun `il contrasto non guarda la trasparenza`() {
        assertEquals(contrastingTextColor(0xFFFFFFFF.toInt()), contrastingTextColor(0x00FFFFFF))
        assertEquals(contrastingTextColor(0xFF102030.toInt()), contrastingTextColor(0x00102030))
    }

    // distanceMeters

    @Test
    fun `la distanza fra lo stesso punto e' zero e non dipende dal verso`() {
        assertEquals(0.0, distanceMeters(45.0, 9.0, 45.0, 9.0), 0.0)
        val ab = distanceMeters(45.0, 9.0, 45.001, 9.002)
        val ba = distanceMeters(45.001, 9.002, 45.0, 9.0)
        assertEquals(ab, ba, 1e-9)
    }

    @Test
    fun `un grado di latitudine e uno di longitudine all'equatore sono 111320 metri`() {
        assertEquals(111_320.0, distanceMeters(10.0, 20.0, 11.0, 20.0), 1e-6)
        assertEquals(111_320.0, distanceMeters(0.0, 20.0, 0.0, 21.0), 1e-6)
    }

    @Test
    fun `la longitudine si accorcia con la latitudine`() {
        // cos(60 gradi) = 0,5.
        assertEquals(55_660.0, distanceMeters(60.0, 10.0, 60.0, 11.0), 1e-3)
    }

    // isServiceActive

    @Test
    fun `una maschera vuota non ha mai servizio`() {
        assertFalse(isServiceActive(ByteArray(0), 0))
        assertFalse(isServiceActive(ByteArray(0), 0, windowDays = 10))
    }

    @Test
    fun `ogni bit della maschera e' un giorno`() {
        val days = byteArrayOf(0x55)
        val active = (0..7).filter { isServiceActive(days, it) }

        assertEquals(listOf(0, 2, 4, 6), active)
    }

    @Test
    fun `windowDays taglia la maschera e una finestra a zero non ha giorni`() {
        val all = byteArrayOf(0xFF.toByte())
        assertTrue(isServiceActive(all, 2, windowDays = 3))
        assertFalse(isServiceActive(all, 3, windowDays = 3))
        assertFalse(isServiceActive(all, 0, windowDays = 0))
    }

    @Test
    fun `una finestra piu' lunga della maschera non legge oltre i suoi byte`() {
        val all = byteArrayOf(0xFF.toByte())
        assertTrue(isServiceActive(all, 7, windowDays = 100))
        assertFalse(isServiceActive(all, 8, windowDays = 100))
    }

    @Test
    fun `l'accessibilita' si legge solo da 1 e 2`() {
        assertEquals(true, wheelchairOf("1"))
        assertEquals(false, wheelchairOf("2"))
        assertNull(wheelchairOf(null))
        assertNull(wheelchairOf(""))
        assertNull(wheelchairOf("3"))
        assertNull(wheelchairOf("true"))
        assertNull(wheelchairOf(" 1"))
    }

    // Departures.expiresSoon

    @Test
    fun `gli orari scadono presto da oggi a sette giorni`() {
        fun board(daysLeft: Int) = TransitBoard.Departures(emptyList(), LocalDate.of(2026, 10, 1), daysLeft, emptyList())

        assertTrue(board(0).expiresSoon)
        assertTrue(board(7).expiresSoon)
        assertFalse(board(8).expiresSoon)
        assertFalse(board(-1).expiresSoon)
        assertFalse(board(365).expiresSoon)
    }

    // combineBoards

    private fun dep(
        inMinutes: Int,
        line: String = "1",
        mode: TransitMode = TransitMode.BUS,
        headsign: String? = null,
    ) = TransitDeparture(line, mode, null, null, headsign, inMinutes, inMinutes)

    private val feed = TransitFeedInfo("mdb-1", "Riga", "Rigas satiksme (CC0 1.0)")
    private val other = TransitFeedInfo("mdb-2", "Milano", "ATM")

    private fun departures(
        items: List<TransitDeparture>,
        validUntil: LocalDate = LocalDate.of(2026, 12, 1),
        daysLeft: Int = 60,
        feeds: List<TransitFeedInfo> = emptyList(),
        expired: List<TransitBoard.Expired> = emptyList(),
    ) = TransitBoard.Departures(items, validUntil, daysLeft, feeds, expired = expired)

    @Test
    fun `nessuna rete o solo reti senza fermate vicine danno NoStops`() {
        assertEquals(TransitBoard.NoStops, combineBoards(emptyList()))
        assertEquals(TransitBoard.NoStops, combineBoards(listOf(TransitBoard.NoStops, TransitBoard.NoStops)))
    }

    @Test
    fun `NoStops accanto a una rete con partenze non toglie nulla`() {
        val combined = combineBoards(listOf(TransitBoard.NoStops, departures(listOf(dep(4)), feeds = listOf(feed))))

        assertEquals(listOf(4), (combined as TransitBoard.Departures).items.map { it.inMinutes })
        assertEquals(listOf(feed), combined.feeds)
    }

    @Test
    fun `solo reti scadute e tutte stimate, anche la somma e' stimata`() {
        val first = TransitBoard.Expired(LocalDate.of(2026, 8, 1), listOf(feed), estimated = true)
        val second = TransitBoard.Expired(LocalDate.of(2026, 9, 1), listOf(other), estimated = true)

        assertTrue((combineBoards(listOf(first, second)) as TransitBoard.Expired).estimated)
        assertFalse((combineBoards(listOf(first, second.copy(estimated = false))) as TransitBoard.Expired).estimated)
    }

    @Test
    fun `solo reti scadute, le reti si sommano e la scadenza e' la piu' recente`() {
        val older = TransitBoard.Expired(LocalDate.of(2026, 8, 1), listOf(feed))
        val newer = TransitBoard.Expired(LocalDate.of(2026, 9, 1), listOf(other))

        val combined = combineBoards(listOf(older, newer)) as TransitBoard.Expired

        assertEquals(LocalDate.of(2026, 9, 1), combined.validUntil)
        assertEquals(listOf(feed, other), combined.feeds)
        assertFalse(combined.estimated)
    }

    @Test
    fun `le partenze successive della stessa linea sono al massimo due, le prossime in ordine di tempo`() {
        val combined = combineBoards(listOf(departures(listOf(dep(3), dep(1), dep(5), dep(2), dep(4))))) as TransitBoard.Departures

        assertEquals(listOf(1), combined.items.map { it.inMinutes })
        assertEquals(listOf(2, 3), combined.items.single().later.map { it.inMinutes })
        assertTrue(combined.items.single().later.all { it.later.isEmpty() })
    }

    @Test
    fun `mezzo o direzione diversi fanno righe diverse anche con lo stesso numero di linea`() {
        val combined = combineBoards(
            listOf(
                departures(
                    listOf(
                        dep(1, "1", TransitMode.TRAM),
                        dep(2, "1", TransitMode.BUS),
                        dep(3, "1", TransitMode.BUS, headsign = "Centro"),
                        dep(4, "1", TransitMode.BUS, headsign = "Centro"),
                    ),
                ),
            ),
        ) as TransitBoard.Departures

        assertEquals(listOf(1, 2, 3), combined.items.map { it.inMinutes })
        assertEquals(listOf(4), combined.items.last().later.map { it.inMinutes })
    }

    @Test
    fun `la stessa linea in due reti diventa una riga con le partenze di entrambe`() {
        val a = departures(listOf(dep(10), dep(30)), feeds = listOf(feed))
        val b = departures(listOf(dep(20)), feeds = listOf(other))

        val combined = combineBoards(listOf(a, b)) as TransitBoard.Departures

        assertEquals(listOf(10), combined.items.map { it.inMinutes })
        assertEquals(listOf(20, 30), combined.items.single().later.map { it.inMinutes })
        assertEquals(listOf(feed, other), combined.feeds)
    }

    @Test
    fun `la scadenza guarda le reti con orari veri e ripiega sulle stimate solo se non ce ne sono`() {
        val estimated = TransitBoard.Expired(LocalDate.of(2026, 9, 30), listOf(feed), estimated = true)
        val stale = departures(listOf(dep(1)), LocalDate.of(2026, 9, 30), -1, expired = listOf(estimated))
        val fresh = departures(listOf(dep(2, "2")), LocalDate.of(2026, 12, 1), 61)

        val mixed = combineBoards(listOf(stale, fresh)) as TransitBoard.Departures
        assertEquals(LocalDate.of(2026, 12, 1), mixed.validUntil)
        assertEquals(61, mixed.daysLeft)
        assertEquals(listOf(estimated), mixed.expired)

        val onlyStale = combineBoards(listOf(stale)) as TransitBoard.Departures
        assertEquals(LocalDate.of(2026, 9, 30), onlyStale.validUntil)
        assertEquals(-1, onlyStale.daysLeft)
    }

    @Test
    fun `la scadenza piu' vicina fra le reti valide e i giorni che restano`() {
        val soon = departures(listOf(dep(1)), LocalDate.of(2026, 10, 5), 4)
        val late = departures(listOf(dep(2, "2")), LocalDate.of(2026, 11, 5), 35)

        val combined = combineBoards(listOf(late, soon)) as TransitBoard.Departures

        assertEquals(LocalDate.of(2026, 10, 5), combined.validUntil)
        assertEquals(4, combined.daysLeft)
        assertTrue(combined.expiresSoon)
    }

    @Test
    fun `le reti scadute accanto alle valide restano in coda, dopo quelle gia' dentro le valide`() {
        val inside = TransitBoard.Expired(LocalDate.of(2026, 9, 30), listOf(feed), estimated = true)
        val alone = TransitBoard.Expired(LocalDate.of(2026, 8, 1), listOf(other))
        val active = departures(listOf(dep(1)), expired = listOf(inside))

        val combined = combineBoards(listOf(alone, active)) as TransitBoard.Departures

        assertEquals(listOf(inside, alone), combined.expired)
    }

    // TransitFeedInfo

    @Test
    fun `l'elenco delle reti vuoto fa il giro`() {
        assertEquals("[]", TransitFeedInfo.encode(emptyList()))
        assertEquals(emptyList<TransitFeedInfo>(), TransitFeedInfo.decode("[]"))
    }

    @Test
    fun `le chiavi in piu' si ignorano e la licenza e' facoltativa`() {
        val decoded = TransitFeedInfo.decode("""[{"id":"a","name":"A","attribution":"x","extra":1,"licenseUrl":"https://example.org/l"},{"id":"b","name":"B","attribution":"y"}]""")

        assertEquals(
            listOf(TransitFeedInfo("a", "A", "x", "https://example.org/l"), TransitFeedInfo("b", "B", "y")),
            decoded,
        )
    }

    @Test
    fun `testo non JSON o senza un campo obbligatorio e' un errore`() {
        assertThrows(IllegalArgumentException::class.java) { TransitFeedInfo.decode("non e' JSON") }
        assertThrows(IllegalArgumentException::class.java) { TransitFeedInfo.decode("""[{"id":"a","name":"A"}]""") }
        assertThrows(IllegalArgumentException::class.java) { TransitFeedInfo.decode("""{"id":"a"}""") }
    }

    @Test
    fun `caratteri non ASCII si conservano e la data degli orari non si salva`() {
        val unicodeFeed = TransitFeedInfo("riga", "Rīga — Rīgas satiksme", "© Rīgas satiksme \"CC0\"", dataDate = LocalDate.of(2026, 9, 29))

        val decoded = TransitFeedInfo.decode(TransitFeedInfo.encode(listOf(unicodeFeed))).single()

        assertEquals(unicodeFeed.copy(dataDate = null), decoded)
        assertNull(decoded.dataDate)
    }
}
