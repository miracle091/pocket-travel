package com.pockettravel.core.sync

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.TransitFeedInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quali regioni installate hanno un aggiornamento: pacchetti del catalogo, civici a griglia e orari dei mezzi pubblici. */
class RegionUpdateTest {

    private val sha = "a".repeat(64)

    private fun file(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/x/$name", 100, sha)

    // Rettangolo da (0, 0) a (1, 1) gradi: a zoom 1 tocca solo la tile 1/1/0.
    private fun region(regionId: String = "r") = RegionManifestEntry(
        regionId = regionId, displayName = regionId, updatedAt = "2026-10-01T00:00:00Z",
        map = MapPackageEntry("v1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 0.0, 0.0, 1.0, 1.0, 0, 14)),
        routing = RoutingPackageEntry("v1", listOf(file("E0_N0.rd5"))),
        poi = PoiPackageEntry("v1", file("poi.db")),
    )

    private fun cell(id: String, version: String) = AddressGridCell(id, version, file("cell.pmtiles"))

    private fun feed(id: String, version: String) = TransitFeed(
        id, "Rete $id", listOf("r"), "CC0-1.0", "Attribuzione", null, version, file("$id.db"),
    )

    private fun installed(addresses: String? = null, transit: String? = null) = InstalledRegion(
        regionId = "r", displayName = "r", countryCode = null, mapVersion = "v1", routingVersion = "v1", poiVersion = "v1", poiExtraVersion = null,
        addressesVersion = addresses, poiSizeBytes = 1, poiExtraSizeBytes = null, sizeBytes = 1, transitVersion = transit,
    )

    private fun updates(
        local: InstalledRegion?,
        addressIndex: AddressGridIndex? = null,
        transitIndex: TransitIndex? = null,
        installedFeeds: List<String> = emptyList(),
    ) = regionUpdates(
        listOf(region()), listOfNotNull(local).associateBy { it.regionId }, addressIndex, transitIndex, { null },
        { installedFeeds.map { TransitFeedInfo(it, "Rete $it", "Attribuzione") } },
    )

    private fun addresses(version: String) = AddressGridIndex("g", 14, listOf(cell("1/1/0", version)))

    private fun transit(vararg feeds: TransitFeed) = TransitIndex("t", feeds.toList())

    @Test
    fun `una regione non installata o senza novita' non ha aggiornamenti`() {
        assertTrue(updates(null).isEmpty())
        assertTrue(updates(installed()).isEmpty())
    }

    @Test
    fun `una cella dei civici cambiata segnala l'aggiornamento dei civici`() {
        val installedVersion = regionAddressesGridVersion(listOf(cell("1/1/0", "v1")))

        assertTrue(updates(installed(addresses = installedVersion), addresses("v1")).isEmpty())
        val update = updates(installed(addresses = installedVersion), addresses("v2")).single()

        assertEquals(setOf(PackageKind.ADDRESSES), update.kinds)
    }

    @Test
    fun `i civici non installati non sono un aggiornamento`() {
        assertTrue(updates(installed(), addresses("v2")).isEmpty())
    }

    @Test
    fun `una rete dei mezzi installata con una versione nuova segnala l'aggiornamento degli orari`() {
        val installedVersion = regionTransitVersion(listOf(feed("a", "v1")))

        assertTrue(updates(installed(transit = installedVersion), transitIndex = transit(feed("a", "v1")), installedFeeds = listOf("a")).isEmpty())
        val update = updates(installed(transit = installedVersion), transitIndex = transit(feed("a", "v2")), installedFeeds = listOf("a")).single()

        assertEquals(setOf(PackageKind.TRANSIT), update.kinds)
    }

    @Test
    fun `una rete nuova che l'utente non ha scelto non e' un aggiornamento`() {
        val installedVersion = regionTransitVersion(listOf(feed("a", "v1")))

        val index = transit(feed("a", "v1"), feed("b", "v1"))

        assertTrue(updates(installed(transit = installedVersion), transitIndex = index, installedFeeds = listOf("a")).isEmpty())
    }

    @Test
    fun `senza indici civici e mezzi restano fuori dal confronto`() {
        assertTrue(updates(installed(addresses = "grid-x", transit = "transit-x")).isEmpty())
    }
}
