package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class AddressSearchTest {

    // ---- streetKey ----

    @Test
    fun `streetKey toglie maiuscole, accenti e punteggiatura`() {
        assertEquals("brivibas iela", streetKey("Brīvības iela"))
        assertEquals("rue de rivoli", streetKey("  Rue de  Rivoli "))
        assertEquals("via xx settembre", streetKey("Via XX-Settembre"))
        assertEquals("d artagnan", streetKey("D'Artagnan"))
        // Le lettere che NFD non scompone (ł, ß) restano com'e': la pipeline fa lo stesso.
        assertEquals("łodz", streetKey("Łódź"))
        assertEquals("straße 5", streetKey("Straße (5)"))
        assertEquals("", streetKey(" -- "))
    }

    @Test
    fun `streetKey usa Locale ROOT, la i turca non cambia`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertEquals("istanbul", streetKey("ISTANBUL"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // ---- parseAddressQuery ----

    @Test
    fun `civico in fondo`() {
        assertEquals(AddressQuery("Brīvības iela", "12"), parseAddressQuery("Brīvības iela 12"))
        assertEquals(AddressQuery("Via Roma", "12a"), parseAddressQuery("Via Roma 12a"))
        assertEquals(AddressQuery("Via Roma", "12/3"), parseAddressQuery(" Via Roma   12/3 "))
        assertEquals(AddressQuery("Calle Mayor", "12-14"), parseAddressQuery("Calle Mayor 12-14"))
    }

    @Test
    fun `civico all inizio`() {
        assertEquals(AddressQuery("Rue de Rivoli", "12"), parseAddressQuery("12 Rue de Rivoli"))
        assertEquals(AddressQuery("Rue de Rivoli", "12b"), parseAddressQuery("12b Rue de Rivoli"))
    }

    @Test
    fun `senza civico si cerca la via`() {
        assertEquals(AddressQuery("Via Roma", null), parseAddressQuery("Via Roma"))
        assertEquals(AddressQuery("Via Roma", null), parseAddressQuery("  Via   Roma  "))
        assertEquals(AddressQuery("Via 20 Settembre", null), parseAddressQuery("Via 20 Settembre"))
    }

    @Test
    fun `una sola parola non e un civico`() {
        assertEquals(AddressQuery("12", null), parseAddressQuery("12"))
        assertEquals(AddressQuery("", null), parseAddressQuery("   "))
    }

    @Test
    fun `l ultima parola vince sulla prima`() {
        assertEquals(AddressQuery("12 Via 5", "7"), parseAddressQuery("12 Via 5 7"))
    }

    @Test
    fun `parole con cifre che non sono civici restano nella via`() {
        assertEquals(AddressQuery("Via Roma 12abc", null), parseAddressQuery("Via Roma 12abc"))
        assertEquals(AddressQuery("A4 Autostrada", null), parseAddressQuery("A4 Autostrada"))
    }

    // ---- searchAddressDb ----

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

    private fun search(text: String, limit: Int = 20) = searchAddressDb(runner, origin, "lettonia", parseAddressQuery(text), limit)

    @Test
    fun `il formato 2 e supportato con la sua origine, un altro no`() {
        val read = readAddressOrigin(runner)!!
        assertEquals(origin.latE6, read.latE6)
        assertEquals(origin.lonE6, read.lonE6)
        exec("UPDATE meta SET value = '1' WHERE key = 'format'")
        assertNull(readAddressOrigin(runner))
    }

    @Test
    fun `senza l origine nella meta il database non e supportato`() {
        exec("DELETE FROM meta WHERE key = 'origin_lat_e6'")
        assertNull(readAddressOrigin(runner))
    }

    @Test
    fun `indirizzo esatto, senza badare a maiuscole e accenti`() {
        address("Brīvības iela", "12", "Rīga", 56.95, 24.11)
        address("Brīvības iela", "14", "Rīga", 56.96, 24.12)
        val found = search("brivibas IELA 12")
        assertEquals(1, found.size)
        assertEquals(AddressResult("lettonia", "Brīvības iela", "12", "Rīga", 56.95, 24.11), found.single())
        assertEquals("Brīvības iela 12, Rīga", found.single().displayName)
    }

    @Test
    fun `il civico con lettera non conta le maiuscole e non confonde 12 con 12a`() {
        address("Via Roma", "12", "Torino", 45.0, 7.0)
        address("Via Roma", "12A", "Torino", 45.1, 7.1)
        assertEquals("12A", search("via roma 12a").single().number)
        assertEquals("12", search("via roma 12").single().number)
    }

    @Test
    fun `civico all inizio e parola nel mezzo del nome della via`() {
        address("Rue de Rivoli", "12", "Paris", 48.86, 2.34)
        address("Rue de Rivoli", "12", "Paris", 48.86, 2.34) // doppione
        address("Impasse Rivoli", "12", "Lyon", 45.7, 4.8)
        // "rivoli" e' una parola, non l'inizio, di entrambe le vie; il doppione conta una volta.
        val found = search("12 Rivoli")
        assertEquals(listOf("Impasse Rivoli", "Rue de Rivoli"), found.map { it.street }.sorted())
        assertEquals("Impasse Rivoli", search("12 Impasse").single().street)
    }

    @Test
    fun `chi inizia per la chiave precede chi la contiene come parola`() {
        address("Rue de Rivoli", "1", "Paris", 1.0, 1.0)
        address("Rivoli Avenue", "1", "Paris", 2.0, 2.0)
        assertEquals(listOf("Rivoli Avenue", "Rue de Rivoli"), search("Rivoli 1").map { it.street })
    }

    @Test
    fun `una via che inizia e contiene la chiave come parola compare una volta sola`() {
        address("Rivoli Rue Rivoli", "1", "Paris", 1.0, 1.0)
        address("Rue de Rivoli", "1", "Paris", 2.0, 2.0)
        assertEquals(listOf("Rivoli Rue Rivoli", "Rue de Rivoli"), search("Rivoli 1").map { it.street })
    }

    @Test
    fun `una parte di parola nel mezzo non basta`() {
        address("Via Gioberti", "3", "Torino", 1.0, 1.0)
        assertTrue(search("berti 3").isEmpty())
    }

    @Test
    fun `lo stesso civico in citta diverse, o senza citta, resta distinto`() {
        address("Main Street", "1", "A", 1.0, 1.0)
        address("Main Street", "1", "B", 2.0, 2.0)
        address("Main Street", "1", null, 3.0, 3.0)
        assertEquals(3, search("main street 1").size)
    }

    @Test
    fun `il limite vale`() {
        (1..10).forEach { address("Via Lunga $it", "5", "X", it.toDouble(), it.toDouble()) }
        assertEquals(4, search("via lunga 5", limit = 4).size)
    }

    @Test
    fun `senza civico una voce per via e citta, sull indirizzo piu vicino alla mediana`() {
        // Il risultato e' un civico vero della via (il piu' vicino alla mediana), non un punto calcolato.
        address("Via Roma", "1", "Torino", 45.0, 7.0)
        address("Via Roma", "2", "Torino", 45.0, 7.1)
        address("Via Roma", "3", "Torino", 45.1, 7.1)
        address("Via Roma", "4", "Torino", 45.1, 7.1)
        address("Via Roma", "5", "Torino", 45.2, 7.2)
        address("Via Roma", "9", "Moncalieri", 44.9, 7.7)
        val found = search("via roma")
        assertEquals(2, found.size)
        val torino = found.single { it.city == "Torino" }
        assertNull(torino.number)
        assertEquals(45.1 to 7.1, torino.latitude to torino.longitude)
        assertEquals("Via Roma, Torino", torino.displayName)
        assertEquals(44.9 to 7.7, found.single { it.city == "Moncalieri" }.let { it.latitude to it.longitude })
    }

    @Test
    fun `senza risultati e con via vuota`() {
        address("Via Roma", "1", "Torino", 1.0, 1.0)
        assertTrue(search("via milano 1").isEmpty())
        assertTrue(search("via roma 2").isEmpty())
        assertTrue(search("  ").isEmpty())
    }
}
