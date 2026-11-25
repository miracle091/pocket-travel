package com.pockettravel.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.ResultSet

/**
 * Una [SQLiteConnection] sopra una connessione sqlite-jdbc, per eseguire le migrazioni nei test JVM (il driver di Android
 * non c'e'). Implementa solo quello che le migrazioni usano: prepare, step e la lettura di testo e interi. Ogni altro metodo
 * lancia, cosi' una migrazione che ne usasse uno nuovo fa fallire il test invece di passare in silenzio. close() non chiude la
 * connessione JDBC, che resta del test.
 */
internal fun jdbcSqliteConnection(connection: Connection): SQLiteConnection = JdbcSqliteConnection(connection)

private class JdbcSqliteConnection(private val connection: Connection) : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = JdbcSqliteStatement(connection.prepareStatement(sql))

    override fun close() = Unit
}

private class JdbcSqliteStatement(private val statement: java.sql.PreparedStatement) : SQLiteStatement {
    private var executed = false
    private var rows: ResultSet? = null

    override fun step(): Boolean {
        if (!executed) {
            executed = true
            if (statement.execute()) rows = statement.resultSet
        }
        return rows?.next() ?: false
    }

    override fun getLong(index: Int): Long = requireNotNull(rows) { "nessuna riga" }.getLong(index + 1)

    override fun getText(index: Int): String = requireNotNull(rows) { "nessuna riga" }.getString(index + 1)

    override fun close() = statement.close()

    override fun bindBlob(index: Int, value: ByteArray) = unsupported("bindBlob")
    override fun bindDouble(index: Int, value: Double) = unsupported("bindDouble")
    override fun bindLong(index: Int, value: Long) = unsupported("bindLong")
    override fun bindText(index: Int, value: String) = unsupported("bindText")
    override fun bindNull(index: Int) = unsupported("bindNull")
    override fun getBlob(index: Int): ByteArray = unsupported("getBlob")
    override fun getDouble(index: Int): Double = unsupported("getDouble")
    override fun isNull(index: Int): Boolean = unsupported("isNull")
    override fun getColumnCount(): Int = unsupported("getColumnCount")
    override fun getColumnName(index: Int): String = unsupported("getColumnName")
    override fun getColumnType(index: Int): Int = unsupported("getColumnType")
    override fun reset() = unsupported("reset")
    override fun clearBindings() = unsupported("clearBindings")

    private fun unsupported(name: String): Nothing = throw UnsupportedOperationException("SQLiteStatement.$name non e' implementato nei test")
}
