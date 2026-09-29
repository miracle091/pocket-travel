package com.pockettravel.app.regions

import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.TransitFeed
import com.pockettravel.core.sync.TransitIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitChoicesTest {
    private fun feed(id: String, bbox: List<Double>) = TransitFeed(
        id, id, listOf("regno-unito"), "OGL-UK-3.0", "DfT", null, "v1",
        RegionManifestFile("transit.db", "https://github.com/x/$id.db", 1, "0".repeat(64)), bbox = bbox,
    )

    private val index = TransitIndex(
        "v1",
        listOf(
            feed("london", listOf(-0.51, 51.28, 0.33, 51.69)),
            feed("north-west", listOf(-3.6, 52.9, -2.0, 55.2)),
            feed("scotland", listOf(-7.6, 54.6, -0.7, 60.9)),
            feed("wales", listOf(-5.4, 51.3, -2.6, 53.5)),
        ),
    )
    private val uk = RegionManifestEntry(
        regionId = "regno-unito",
        displayName = "Regno Unito",
        updatedAt = "2026-09-29T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", -8.7, 49.8, 1.8, 60.9, 0, 14)),
        routing = RoutingPackageEntry("r1", emptyList()),
        poi = PoiPackageEntry("p1", RegionManifestFile("poi.db", "https://github.com/poi.db", 1, "a".repeat(64))),
        countryCode = "gb",
    )

    @Test
    fun `nella stessa nazione restano le reti vicine`() {
        val choices = effectiveTransitChoices(listOf(uk), index, emptyMap(), DevicePlace(51.5074, -0.1278, "gb"))
        assertEquals(setOf("north-west", "scotland", "wales"), choices.excluded["regno-unito"])
        assertEquals(TransitDefaultReason.NEAR, choices.reasons["regno-unito"])
    }

    @Test
    fun `da un'altra nazione restano tutte`() {
        // Calais: a 40 km dalla costa inglese, ma in Francia.
        val choices = effectiveTransitChoices(listOf(uk), index, emptyMap(), DevicePlace(50.95, 1.85, "fr"))
        assertEquals(emptySet<String>(), choices.excluded["regno-unito"])
        assertEquals(TransitDefaultReason.ALL, choices.reasons["regno-unito"])
    }

    @Test
    fun `la scelta dell'utente vince e non ha un perche'`() {
        val choices = effectiveTransitChoices(listOf(uk), index, mapOf("regno-unito" to setOf("london")), DevicePlace(51.5, -0.12, "gb"))
        assertEquals(setOf("london"), choices.excluded["regno-unito"])
        assertEquals(null, choices.reasons["regno-unito"])
    }
}
