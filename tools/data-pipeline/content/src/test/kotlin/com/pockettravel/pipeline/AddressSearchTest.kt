package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class AddressSearchTest {

    @Test
    fun `streetKey toglie maiuscole, accenti e punteggiatura`() {
        assertEquals("via dell universita 3", streetKey("Via Dell'Univérsità, 3"))
        assertEquals("rue de l eglise", streetKey("  Rue de l’Église  "))
        // Nessuna trascrizione: la ß resta com'e'.
        assertEquals("straße des 17 juni", streetKey("Straße des 17. Juni"))
    }

    @Test
    fun `streetKey riduce ogni sequenza di separatori a un solo spazio e toglie quelli ai bordi`() {
        assertEquals("avenida 9 de julio", streetKey("--Avenida 9 de   Julio--"))
        assertEquals("a b c", streetKey("a/b\tc"))
        assertEquals("", streetKey(" - . "))
    }

    @Test
    fun `streetKey tiene lettere e cifre di altri alfabeti e toglie i segni combinanti`() {
        // Cirillico e CJK restano; la i breve cirillica (й) si scompone in и + segno e perde il segno.
        assertEquals("улица ленина", streetKey("Улица Ленина"))
        assertEquals("и", streetKey("й"))
        assertEquals("中山路 2", streetKey("中山路, 2"))
        // I (con punto sopra) minuscola con Locale.ROOT diventa "i" + punto combinante: il punto sparisce.
        assertEquals("istanbul", streetKey("İstanbul"))
    }

    private fun road(name: String, vararg points: Pair<Int, Int>) = Road(name, points.toList())

    // A lat 43.94 un grado di latitudine sono 110.540 m: 1 m = ~9 microgradi.
    private val roadsIndex = RoadIndex(
        listOf(
            // Segmento est-ovest a lat 43.940000, da lon 12.440000 a 12.450000 (~800 m).
            road("Via Ovest-Est", 43_940_000 to 12_440_000, 43_940_000 to 12_450_000),
            // Segmento nord-sud a lon 12.445000, a nord della prima (lat 43.941000..43.942000).
            road("Via Nord-Sud", 43_941_000 to 12_445_000, 43_942_000 to 12_445_000),
        ),
    )

    @Test
    fun `la via della fonte vince sulla strada piu' vicina`() {
        val address = Address(43_940_050, 12_445_000, "1", street = "Via della Fonte", city = "Serravalle")

        val result = assignStreets(listOf(address), roadsIndex)

        assertEquals(listOf(address), result)
    }

    @Test
    fun `senza via della fonte si prende la strada con nome piu' vicina entro 50 m`() {
        // ~22 m a nord di Via Ovest-Est (200 microgradi di latitudine = ~22 m).
        val near = Address(43_940_200, 12_443_000, "5")
        // ~44 m: dentro i 50 m, e piu' vicina a Via Ovest-Est che a nessun'altra.
        val edge = Address(43_940_400, 12_447_000, "7")

        val result = assignStreets(listOf(near, edge), roadsIndex)

        assertEquals(listOf("Via Ovest-Est", "Via Ovest-Est"), result.map { it.street })
    }

    @Test
    fun `oltre 50 m non si assegna nessuna via`() {
        // 600 microgradi di latitudine = ~66 m dalla strada piu' vicina.
        val far = Address(43_940_600, 12_443_000, "9")

        val result = assignStreets(listOf(far), roadsIndex)

        assertNull(result.single().street)
    }

    @Test
    fun `tra due strade vince la piu' vicina, anche oltre l'estremo di un segmento`() {
        val nearSecond = Address(43_940_900, 12_445_050, "3")
        // A est della fine del primo segmento (12.450000): la distanza e' quella dall'estremo, ~30 m.
        val pastEnd = Address(43_940_000, 12_450_400, "4")
        // Oltre l'estremo piu' di 50 m: niente.
        val tooFarPastEnd = Address(43_940_000, 12_450_900, "6")

        val result = assignStreets(listOf(nearSecond, pastEnd, tooFarPastEnd), roadsIndex)

        assertEquals(listOf("Via Nord-Sud", "Via Ovest-Est", null), result.map { it.street })
    }

    @Test
    fun `senza strade nessun civico prende una via`() {
        val result = assignStreets(listOf(Address(1, 1, "1"), Address(2, 2, "2", street = "Via X")), RoadIndex(emptyList()))

        assertEquals(listOf(null, "Via X"), result.map { it.street })
    }

    @Test
    fun `le strade del fixture di San Marino hanno un nome e cadono vicino ai civici`() {
        val fixture = File("testdata/san-marino-center-z15.pmtiles")
        val roads = extractRoads(fixture)
        assertTrue("attese strade con nome, trovate ${roads.size}", roads.size > 20)
        assertTrue(roads.all { it.name.isNotBlank() && it.points.size >= 2 })

        val addresses = extractAddresses(fixture, 12.44, 43.93, 12.46, 43.94)
        val withStreets = assignStreets(addresses, RoadIndex(roads))
        // Nel centro storico quasi ogni civico ha una strada con nome a pochi metri.
        assertTrue(withStreets.count { it.street != null } > addresses.size / 2)
        assertTrue(withStreets.filter { it.street != null }.all { it.street == it.street!!.trim() && it.street!!.isNotEmpty() })
    }

    private fun withDb(block: (File) -> Unit) {
        val db = File.createTempFile("pocket-travel-test", ".addresses-search.db")
        try {
            block(db)
        } finally {
            db.delete()
        }
    }

    private fun <T> query(db: File, sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val rows = mutableListOf<T>()
                    while (rs.next()) rows += read(rs)
                    rows
                }
            }
        }

    @Test
    fun `scrive schema, meta e righe solo per i civici con una via`() = withDb { db ->
        val addresses = listOf(
            Address(43_942_400, 12_457_800, "12", street = "Via dell'Università", city = "San Marino"),
            Address(43_942_500, 12_457_900, "3A", street = "Via Roma"),
            Address(43_942_600, 12_458_000, "5"),
            Address(43_942_700, 12_458_100, "6", street = "  "),
            Address(43_942_800, 12_458_200, "7", street = "- . -"),
        )

        val written = writeAddressSearchDb(addresses, db)

        assertEquals(2, written)
        // Vie in ordine di (key, name, city): "via dell universita" prima di "via roma", id da 1.
        val streets = query(db, "SELECT id, name, key, city FROM street ORDER BY id") {
            listOf(it.getInt(1), it.getString(2), it.getString(3), it.getString(4))
        }
        assertEquals(
            listOf(
                listOf(1, "Via dell'Università", "via dell universita", "San Marino"),
                listOf(2, "Via Roma", "via roma", null),
            ),
            streets,
        )
        // Origine = minimi di latE6/lonE6; le posizioni sono scarti non negativi da quella.
        val addressRows = query(db, "SELECT street_id, number, dlat, dlon FROM address ORDER BY street_id, number") {
            listOf(it.getInt(1), it.getString(2), it.getInt(3), it.getInt(4))
        }
        assertEquals(listOf(listOf(1, "12", 0, 0), listOf(2, "3A", 100, 100)), addressRows)
        assertEquals(
            mapOf("format" to "2", "origin_lat_e6" to "43942400", "origin_lon_e6" to "12457800"),
            query(db, "SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap(),
        )
        assertEquals(
            listOf("street_key"),
            query(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'street' AND name NOT LIKE 'sqlite_%'") { it.getString(1) },
        )
        // La ricerca per via usa l'indice street_key e poi la chiave primaria di address, senza scorrere le tabelle.
        val streetPlan = query(db, "EXPLAIN QUERY PLAN SELECT id FROM street WHERE key = 'via roma'") { it.getString(4) }
        assertTrue(streetPlan.toString(), streetPlan.any { it.contains("street_key") })
        val addressPlan = query(db, "EXPLAIN QUERY PLAN SELECT dlat, dlon FROM address WHERE street_id = 2 AND number = '3a'") { it.getString(4) }
        assertTrue(addressPlan.toString(), addressPlan.none { it.startsWith("SCAN") })
        // Il civico si confronta senza badare alle maiuscole (NOCASE).
        assertEquals(listOf(1), query(db, "SELECT count(*) FROM address WHERE street_id = 2 AND number = '3a'") { it.getInt(1) })
        // Giornale di rollback (non WAL) e pagine da 1 KiB: l'app apre il file in sola lettura.
        assertEquals(listOf("delete"), query(db, "PRAGMA journal_mode") { it.getString(1) })
        assertEquals(listOf(1024), query(db, "PRAGMA page_size") { it.getInt(1) })
    }

    @Test
    fun `una riga per via e citta' distinte e un solo civico per posizione`() = withDb { db ->
        val addresses = listOf(
            Address(43_942_400, 12_457_800, "7", street = "Via Roma", city = "A"),
            Address(43_942_900, 12_457_900, "7", street = "Via Roma", city = "B"),
            // Stessa via, civico (senza badare alle maiuscole) e posizione: duplicato.
            Address(43_942_400, 12_457_800, "7a", street = "Via Roma", city = "A"),
            Address(43_942_400, 12_457_800, "7A", street = "Via Roma", city = "A"),
        )

        val written = writeAddressSearchDb(addresses, db)

        assertEquals(3, written)
        assertEquals(listOf(listOf(1, "A"), listOf(2, "B")), query(db, "SELECT id, city FROM street ORDER BY id") { listOf(it.getInt(1), it.getString(2)) })
        // Dei due duplicati resta il primo in ordine di byte: "7A" < "7a".
        assertEquals(listOf("7", "7A"), query(db, "SELECT number FROM address WHERE street_id = 1 ORDER BY number") { it.getString(1) })
    }

    @Test
    fun `lo stesso contenuto da' gli stessi byte e il file e' sostituito, non accodato`() = withDb { db ->
        val addresses = listOf(
            Address(43_942_500, 12_457_900, "3A", street = "Via Roma"),
            Address(43_942_400, 12_457_800, "12", street = "Via Roma"),
        )

        writeAddressSearchDb(addresses, db)
        val first = db.readBytes()
        writeAddressSearchDb(addresses.reversed(), db)

        assertTrue("stessi byte a prescindere dall'ordine di ingresso", first.contentEquals(db.readBytes()))
        assertEquals(listOf(2), query(db, "SELECT count(*) FROM address") { it.getInt(1) })
    }
}
