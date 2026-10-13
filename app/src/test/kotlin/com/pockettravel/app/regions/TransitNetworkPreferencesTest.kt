package com.pockettravel.app.regions

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RegionTransitEntry
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.TransitFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitNetworkPreferencesTest {
    private fun feed(id: String) = TransitFeed(
        id, id, listOf("regno-unito"), "OGL-UK-3.0", "DfT", null, "v1",
        RegionManifestFile("transit.db", "https://github.com/x/$id.db", 1, "0".repeat(64)),
    )

    private fun entry(transit: RegionTransitEntry?) = RegionManifestEntry(
        regionId = "regno-unito",
        displayName = "Regno Unito",
        updatedAt = "2026-09-29T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", -8.7, 49.8, 1.8, 60.9, 0, 14)),
        routing = RoutingPackageEntry("r1", emptyList()),
        poi = PoiPackageEntry("p1", RegionManifestFile("poi.db", "https://github.com/poi.db", 1, "a".repeat(64))),
        transit = transit,
    )

    // Due reti offerte, una sola scelta da scaricare: la tolta e' "wales".
    private val twoNetworks = entry(RegionTransitEntry(feeds = listOf(feed("london")), available = listOf(feed("london"), feed("wales"))))

    @Test
    fun `senza scelte non ci sono reti tolte`() {
        assertTrue(TransitNetworkPreferences(InMemoryPreferencesFactory()).excluded.value.isEmpty())
    }

    @Test
    fun `la scelta salvata resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        TransitNetworkPreferences(files).setExcluded("regno-unito", setOf("wales"))

        assertEquals(mapOf("regno-unito" to setOf("wales")), TransitNetworkPreferences(files).excluded.value)
    }

    @Test
    fun `una scelta vuota si salva e resta distinta dall'assenza di scelta`() {
        val files = InMemoryPreferencesFactory()
        TransitNetworkPreferences(files).setExcluded("regno-unito", emptySet())

        assertEquals(mapOf("regno-unito" to emptySet<String>()), TransitNetworkPreferences(files).excluded.value)
    }

    @Test
    fun `le reti stanno come set di stringhe nel file transit_networks, con l'id della regione per chiave`() {
        val files = InMemoryPreferencesFactory()
        TransitNetworkPreferences(files).setExcluded("regno-unito", setOf("wales"))

        assertEquals(setOf("wales"), files.open("transit_networks").getStringSet("regno-unito", null))
    }

    @Test
    fun `includere una rete parte dai default se l'utente non ha scelto`() {
        val preferences = TransitNetworkPreferences(InMemoryPreferencesFactory())
        preferences.setIncluded("regno-unito", "wales", included = true, defaults = setOf("wales", "scotland"))

        assertEquals(setOf("scotland"), preferences.excluded.value["regno-unito"])
    }

    @Test
    fun `escludere una rete la aggiunge alla scelta salvata`() {
        val preferences = TransitNetworkPreferences(InMemoryPreferencesFactory())
        preferences.setExcluded("regno-unito", setOf("wales"))
        preferences.setIncluded("regno-unito", "london", included = false, defaults = emptySet())

        assertEquals(setOf("wales", "london"), preferences.excluded.value["regno-unito"])
    }

    @Test
    fun `rememberChoice salva le reti non scelte quando si scaricano gli orari`() {
        val preferences = TransitNetworkPreferences(InMemoryPreferencesFactory())
        preferences.rememberChoice(twoNetworks, setOf(PackageKind.TRANSIT))

        assertEquals(setOf("wales"), preferences.excluded.value["regno-unito"])
    }

    @Test
    fun `rememberChoice non salva nulla se non si scaricano gli orari`() {
        val preferences = TransitNetworkPreferences(InMemoryPreferencesFactory())
        preferences.rememberChoice(twoNetworks, setOf(PackageKind.MAP))

        assertTrue(preferences.excluded.value.isEmpty())
    }

    @Test
    fun `rememberChoice non cambia una scelta gia' salvata`() {
        val preferences = TransitNetworkPreferences(InMemoryPreferencesFactory())
        preferences.setExcluded("regno-unito", setOf("london"))
        preferences.rememberChoice(twoNetworks, setOf(PackageKind.TRANSIT))

        assertEquals(setOf("london"), preferences.excluded.value["regno-unito"])
    }

    @Test
    fun `rememberChoice non salva nulla per una regione senza reti`() {
        val preferences = TransitNetworkPreferences(InMemoryPreferencesFactory())
        preferences.rememberChoice(entry(null), setOf(PackageKind.TRANSIT))

        assertTrue(preferences.excluded.value.isEmpty())
    }
}
