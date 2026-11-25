package com.pockettravel.core.data.db

import androidx.room3.migration.Migration
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking

/**
 * I metodi di SQLiteConnection che servono ai test di migrazione, con i nomi che avevano con SupportSQLiteDatabase e Cursor:
 * le righe di una query si leggono subito, cosi' i test restano come erano.
 */
internal class TestDb(val connection: SQLiteConnection) : AutoCloseable {
    fun execSQL(sql: String) = connection.execSQL(sql)

    fun execSQL(sql: String, args: Array<Any?>) = connection.prepare(sql).use { statement ->
        args.forEachIndexed { i, value ->
            when (value) {
                null -> statement.bindNull(i + 1)
                is Int -> statement.bindLong(i + 1, value.toLong())
                is Long -> statement.bindLong(i + 1, value)
                is Double -> statement.bindDouble(i + 1, value)
                else -> statement.bindText(i + 1, value.toString())
            }
        }
        statement.step()
        Unit
    }

    fun query(sql: String): TestCursor = connection.prepare(sql).use { statement ->
        val rows = mutableListOf<List<Any?>>()
        while (statement.step()) {
            rows += (0 until statement.getColumnCount()).map { i ->
                when (statement.getColumnType(i)) {
                    SQLITE_DATA_INTEGER -> statement.getLong(i)
                    SQLITE_DATA_FLOAT -> statement.getDouble(i)
                    SQLITE_DATA_BLOB -> statement.getBlob(i)
                    SQLITE_DATA_NULL -> null
                    else -> statement.getText(i)
                }
            }
        }
        TestCursor(rows)
    }

    override fun close() = connection.close()
}

internal class TestCursor(private val rows: List<List<Any?>>) : AutoCloseable {
    private var position = -1

    val count: Int get() = rows.size

    fun moveToFirst(): Boolean {
        position = 0
        return rows.isNotEmpty()
    }

    fun moveToNext(): Boolean = ++position < rows.size

    fun isNull(column: Int): Boolean = rows[position][column] == null

    fun getString(column: Int): String? = rows[position][column]?.toString()

    fun getLong(column: Int): Long = (rows[position][column] as? Number)?.toLong() ?: 0L

    fun getInt(column: Int): Int = getLong(column).toInt()

    override fun close() = Unit
}

/** L'helper di Room con il driver di Android sul database [dbName] del dispositivo. */
internal fun migrationTestHelper(dbName: String) = InstrumentationRegistry.getInstrumentation().let { instrumentation ->
    MigrationTestHelper(instrumentation, instrumentation.targetContext.getDatabasePath(dbName), AndroidSQLiteDriver(), RegionDatabase::class)
}

/** Crea il database vuoto alla [version]: l'helper di Room non toglie il file di una prova precedente, quindi lo si cancella prima. */
internal fun MigrationTestHelper.createDatabase(dbName: String, version: Int): TestDb {
    InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(dbName)
    return TestDb(runBlocking { createDatabase(version) })
}

/** Migra il database creato da [createDatabase] alla [version] e ne controlla lo schema con quello esportato. */
internal fun MigrationTestHelper.migrateAndValidate(version: Int, vararg migrations: Migration): TestDb =
    TestDb(runBlocking { runMigrationsAndValidate(version, migrations.toList()) })
