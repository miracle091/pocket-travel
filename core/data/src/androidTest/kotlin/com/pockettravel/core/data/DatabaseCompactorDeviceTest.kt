package com.pockettravel.core.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.db.RegionDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il checkpoint del WAL e il VACUUM del compattatore girano anche con il driver SQLite di Android usato da Room 3, non
 * solo con sqlite-jdbc dei test JVM: un errore li' verrebbe solo scritto nel log e lo spazio non tornerebbe mai.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseCompactorDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun lePagineLibereTornanoAlDisco() {
        context.deleteDatabase(DB_NAME)
        val db = Room.databaseBuilder(context, RegionDatabase::class.java, DB_NAME).setDriver(AndroidSQLiteDriver()).build()
        try {
            runBlocking {
                // 40 MB di dati tolti subito: pagine libere oltre la soglia di 32 MB del compattatore. L'SQLite di Android
                // nasce con auto_vacuum FULL, che le restituirebbe a ogni commit: qui si spegne, e vale dopo un VACUUM.
                db.useWriterConnection { connection ->
                    connection.usePrepared("PRAGMA auto_vacuum = NONE") { it.step() }
                    connection.usePrepared("VACUUM") { it.step() }
                    connection.usePrepared("CREATE TABLE scarto(b BLOB)") { it.step() }
                    repeat(40) { connection.usePrepared("INSERT INTO scarto VALUES (zeroblob(1048576))") { it.step() } }
                    connection.usePrepared("DROP TABLE scarto") { it.step() }
                }
                suspend fun freePages() = db.useWriterConnection { connection -> connection.usePrepared("PRAGMA freelist_count") { it.step(); it.getLong(0) } }
                val before = freePages()
                assertTrue("pagine libere prima: $before", before > 8000)
                val compactor = DatabaseCompactor({ block -> db.useWriterConnection { block(it) } }, Dispatchers.IO) { Long.MAX_VALUE }
                compactor.compactIfWasteful()
                val after = freePages()
                assertTrue("pagine libere dopo il VACUUM: $after", after < 100)
            }
        } finally {
            db.close()
        }
        assertTrue(context.getDatabasePath(DB_NAME).length() < 8L * 1024 * 1024)
    }

    private companion object {
        const val DB_NAME = "compactor-test.db"
    }
}
