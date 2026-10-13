package com.pockettravel.core.data.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * Un [SupportSQLiteDatabase] sopra una connessione sqlite-jdbc, per eseguire le migrazioni nei test JVM (android.database.sqlite
 * non c'e'). Implementa solo quello che le migrazioni usano: execSQL, query con una stringa SQL e il percorso del file.
 * Ogni altro metodo lancia, cosi' una migrazione che ne usasse uno nuovo fa fallire il test invece di passare in silenzio.
 */
internal fun jdbcSupportDatabase(connection: Connection, path: String? = ":memory:"): SupportSQLiteDatabase =
    Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
        InvocationHandler { _, method, args ->
            when {
                method.name == "execSQL" && args.size == 1 -> {
                    connection.createStatement().use { it.execute(args[0] as String) }
                    null
                }
                method.name == "query" && args.size == 1 && args[0] is String -> queryCursor(connection, args[0] as String)
                method.name == "getPath" -> path
                method.name == "toString" -> "JdbcSupportDatabase($path)"
                method.name == "hashCode" -> System.identityHashCode(connection)
                method.name == "equals" -> false
                else -> throw UnsupportedOperationException("SupportSQLiteDatabase.${method.name} non e' implementato nei test")
            }
        },
    ) as SupportSQLiteDatabase

private fun queryCursor(connection: Connection, sql: String): Cursor {
    val columns = mutableListOf<String>()
    val rows = mutableListOf<List<Any?>>()
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            val meta = rs.metaData
            for (i in 1..meta.columnCount) columns += meta.getColumnLabel(i)
            while (rs.next()) rows += (1..meta.columnCount).map { rs.getObject(it) }
        }
    }
    var position = -1
    return Proxy.newProxyInstance(
        Cursor::class.java.classLoader,
        arrayOf(Cursor::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "moveToFirst" -> { position = 0; rows.isNotEmpty() }
                "moveToNext" -> { position++; position < rows.size }
                "getCount" -> rows.size
                "getColumnIndex" -> columns.indexOf(args[0] as String)
                "close" -> null
                else -> cellValue(method.name, rows[position][args[0] as Int])
            }
        },
    ) as Cursor
}

private fun cellValue(getter: String, value: Any?): Any? = when (getter) {
    "isNull" -> value == null
    "getLong" -> (value as? Number)?.toLong() ?: 0L
    "getInt" -> (value as? Number)?.toInt() ?: 0
    "getDouble" -> (value as? Number)?.toDouble() ?: 0.0
    "getString" -> value?.toString()
    else -> throw UnsupportedOperationException("Cursor.$getter non e' implementato nei test")
}
