package com.pockettravel.core.data

import com.pockettravel.core.data.db.POI_ROW_ESTIMATE_SQL
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.estimatedStorageBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * La stima del peso dei POI che la migrazione del database calcola in SQL (POI_ROW_ESTIMATE_SQL) deve essere uguale a quella
 * di PoiRow.estimatedStorageBytes, che si usa all'importazione: sqlite-jdbc, perche' android.database.sqlite non c'e' nei test JVM.
 */
class PoiSqlEstimateTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use {
            it.execute(
                "CREATE TABLE poi(id INTEGER PRIMARY KEY AUTOINCREMENT, extra INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL, phone TEXT, wheelchair TEXT, " +
                    "openingHours TEXT, address TEXT, website TEXT, email TEXT, country TEXT, nameEn TEXT, nameIt TEXT, toiletsWheelchair TEXT, " +
                    "capacityDisabled INTEGER)",
            )
        }
    }

    @After
    fun tearDown() = connection.close()

    private val rows = listOf(
        PoiRow(regionRef = 1, code = 1, latE6 = 1, lonE6 = 1, name = "Da Mario"),
        PoiRow(regionRef = 1, code = 2, latE6 = 1, lonE6 = 1, name = "Caff\u00e8 Rig\u0101", nameEn = "Cafe", nameIt = "Caff\u00e8", wheelchair = "yes"),
        PoiRow(
            regionRef = 1, code = 3, latE6 = 1, lonE6 = 1, name = "\u6771\u4eac\u99c5", phone = "+39 06 1234567", openingHours = "Mo-Fr 08:00-18:00",
            address = "Via Roma 1, Milano", website = "https://example.org/", email = "a@example.org", country = "IT", toiletsWheelchair = "limited",
            capacityDisabled = 4, extra = true,
        ),
    )

    private fun insert(row: PoiRow) {
        connection.prepareStatement(
            "INSERT INTO poi(extra, name, phone, wheelchair, openingHours, address, website, email, country, nameEn, nameIt, toiletsWheelchair, capacityDisabled) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setInt(1, if (row.extra) 1 else 0)
            listOf(row.name, row.phone, row.wheelchair, row.openingHours, row.address, row.website, row.email, row.country, row.nameEn, row.nameIt, row.toiletsWheelchair)
                .forEachIndexed { i, value -> it.setString(i + 2, value) }
            it.setObject(13, row.capacityDisabled)
            it.executeUpdate()
        }
    }

    private fun sqlSum(where: String = "1"): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(SUM($POI_ROW_ESTIMATE_SQL), 0) FROM poi WHERE $where").use { it.next(); it.getLong(1) }
        }

    @Test
    fun `la stima in SQL e' quella di Kotlin, riga per riga e in somma`() {
        rows.forEach(::insert)

        assertEquals(rows.sumOf { it.estimatedStorageBytes() }, sqlSum())
        assertEquals(rows.filter { !it.extra }.sumOf { it.estimatedStorageBytes() }, sqlSum("extra = 0"))
        assertEquals(rows.filter { it.extra }.sumOf { it.estimatedStorageBytes() }, sqlSum("extra = 1"))
        rows.forEachIndexed { i, row ->
            val one = connection.createStatement().use { s ->
                s.executeQuery("SELECT $POI_ROW_ESTIMATE_SQL FROM poi WHERE id = ${i + 1}").use { it.next(); it.getLong(1) }
            }
            assertEquals("riga $i", row.estimatedStorageBytes(), one)
        }
        assertTrue(sqlSum() > 0)
    }
}
