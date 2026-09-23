package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateAddressesTest {

    // Estratto reale (build Protomaps 2026-09-21) del centro di San Marino, solo z15:
    // pmtiles extract ... --bbox=12.44,43.93,12.46,43.94 --minzoom=15 --maxzoom=15
    private val fixture = File("testdata/san-marino-center-z15.pmtiles")

    @Test
    fun `estrae i numeri civici dentro il bbox con coordinate plausibili`() {
        val addresses = extractAddresses(fixture, minLon = 12.44, minLat = 43.93, maxLon = 12.46, maxLat = 43.94)

        assertTrue("attesi indirizzi nel centro di San Marino, trovati ${addresses.size}", addresses.size > 100)
        assertTrue(addresses.all { it.number.isNotBlank() })
        // Se la conversione da coordinate di tile fosse sbagliata, i punti cadrebbero fuori dal
        // bbox e verrebbero scartati: qui devono stare tutti dentro.
        assertTrue(addresses.all { it.latE6 in 43_930_000..43_940_000 && it.lonE6 in 12_440_000..12_460_000 })
    }

    @Test
    fun `un bbox piu' piccolo delle tile scarta gli indirizzi fuori`() {
        val all = extractAddresses(fixture, 12.44, 43.93, 12.46, 43.94)
        val half = extractAddresses(fixture, 12.44, 43.93, 12.45, 43.94)

        assertTrue(half.isNotEmpty() && half.size < all.size)
        assertTrue(half.all { it.lonE6 <= 12_450_000 })
    }

    @Test
    fun `scrive la tabella addresses in content db`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        try {
            writeAddressesDb(listOf(Address(43_935_000, 12_447_000, "12A")), outputDb)
            DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
                conn.createStatement().executeQuery("SELECT latE6, lonE6, number FROM addresses").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(43_935_000, rs.getInt(1))
                    assertEquals(12_447_000, rs.getInt(2))
                    assertEquals("12A", rs.getString(3))
                }
            }
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `conversione tile-coordinate geografiche`() {
        assertEquals(-180.0, tileXToLon(0.0, 15), 1e-9)
        assertEquals(0.0, tileXToLon(16384.0, 15), 1e-9)
        assertEquals(0.0, tileYToLat(16384.0, 15), 1e-9)
    }
}
