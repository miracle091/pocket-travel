package com.pockettravel.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

/** Casi limite di mergeAddressSearchDbs: nessun ingresso, vie senza citta', celle ripetute, civici insoliti. */
class AddressSearchMergerEdgeCasesTest {

    private val dir = createTempDirectory("pocket-travel-address-merge-edge-test").toFile()
    private val connections = mutableListOf<Connection>()

    @After
    fun tearDown() {
        connections.forEach { it.close() }
        dir.deleteRecursively()
    }

    private class Addr(val street: String, val city: String?, val number: String, val lat: Double, val lon: Double)

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}").also { connections += it }

    private fun exec(connection: Connection, vararg sql: String) = connection.createStatement().use { s -> sql.forEach { s.execute(it) } }

    /** Una cella come la pubblica la pipeline: origine propria e id delle vie a partire da [firstStreetId]. */
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

    private fun count(connection: Connection, table: String): Long = runner(connection).query("SELECT COUNT(*) FROM $table", emptyList()) { it.long(0) }.single()

    @Test
    fun `senza celle non c'e' niente da unire e il database non viene toccato`() {
        val untouched = object : MergeDb {
            override fun exec(sql: String): Unit = throw AssertionError("exec: $sql")
            override fun queryString(sql: String): String? = throw AssertionError("queryString: $sql")
        }

        assertThrows(IllegalArgumentException::class.java) { mergeAddressSearchDbs(untouched, emptyList(), File(dir, "merge.scratch")) }
    }

    @Test
    fun `una via senza citta' in due celle e' una via sola`() {
        val a = cell("a.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", null, "1", 45.01, 7.61), Addr("Via Roma", null, "2", 45.02, 7.62))
        val b = cell("b.db", 44_500_000, 6_900_000, 10, Addr("Via Roma", null, "3", 45.03, 7.63))

        val merged = merge(a, b)

        assertEquals(1L, count(merged, "street"))
        assertEquals(3L, count(merged, "address"))
        val db = runner(merged)
        val origin = checkNotNull(readAddressOrigin(db))
        val found = searchAddressDb(db, origin, "x", parseAddressQuery("via roma 3"), 10).single()
        assertEquals("Via Roma 3", found.displayName)
        assertEquals(45.03 to 7.63, found.latitude to found.longitude)
    }

    @Test
    fun `lo stesso nome in citta' diverse resta in due vie`() {
        val a = cell("a.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Torino", "1", 45.01, 7.61))
        val b = cell("b.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Moncalieri", "1", 44.99, 7.68))

        val merged = merge(a, b)

        assertEquals(2L, count(merged, "street"))
        val db = runner(merged)
        val origin = checkNotNull(readAddressOrigin(db))
        val found = searchAddressDb(db, origin, "x", parseAddressQuery("via roma 1"), 10)
        assertEquals(listOf("Via Roma 1, Moncalieri", "Via Roma 1, Torino"), found.map { it.displayName }.sorted())
    }

    @Test
    fun `la stessa cella data due volte non duplica ne' vie ne' indirizzi`() {
        val a = cell("a.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Torino", "1", 45.01, 7.61), Addr("Corso Francia", "Torino", "5", 45.05, 7.55))

        val merged = merge(a, a)

        assertEquals(2L, count(merged, "street"))
        assertEquals(2L, count(merged, "address"))
    }

    @Test
    fun `una sola cella tiene la sua origine e le sue coordinate`() {
        val a = cell("a.db", 44_123_456, 7_654_321, 7, Addr("Via Roma", "Torino", "12a", 45.01, 7.61))

        val merged = merge(a)
        val db = runner(merged)
        val origin = checkNotNull(readAddressOrigin(db))

        assertEquals(44_123_456L, origin.latE6)
        assertEquals(7_654_321L, origin.lonE6)
        val found = searchAddressDb(db, origin, "x", parseAddressQuery("via roma 12A"), 10).single()
        assertEquals("Via Roma 12a, Torino", found.displayName)
        assertEquals(45.01 to 7.61, found.latitude to found.longitude)
    }

    @Test
    fun `un civico scritto in modo diverso nelle maiuscole nella stessa posizione conta una volta`() {
        val a = cell("a.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Torino", "12A", 45.01, 7.61))
        val b = cell("b.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Torino", "12a", 45.01, 7.61))

        val merged = merge(a, b)

        assertEquals(1L, count(merged, "address"))
    }

    @Test
    fun `una cella senza origine nella meta non si unisce`() {
        val a = cell("a.db", 44_000_000, 7_000_000, 1, Addr("Via Roma", "Torino", "1", 45.01, 7.61))
        connect(a).use { exec(it, "DELETE FROM meta WHERE key = 'origin_lat_e6'") }

        assertThrows(IllegalStateException::class.java) { merge(a) }
    }
}
