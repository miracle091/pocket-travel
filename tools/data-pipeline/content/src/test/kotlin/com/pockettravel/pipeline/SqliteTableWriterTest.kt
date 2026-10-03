package com.pockettravel.pipeline

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class SqliteTableWriterTest {
    private lateinit var outputDb: File

    @Before
    fun setUp() {
        outputDb = File.createTempFile("pocket-travel-test", ".db")
        outputDb.delete()
    }

    @After
    fun tearDown() {
        outputDb.delete()
    }

    private fun write(tableName: String, rows: List<Pair<Int, String>>) = writeSqliteTable(
        outputDb = outputDb,
        tableName = tableName,
        createTableSql = "CREATE TABLE $tableName (id INTEGER PRIMARY KEY, label TEXT NOT NULL)",
        insertSql = "INSERT INTO $tableName (id, label) VALUES (?, ?)",
        rows = rows,
    ) { insert, (id, label) ->
        insert.setInt(1, id)
        insert.setString(2, label)
    }

    private fun readAll(tableName: String): List<Pair<Int, String>> =
        DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
            conn.createStatement().use { statement ->
                val rs = statement.executeQuery("SELECT id, label FROM $tableName ORDER BY id")
                buildList { while (rs.next()) add(rs.getInt("id") to rs.getString("label")) }
            }
        }

    @Test
    fun `crea il file e la tabella e vi inserisce tutte le righe`() {
        write("voci", listOf(1 to "uno", 2 to "due", 3 to "tre"))

        assertEquals(listOf(1 to "uno", 2 to "due", 3 to "tre"), readAll("voci"))
    }

    @Test
    fun `senza righe la tabella esiste ed e' vuota`() {
        write("voci", emptyList())

        assertEquals(emptyList<Pair<Int, String>>(), readAll("voci"))
    }

    @Test
    fun `piu' righe di un blocco di batch vengono inserite tutte`() {
        // Un po' oltre due blocchi da 10.000, per passare dal flush intermedio e da quello finale.
        val rows = (1..20_005).map { it to "riga $it" }

        write("voci", rows)

        assertEquals(rows, readAll("voci"))
    }

    @Test
    fun `riscrivere la tabella sostituisce le righe e lascia intatte le altre tabelle`() {
        write("voci", listOf(1 to "vecchia"))
        write("altra", listOf(7 to "resta"))

        write("voci", listOf(2 to "nuova", 3 to "nuova 3"))

        assertEquals(listOf(2 to "nuova", 3 to "nuova 3"), readAll("voci"))
        assertEquals(listOf(7 to "resta"), readAll("altra"))
    }

    @Test
    fun `un insert che fallisce lascia la tabella precedente`() {
        write("voci", listOf(1 to "precedente"))

        // Chiave primaria duplicata: l'inserimento fallisce dopo DROP e CREATE.
        assertThrows(Exception::class.java) { write("voci", listOf(5 to "a", 5 to "b")) }

        assertEquals(listOf(1 to "precedente"), readAll("voci"))
    }

    @Test
    fun `un errore in bindRow lascia la tabella precedente`() {
        write("voci", listOf(1 to "precedente"))

        assertThrows(IllegalStateException::class.java) {
            writeSqliteTable(
                outputDb = outputDb,
                tableName = "voci",
                createTableSql = "CREATE TABLE voci (id INTEGER PRIMARY KEY, label TEXT NOT NULL)",
                insertSql = "INSERT INTO voci (id, label) VALUES (?, ?)",
                rows = listOf(1, 2),
            ) { insert, n ->
                check(n == 1) { "riga non valida" }
                insert.setInt(1, n)
                insert.setString(2, "x")
            }
        }

        assertEquals(listOf(1 to "precedente"), readAll("voci"))
    }
}
