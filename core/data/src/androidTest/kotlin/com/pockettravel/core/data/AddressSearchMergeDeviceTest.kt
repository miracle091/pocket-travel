package com.pockettravel.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * L'unione delle celle con SQLiteDatabase di Android (page_size, ATTACH, ANALYZE via execSQL), che i
 * test JVM con sqlite-jdbc non coprono.
 */
@RunWith(AndroidJUnit4::class)
class AddressSearchMergeDeviceTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "address-merge-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun cell(name: String, originLat: Int, originLon: Int, rows: List<Triple<String, String, Pair<Int, Int>>>): File {
        val file = File(dir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID")
            db.execSQL("CREATE TABLE street (id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, city TEXT)")
            db.execSQL("CREATE INDEX street_key ON street(key)")
            db.execSQL(
                "CREATE TABLE address (street_id INTEGER NOT NULL, number TEXT NOT NULL COLLATE NOCASE, dlat INTEGER NOT NULL, " +
                    "dlon INTEGER NOT NULL, PRIMARY KEY (street_id, number, dlat, dlon)) WITHOUT ROWID",
            )
            db.execSQL("INSERT INTO meta VALUES ('format', '2'), ('origin_lat_e6', '$originLat'), ('origin_lon_e6', '$originLon')")
            rows.map { it.first }.distinct().forEachIndexed { index, street ->
                db.execSQL("INSERT INTO street VALUES (?, ?, ?, 'Rīga')", arrayOf<Any>(index + 1, street, streetKey(street)))
            }
            val ids = rows.map { it.first }.distinct()
            rows.forEach { (street, number, coords) ->
                db.execSQL(
                    "INSERT INTO address VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(ids.indexOf(street) + 1, number, coords.first - originLat, coords.second - originLon),
                )
            }
        }
        return file
    }

    @Test
    fun due_celle_con_la_stessa_via_diventano_un_solo_indice() {
        val a = cell("a.db", 56_950_000, 24_100_000, listOf(Triple("Brīvības iela", "1", 56_950_100 to 24_100_200)))
        val b = cell(
            "b.db",
            56_960_000,
            24_110_000,
            listOf(Triple("Brīvības iela", "2", 56_960_300 to 24_110_400), Triple("Elizabetes iela", "10", 56_961_000 to 24_111_000)),
        )
        val target = File(dir, "addresses-search.db")

        mergeAddressSearchOnDevice(listOf(a, b), target)

        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            fun long(sql: String) = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
            assertEquals(1024L, long("PRAGMA page_size"))
            assertEquals(2L, long("SELECT COUNT(*) FROM street"))
            assertEquals(3L, long("SELECT COUNT(*) FROM address"))
            // ANALYZE eseguito: le statistiche esistono.
            assertEquals(1L, long("SELECT COUNT(*) FROM sqlite_master WHERE name = 'sqlite_stat1'"))
            val originLat = long("SELECT value FROM meta WHERE key = 'origin_lat_e6'")
            val originLon = long("SELECT value FROM meta WHERE key = 'origin_lon_e6'")
            val lat = long("SELECT dlat FROM address JOIN street ON street.id = street_id WHERE number = '2'") + originLat
            val lon = long("SELECT dlon FROM address JOIN street ON street.id = street_id WHERE number = '2'") + originLon
            assertEquals(56_960_300L, lat)
            assertEquals(24_110_400L, lon)
        }
        assertEquals(false, File(dir, "addresses-search.db.scratch").exists())
    }
}
