package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RegionZoneTest {

    private val sha = "a".repeat(64)
    private fun file(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/x/$name", 1_000L, sha)
    private fun cell(id: String) = AddressGridCell(id, "v1", file("cell-${id.replace('/', '-')}.pmtiles"))

    // Italia, grosso modo: tile .rd5 da E5_N35 a E15_N45.
    private val italy = RegionManifestEntry(
        regionId = "italia",
        displayName = "Italia",
        updatedAt = "2026-10-01T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.6, 35.5, 18.5, 47.1, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf("E5_N35.rd5", "E5_N40.rd5", "E5_N45.rd5", "E10_N35.rd5", "E10_N40.rd5", "E10_N45.rd5", "E15_N35.rd5", "E15_N40.rd5").map(::file)),
        poi = PoiPackageEntry("p1", file("poi.db")),
        // Zoom 8: x 133-140, y 90-102 circa per l'Italia. 136/94 e' vicino a Roma, 134/90 sopra Milano.
        addressGrid = RegionAddressGridEntry(listOf(cell("8/136/94"), cell("8/134/90"))),
    )

    private val rome = RegionZone(minLon = 12.2, minLat = 41.7, maxLon = 12.8, maxLat = 42.1)

    @Test
    fun `senza zona la regione resta com'e'`() {
        assertSame(italy, italy.restrictedTo(null))
    }

    @Test
    fun `la mappa si estrae solo dal riquadro della zona, dentro quello della regione`() {
        val source = italy.restrictedTo(RegionZone(5.0, 41.7, 12.8, 42.1)).map.source
        assertEquals(listOf(6.6, 41.7, 12.8, 42.1), listOf(source.minLon, source.minLat, source.maxLon, source.maxLat))
        assertEquals("m1", italy.restrictedTo(rome).map.version)
    }

    @Test
    fun `percorsi solo dalle tile 5x5 che toccano la zona`() {
        assertEquals(listOf("E10_N40.rd5"), italy.restrictedTo(rome).routing.files.map { it.name })
        // Zona a cavallo di 45 gradi nord: due tile.
        assertEquals(listOf("E10_N40.rd5", "E10_N45.rd5"), italy.restrictedTo(RegionZone(11.0, 44.5, 12.0, 45.5)).routing.files.map { it.name })
    }

    @Test
    fun `tile a ovest e a sud si leggono con il segno`() {
        assertEquals(TileBounds(-5.0, -10.0, 0.0, -5.0), rd5TileBounds("W5_S10.rd5"))
        assertEquals(TileBounds(10.0, 45.0, 15.0, 50.0), rd5TileBounds("E10_N45.rd5"))
        assertNull(rd5TileBounds("segment.rd5"))
    }

    @Test
    fun `civici solo dalle celle della zona, nessuna cella vuol dire niente civici`() {
        assertEquals(listOf("8/136/94"), italy.restrictedTo(rome).addressGrid!!.cells.map { it.id })
        assertNull(italy.restrictedTo(RegionZone(16.0, 38.0, 16.5, 38.5)).addressGrid)
    }

    @Test
    fun `una zona fuori dalla regione lascia la regione intera`() {
        assertSame(italy, italy.restrictedTo(RegionZone(-10.0, 0.0, -5.0, 5.0)))
    }
}
