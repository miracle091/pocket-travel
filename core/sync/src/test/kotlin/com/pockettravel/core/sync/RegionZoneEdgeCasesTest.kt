package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Casi limite di restrictedTo oltre RegionZoneTest: bordi, emisferi ovest e sud, file con nome diverso. */
class RegionZoneEdgeCasesTest {

    private val sha = "a".repeat(64)
    private fun file(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/x/$name", 1_000L, sha)

    private fun region(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, vararg tiles: String) = RegionManifestEntry(
        regionId = "r",
        displayName = "R",
        updatedAt = "2026-10-01T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", minLon, minLat, maxLon, maxLat, 0, 14)),
        routing = RoutingPackageEntry("r1", tiles.map(::file)),
        poi = PoiPackageEntry("p1", file("poi.db")),
    )

    private val europe = region(6.6, 35.5, 18.5, 47.1, "E5_N35.rd5", "E5_N40.rd5", "E10_N35.rd5", "E10_N40.rd5", "E15_N35.rd5", "E15_N40.rd5")

    @Test
    fun `una zona che tocca la regione solo sul bordo la lascia intera`() {
        assertSame(europe, europe.restrictedTo(RegionZone(18.5, 40.0, 20.0, 41.0)))
        assertSame(europe, europe.restrictedTo(RegionZone(10.0, 47.1, 11.0, 48.0)))
    }

    @Test
    fun `una tile che tocca la zona solo sul bordo non serve`() {
        // La zona finisce a 10 gradi est: la tile E10 comincia a 10 e non entra.
        val tiles = europe.restrictedTo(RegionZone(7.0, 41.0, 10.0, 43.0)).routing.files.map { it.name }
        assertEquals(listOf("E5_N40.rd5"), tiles)
    }

    @Test
    fun `una zona piu' grande della regione prende il riquadro della regione e tutte le tile`() {
        val restricted = europe.restrictedTo(RegionZone(-180.0, -90.0, 180.0, 90.0))
        assertEquals(europe.map.source, restricted.map.source)
        assertEquals(europe.routing.files, restricted.routing.files)
    }

    @Test
    fun `le versioni e i pacchetti non toccati restano uguali`() {
        val restricted = europe.restrictedTo(RegionZone(7.0, 41.0, 8.0, 42.0))
        assertEquals("r1", restricted.routing.version)
        assertEquals("p1", restricted.poi.version)
        assertEquals("m1", restricted.map.version)
        assertEquals(europe.poi, restricted.poi)
        assertNull(restricted.addressGrid)
    }

    @Test
    fun `un file di routing con un nome che non e' una tile resta`() {
        val odd = region(6.6, 35.5, 18.5, 47.1, "E5_N35.rd5", "extra.bin")
        val names = odd.restrictedTo(RegionZone(16.0, 44.0, 17.0, 45.0)).routing.files.map { it.name }
        // E5_N35 non tocca la zona, extra.bin non e' una tile: non si puo' dire che non serva.
        assertEquals(listOf("extra.bin"), names)
    }

    @Test
    fun `le tile a ovest e a sud intersecano la zona con i segni giusti`() {
        val west = region(-8.0, -8.0, -2.0, -2.0, "W10_S10.rd5", "W5_S10.rd5", "W10_S5.rd5", "W5_S5.rd5")
        assertEquals(
            listOf("W10_S10.rd5"),
            west.restrictedTo(RegionZone(-9.0, -9.0, -7.0, -7.0)).routing.files.map { it.name },
        )
        assertEquals(
            listOf("W10_S10.rd5", "W5_S10.rd5", "W10_S5.rd5", "W5_S5.rd5"),
            west.restrictedTo(RegionZone(-6.0, -6.0, -4.0, -4.0)).routing.files.map { it.name },
        )
    }

    @Test
    fun `i nomi di tile malformati non sono tile`() {
        listOf("E5N35.rd5", "e5_n35.rd5", "E5_N35.rd5.xz", "E_N35.rd5", "E5_N35", "X5_N35.rd5", "E5_N35.rd5\n").forEach {
            assertNull(it, rd5TileBounds(it))
        }
        assertEquals(TileBounds(0.0, 0.0, 5.0, 5.0), rd5TileBounds("E0_N0.rd5"))
    }
}
