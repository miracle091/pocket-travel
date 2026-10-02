package com.pockettravel.app.regions

import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.feature.map.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingRoutingRegionTest {

    private fun file(name: String) = RegionManifestFile(name, "https://github.com/$name", 1_000, "a".repeat(64))

    private fun region(id: String, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) = RegionManifestEntry(
        regionId = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        updatedAt = "2026-09-23T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", minLon, minLat, maxLon, maxLat, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5"))),
        poi = PoiPackageEntry("p1", file("poi.db")),
    )

    private val italia = region("italia", 6.6, 35.5, 18.5, 47.1)
    private val sanMarino = region("san-marino", 12.40, 43.89, 12.52, 43.99)
    private val svizzera = region("svizzera", 5.9, 45.8, 10.5, 47.8)
    private val all = listOf(italia, sanMarino, svizzera)

    private val rimini = RoutePoint(44.059, 12.568)
    private val cittaDiSanMarino = RoutePoint(43.9356, 12.4473)
    private val milano = RoutePoint(45.4642, 9.19)
    private val zurigo = RoutePoint(47.3769, 8.5417)

    @Test
    fun `un punto in due riquadri, senza percorsi installati, la regione piu' piccola`() {
        assertEquals(sanMarino, missingRoutingRegion(all, emptySet(), listOf(cittaDiSanMarino, rimini)))
    }

    @Test
    fun `l'Italia contiene San Marino, quindi con i suoi percorsi partenza e arrivo sono coperti`() {
        assertNull(missingRoutingRegion(all, setOf("italia"), listOf(rimini, cittaDiSanMarino)))
    }

    @Test
    fun `con l'arrivo in Svizzera e i percorsi dell'Italia manca la Svizzera`() {
        assertEquals(svizzera, missingRoutingRegion(all, setOf("italia"), listOf(milano, zurigo)))
    }

    @Test
    fun `la partenza scoperta ha la precedenza sull'arrivo`() {
        assertEquals(italia, missingRoutingRegion(all, emptySet(), listOf(milano, zurigo)))
    }

    @Test
    fun `un punto in nessuna regione del manifest non da' nessuna proposta`() {
        val newYork = RoutePoint(40.7, -74.0)

        assertNull(missingRoutingRegion(all, setOf("italia"), listOf(newYork, milano)))
    }
}
