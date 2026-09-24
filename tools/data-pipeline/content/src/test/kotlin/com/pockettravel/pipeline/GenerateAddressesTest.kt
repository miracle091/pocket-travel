package com.pockettravel.pipeline

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import org.locationtech.jts.geom.Point
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
    fun `scrive i civici in un pmtiles di soli punti a z14`() {
        val addresses = extractAddresses(fixture, 12.44, 43.93, 12.46, 43.94)
        val output = File.createTempFile("pocket-travel-test", ".addresses.pmtiles")
        try {
            writeAddressesPmtiles(addresses, output, 12.44, 43.93, 12.46, 43.94)

            val decoded = mutableListOf<Address>()
            ReadablePmtiles.newReadFromFile(output.toPath()).use { archive ->
                archive.getAllTiles().use { tiles ->
                    tiles.forEachRemaining { tile ->
                        val coord = tile.coord()
                        assertEquals(14, coord.z())
                        VectorTile.decode(GZIPInputStream(ByteArrayInputStream(tile.bytes())).readBytes()).forEach { feature ->
                            assertEquals("addresses", feature.layer())
                            val point = feature.geometry().decode() as Point
                            val lon = tileXToLon(coord.x() + point.x / 256.0, 14)
                            val lat = tileYToLat(coord.y() + point.y / 256.0, 14)
                            decoded += Address((lat * 1e6).roundToInt(), (lon * 1e6).roundToInt(), feature.attrs()["number"].toString())
                        }
                    }
                }
            }

            assertEquals(addresses.size, decoded.size)
            assertEquals(addresses.map { it.number }.sorted(), decoded.map { it.number }.sorted())
            // La griglia z14 (4096 unita' per tile, ~0,6 m) sposta i punti di poco: entro 20 microgradi.
            // Ogni punto letto deve avere un originale con lo stesso numero a quella distanza (lo
            // stesso numero compare piu' volte, su vie diverse).
            val remaining = addresses.toMutableList()
            decoded.forEach { b ->
                val match = remaining.firstOrNull { a -> a.number == b.number && abs(a.latE6 - b.latE6) <= 20 && abs(a.lonE6 - b.lonE6) <= 20 }
                assertTrue("nessun originale vicino a $b", match != null)
                remaining.remove(match)
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun `conversione tile-coordinate geografiche`() {
        assertEquals(-180.0, tileXToLon(0.0, 15), 1e-9)
        assertEquals(0.0, tileXToLon(16384.0, 15), 1e-9)
        assertEquals(0.0, tileYToLat(16384.0, 15), 1e-9)
    }
}
