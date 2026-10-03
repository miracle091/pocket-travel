package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingVariantTest {

    private val sha = "a".repeat(64)
    private fun file(name: String, size: Long) = RegionManifestFile(name, "https://github.com/o/r/releases/download/x/$name", size, sha)

    private val entry = RegionManifestEntry(
        regionId = "italia",
        displayName = "Italia",
        updatedAt = "2026-10-03T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.6, 35.5, 18.5, 47.1, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5", 1000), file("E10_N45.rd5", 2000))),
        routingCar = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5", 430), file("E10_N45.rd5", 860))),
        poi = PoiPackageEntry("p1", file("poi.db", 10)),
    )

    @Test
    fun `con solo auto scelta e offerta i percorsi sono quelli per l'auto`() {
        assertEquals(listOf(430L, 860L), entry.withRoutingVariant(true).routing.files.map { it.sizeBytes })
    }

    @Test
    fun `se non scelta o non offerta restano i percorsi completi`() {
        assertSame(entry, entry.withRoutingVariant(false))
        val withoutCar = entry.copy(routingCar = null)
        assertSame(withoutCar, withoutCar.withRoutingVariant(true))
    }

    @Test
    fun `la zona si applica dopo, anche alla variante auto`() {
        val zoned = entry.withRoutingVariant(true).restrictedTo(com.pockettravel.core.data.RegionZone(12.2, 41.7, 12.8, 42.1))
        assertEquals(listOf("E10_N40.rd5" to 430L), zoned.routing.files.map { it.name to it.sizeBytes })
    }

    @Test
    fun `la variante auto si riconosce dallo sha256, anche dopo la zona`() {
        fun file(name: String, sha: Char) = RegionManifestFile(name, "https://github.com/o/r/releases/download/x/$name", 10, sha.toString().repeat(64))
        val distinct = entry.copy(
            routing = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5", 'a'), file("E10_N45.rd5", 'b'))),
            routingCar = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5", 'c'), file("E10_N45.rd5", 'd'))),
        )
        assertFalse(distinct.hasCarOnlyRouting)
        assertTrue(distinct.withRoutingVariant(true).hasCarOnlyRouting)
        val zoned = distinct.withRoutingVariant(true).restrictedTo(com.pockettravel.core.data.RegionZone(12.2, 41.7, 12.8, 42.1))
        assertTrue(zoned.hasCarOnlyRouting)
        assertFalse(distinct.copy(routingCar = null).hasCarOnlyRouting)
    }
}
