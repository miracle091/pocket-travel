package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

/** Unione dei database di ricerca per cella (formato 2) in quello unico della regione, letto poi con searchAddressDb. */
class AddressSearchMergerTest {

    private val dir = createTempDirectory("pocket-travel-address-merge-test").toFile()
    private val connections = mutableListOf<Connection>()

    @After
    fun tearDown() {
        connections.forEach { it.close() }
        dir.deleteRecursively()
    }

    private class Addr(val street: String, val city: String?, val number: String, val lat: Double, val lon: Double)

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}").also { connections += it }

    private fun exec(connection: Connection, vararg sql: String) = connection.createStatement().use { s -> sql.forEach { s.execute(it) } }

    /** Una cella come la pubblica la pipeline: origine propria e id di via che partono da [firstStreetId]. */
    private fun cell(name: String, originLatE6: Long, originLonE6: Long, firstStreetId: Long, vararg addresses: Addr): File {
        val file = File(dir, name)
        val connection = connect(file)
        exec(
            connection,
            "PRAGMA page_size=1024",
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID",
            "INSERT INTO meta VALUES ('format', '2'), ('origin_lat_e6', '$originLatE6'), ('origin_lon_e6', '$originLonE6')",
            "CREATE TABLE street (id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, city TEXT)",
            "CREATE INDEX street_key ON street(key)",
            "CREATE TABLE address (street_id INTEGER NOT NULL, number TEXT NOT NULL COLLATE NOCASE, dlat INTEGER NOT NULL, dlon INTEGER NOT NULL, PRIMARY KEY(street_id, number, dlat, dlon)) WITHOUT ROWID",
        )
        val ids = LinkedHashMap<Pair<String, String?>, Long>()
        addresses.forEach { a ->
            val id = ids.getOrPut(a.street to a.city) {
                (firstStreetId + ids.size).also { id ->
                    connection.prepareStatement("INSERT INTO street VALUES (?, ?, ?, ?)").use {
                        it.setLong(1, id); it.setString(2, a.street); it.setString(3, streetKey(a.street)); it.setString(4, a.city)
                        it.execute()
                    }
                }
            }
            connection.prepareStatement("INSERT INTO address VALUES (?, ?, ?, ?)").use {
                it.setLong(1, id); it.setString(2, a.number)
                it.setLong(3, Math.round(a.lat * 1e6) - originLatE6); it.setLong(4, Math.round(a.lon * 1e6) - originLonE6)
                it.execute()
            }
        }
        connection.close()
        return file
    }

    private class JdbcMergeDb(private val connection: Connection) : MergeDb {
        override fun exec(sql: String) = connection.createStatement().use { it.execute(sql); Unit }
        override fun queryString(sql: String): String? =
            connection.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
    }

    private fun runner(connection: Connection) = object : AddressQueryRunner {
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

    private fun merge(vararg cells: File): Connection {
        val target = connect(File(dir, "addresses-search.db"))
        mergeAddressSearchDbs(JdbcMergeDb(target), cells.toList(), File(dir, "merge.scratch"))
        return target
    }

    // Via Roma (Torino) e' in entrambe le celle, con id di via diversi; Via Roma (Moncalieri) e Corso Francia ciascuna in una sola.
    private val cellA = { cell("a.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Torino", "1", 45.01, 7.61), Addr("Via Roma", "Torino", "2", 45.02, 7.62), Addr("Corso Francia", "Torino", "5", 45.05, 7.55)) }
    private val cellB = { cell("b.db", 44_500_000, 6_900_000, 40, Addr("Via Roma", "Moncalieri", "9", 44.99, 7.68), Addr("Via Roma", "Torino", "3", 45.03, 7.63), Addr("Via Roma", "Torino", "2", 45.02, 7.62)) }

    @Test
    fun `due celle con la stessa via diventano una via sola, con un'origine unica`() {
        val merged = merge(cellA(), cellB())
        val db = runner(merged)

        // 3 vie distinte (Via Roma Torino una volta sola), nessun id di cella riusato per un'altra via.
        assertEquals(3L, db.query("SELECT COUNT(*) FROM street", emptyList()) { it.long(0) }.single())
        // L'indirizzo che compare in tutte e due le celle (Via Roma 2, stesse coordinate) si tiene una volta sola.
        assertEquals(5L, db.query("SELECT COUNT(*) FROM address", emptyList()) { it.long(0) }.single())
        val origin = readAddressOrigin(db)!!
        assertEquals(44_000_000L, origin.latE6)
        assertEquals(6_900_000L, origin.lonE6)
        assertEquals(1024L, db.query("PRAGMA page_size", emptyList()) { it.long(0) }.single())
        // ANALYZE ha scritto le statistiche per il pianificatore.
        assertEquals(1L, db.query("SELECT COUNT(*) > 0 FROM sqlite_stat1", emptyList()) { it.long(0) }.single())
    }

    @Test
    fun `le coordinate di ogni indirizzo restano quelle di partenza`() {
        val db = runner(merge(cellA(), cellB()))
        val origin = readAddressOrigin(db)!!

        fun find(text: String) = searchAddressDb(db, origin, "torino", parseAddressQuery(text), 10)

        assertEquals(listOf(45.01 to 7.61), find("via roma 1").map { it.latitude to it.longitude })
        assertEquals(listOf(45.03 to 7.63), find("via roma 3").map { it.latitude to it.longitude })
        assertEquals(listOf(45.05 to 7.55), find("corso francia 5").map { it.latitude to it.longitude })
        assertEquals(listOf(44.99 to 7.68), find("via roma 9").map { it.latitude to it.longitude })
    }

    @Test
    fun `con il civico un indirizzo esatto, senza una voce per via e citta`() {
        val db = runner(merge(cellA(), cellB()))
        val origin = readAddressOrigin(db)!!

        // "via roma 3" e' solo a Torino (cella B): le vie di Moncalieri senza quel civico non danno risultati.
        val withNumber = searchAddressDb(db, origin, "x", parseAddressQuery("via roma 3"), 10)
        assertEquals(listOf("Via Roma 3, Torino"), withNumber.map { it.displayName })

        // Senza civico: Via Roma Torino (indirizzi di entrambe le celle: 1, 2, 3, mediana 2) e Via Roma Moncalieri.
        val streets = searchAddressDb(db, origin, "x", parseAddressQuery("via roma"), 10)
        assertEquals(listOf("Via Roma, Moncalieri", "Via Roma, Torino"), streets.map { it.displayName }.sorted())
        val torino = streets.single { it.city == "Torino" }
        assertEquals(45.02 to 7.62, torino.latitude to torino.longitude)
        assertEquals(1, searchAddressDb(db, origin, "x", parseAddressQuery("via roma"), 1).size)
    }

    @Test
    fun `una cella in un formato diverso dal 2 non si unisce`() {
        val old = cellA()
        connect(old).use { exec(it, "UPDATE meta SET value = '1' WHERE key = 'format'") }
        assertThrows(IllegalStateException::class.java) { merge(old) }
    }
}
