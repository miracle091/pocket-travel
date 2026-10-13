package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Chiavi di ricerca, civici e query oltre AddressSearchTest: scritture insolite, caratteri speciali, mediane, formato. */
class AddressSearchEdgeCasesTest {

    private lateinit var connection: Connection
    private val origin = AddressOrigin(latE6 = 1_000_000, lonE6 = -5_000_000)

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec(
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID",
            "INSERT INTO meta VALUES ('format', '2'), ('origin_lat_e6', '${origin.latE6}'), ('origin_lon_e6', '${origin.lonE6}')",
            "CREATE TABLE street (id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, city TEXT)",
            "CREATE INDEX street_key ON street(key)",
            "CREATE TABLE address (street_id INTEGER NOT NULL, number TEXT NOT NULL COLLATE NOCASE, dlat INTEGER NOT NULL, dlon INTEGER NOT NULL, PRIMARY KEY(street_id, number, dlat, dlon)) WITHOUT ROWID",
        )
    }

    @After
    fun tearDown() = connection.close()

    private fun exec(vararg sql: String) = connection.createStatement().use { s -> sql.forEach { s.execute(it) } }

    private fun streetId(street: String, city: String?): Long {
        connection.prepareStatement("SELECT id FROM street WHERE name = ? AND city IS ?").use {
            it.setString(1, street); it.setString(2, city)
            it.executeQuery().use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        connection.prepareStatement("INSERT INTO street(name, key, city) VALUES (?, ?, ?)").use {
            it.setString(1, street); it.setString(2, streetKey(street)); it.setString(3, city)
            it.execute()
        }
        return streetId(street, city)
    }

    private fun address(street: String, number: String, city: String?, lat: Double, lon: Double) {
        connection.prepareStatement("INSERT OR IGNORE INTO address VALUES (?, ?, ?, ?)").use {
            it.setLong(1, streetId(street, city)); it.setString(2, number)
            it.setLong(3, Math.round(lat * 1e6) - origin.latE6); it.setLong(4, Math.round(lon * 1e6) - origin.lonE6)
            it.execute()
        }
    }

    private val runner = object : AddressQueryRunner {
        override fun <T> query(sql: String, args: List<String>, read: (AddressRow) -> T): List<T> {
            val result = mutableListOf<T>()
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, arg -> statement.setString(i + 1, arg) }
                statement.executeQuery().use { rs ->
                    val row = object : AddressRow {
                        override fun string(column: Int): String? = rs.getString(column + 1)
                        override fun long(column: Int) = rs.getLong(column + 1)
                    }
                    while (rs.next()) result += read(row)
                }
            }
            return result
        }
    }

    private fun search(text: String, limit: Int = 20) = searchAddressDb(runner, origin, "r", parseAddressQuery(text), limit)

    private fun count(table: String) = connection.createStatement().use { s ->
        s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) }
    }

    // streetKey

    @Test
    fun `streetKey non distingue la forma composta da quella scomposta degli accenti`() {
        assertEquals(streetKey("café"), streetKey("café"))
        assertEquals("cafe", streetKey("café"))
    }

    @Test
    fun `streetKey tiene lettere non latine e cifre di altri sistemi, scarta simboli e trattini bassi`() {
        assertEquals("улица ленина 5", streetKey("Улица Ленина, 5"))
        assertEquals("中山路", streetKey("中山路"))
        assertEquals("via roma", streetKey("via_roma"))
        assertEquals("via roma", streetKey("via\tRoma\n"))
        assertEquals("a b", streetKey("a—b"))
        assertEquals("via roma", streetKey("Via 😀 Roma"))
    }

    @Test
    fun `streetKey riduce la I turca maiuscola con il punto a una i normale`() {
        assertEquals("istanbul", streetKey("İstanbul"))
    }

    @Test
    fun `streetKey e' idempotente`() {
        listOf("Brīvības iela", "  Rue de  Rivoli ", "Straße (5)", "Łódź", "").forEach {
            assertEquals(it, streetKey(it), streetKey(streetKey(it)))
        }
    }

    // parseAddressQuery

    @Test
    fun `civici composti con lettere e barre`() {
        assertEquals(AddressQuery("Via Roma", "12a/3b"), parseAddressQuery("Via Roma 12a/3b"))
        assertEquals(AddressQuery("Calle Mayor", "12-14a"), parseAddressQuery("Calle Mayor 12-14a"))
        assertEquals(AddressQuery("Via Roma", "7"), parseAddressQuery("Via\tRoma\n7"))
    }

    @Test
    fun `numeri malformati non sono civici`() {
        listOf("Via Roma 12/", "Via Roma -12", "Via Roma 12ab", "Via Roma 1/2/3", "Via Roma #12", "Via Roma n.12").forEach {
            assertNull(it, parseAddressQuery(it).number)
        }
    }

    @Test
    fun `due numeri soli danno il secondo come civico`() {
        assertEquals(AddressQuery("12", "7"), parseAddressQuery("12 7"))
    }

    @Test
    fun `senza parole la via e' vuota`() {
        assertEquals(AddressQuery("", null), parseAddressQuery(""))
        assertEquals(AddressQuery("", null), parseAddressQuery(" \t\n "))
    }

    // searchAddressDb

    @Test
    fun `i caratteri speciali del LIKE non allargano la ricerca`() {
        address("Via Roma", "1", "Torino", 45.0, 7.0)
        address("Via Milano", "1", "Torino", 45.1, 7.1)
        assertTrue(search("%").isEmpty())
        assertTrue(search("_").isEmpty())
        assertTrue(search("% 1").isEmpty())
        assertEquals(listOf("Via Roma"), search("via_roma 1").map { it.street })
        assertEquals(listOf("Via Roma"), search("via r%").map { it.street })
    }

    @Test
    fun `un testo con apici e SQL e' solo un testo`() {
        address("Via Roma", "1", "Torino", 45.0, 7.0)
        assertTrue(search("x'); DROP TABLE address;-- 1").isEmpty())
        assertTrue(search("' OR '1'='1").isEmpty())
        assertEquals(1, count("address"))
        assertEquals(1, count("street"))
    }

    @Test
    fun `coordinate negative e a sud dell'equatore`() {
        address("George Street", "1", "Sydney", -33.87, 151.21)
        val found = search("george street 1").single()
        assertEquals(-33.87, found.latitude, 1e-9)
        assertEquals(151.21, found.longitude, 1e-9)
        address("Rua Augusta", "1", "Lisboa", 38.71, -9.14)
        val west = search("rua augusta 1").single()
        assertEquals(38.71, west.latitude, 1e-9)
        assertEquals(-9.14, west.longitude, 1e-9)
    }

    @Test
    fun `con due indirizzi la mediana e' il secondo e vince quello piu' vicino`() {
        address("Via Doppia", "1", "X", 10.0, 10.0)
        address("Via Doppia", "2", "X", 20.0, 20.0)
        val found = search("via doppia").single()
        assertEquals(20.0, found.latitude, 1e-9)
        assertEquals(20.0, found.longitude, 1e-9)
    }

    @Test
    fun `una via senza indirizzi non da' risultati senza civico`() {
        exec("INSERT INTO street(name, key, city) VALUES ('Via Vuota', 'via vuota', 'X')")
        assertTrue(search("via vuota").isEmpty())
        assertTrue(search("via vuota 1").isEmpty())
    }

    @Test
    fun `una via con civico e senza il civico cercato non compare, anche con altre vie uguali`() {
        address("Via Roma", "1", "Torino", 45.0, 7.0)
        address("Via Roma", "2", "Asti", 44.9, 8.2)
        val found = search("via roma 2")
        assertEquals(listOf("Asti"), found.map { it.city })
    }

    @Test
    fun `il limite zero non da' risultati`() {
        address("Via Roma", "1", "Torino", 45.0, 7.0)
        assertTrue(search("via roma", limit = 0).isEmpty())
        assertTrue(search("via roma 1", limit = 0).isEmpty())
    }

    @Test
    fun `se il limite e' raggiunto dalle vie che iniziano per la chiave, quelle con la parola non entrano`() {
        address("Rivoli Avenue", "1", "Paris", 1.0, 1.0)
        address("Rue de Rivoli", "1", "Paris", 2.0, 2.0)
        assertEquals(listOf("Rivoli Avenue"), search("rivoli", limit = 1).map { it.street })
        assertEquals(listOf("Rivoli Avenue", "Rue de Rivoli"), search("rivoli", limit = 2).map { it.street })
    }

    @Test
    fun `il nome della via si restituisce com'e' scritto nel database`() {
        address("Brīvības iela", "12", "Rīga", 56.95, 24.11)
        assertEquals("Brīvības iela", search("BRIVIBAS 12").single().street)
    }

    @Test
    fun `una cifra sola cerca le vie che iniziano cosi'`() {
        address("12 de Octubre", "3", "Madrid", 40.4, -3.7)
        address("Calle 12", "3", "Madrid", 40.5, -3.7)
        assertEquals(listOf("12 de Octubre", "Calle 12"), search("12").map { it.street })
    }

    // AddressResult.displayName

    @Test
    fun `displayName omette le parti mancanti o vuote`() {
        assertEquals("Via Roma 12, Torino", AddressResult("r", "Via Roma", "12", "Torino", 0.0, 0.0).displayName)
        assertEquals("Via Roma 12", AddressResult("r", "Via Roma", "12", null, 0.0, 0.0).displayName)
        assertEquals("Via Roma 12", AddressResult("r", "Via Roma", "12", "  ", 0.0, 0.0).displayName)
        assertEquals("Via Roma, Torino", AddressResult("r", "Via Roma", null, "Torino", 0.0, 0.0).displayName)
        assertEquals("Via Roma", AddressResult("r", "Via Roma", null, "", 0.0, 0.0).displayName)
    }

    // readAddressOrigin

    @Test
    fun `una meta con valori non numerici non e' supportata`() {
        exec("UPDATE meta SET value = 'due' WHERE key = 'format'")
        assertNull(readAddressOrigin(runner))
        exec("UPDATE meta SET value = '2' WHERE key = 'format'", "UPDATE meta SET value = 'x' WHERE key = 'origin_lon_e6'")
        assertNull(readAddressOrigin(runner))
    }

    @Test
    fun `le chiavi in piu' nella meta non danno fastidio e il formato e' il 2`() {
        exec("INSERT INTO meta VALUES ('built', '2026-10-01')")
        val read = checkNotNull(readAddressOrigin(runner))
        assertEquals(origin.latE6, read.latE6)
        assertEquals(2, ADDRESS_SEARCH_DB_FORMAT)
    }

    @Test
    fun `senza la riga del formato il database non e' supportato`() {
        exec("DELETE FROM meta WHERE key = 'format'")
        assertNull(readAddressOrigin(runner))
    }
}
