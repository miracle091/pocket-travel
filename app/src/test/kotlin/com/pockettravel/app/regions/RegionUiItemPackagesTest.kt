package com.pockettravel.app.regions

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.AddressGridCell
import com.pockettravel.core.sync.AddressSearchEntry
import com.pockettravel.core.sync.CitiesPackageEntry
import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionAddressGridEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RegionTransitEntry
import com.pockettravel.core.sync.ReplacedRegion
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.TransitFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class RegionUiItemPackagesTest {
    private fun file(name: String, size: Long) = RegionManifestFile(name, "https://github.com/$name", size, "a".repeat(64))

    private val remote = RegionManifestEntry(
        regionId = "italia",
        displayName = "Italia",
        updatedAt = "2026-09-23T00:00:00Z",
        map = MapPackageEntry("m2", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.0, 36.0, 19.0, 47.0, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E5_N45.rd5", 80_000_000))),
        poi = PoiPackageEntry("p2", file("poi.db", 60_000_000)),
        continent = "Europa",
        countryCode = "it",
    )

    private fun local(
        map: String? = "m2",
        routing: String? = "r1",
        poi: String? = "p2",
        addresses: String? = null,
        cities: String? = null,
        size: Long = 123,
    ) = InstalledRegion(
        "italia", "Italia", "it", mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = null,
        addressesVersion = addresses, poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = size, citiesVersion = cities,
    )

    private val noBytes: (InstalledRegion, PackageKind) -> Long? = { _, _ -> null }

    private val feed = TransitFeed("mdb-1", "Riga", listOf("italia"), "CC0-1.0", "Riga", null, "v1", file("transit.db", 4_000_000))

    private val grid = remote.copy(
        addressGrid = RegionAddressGridEntry(
            listOf(
                AddressGridCell("1/0/0", "a1", file("c1.pmtiles", 50_000_000), search = AddressSearchEntry(file("s1.db", 3_000_000))),
                AddressGridCell("1/0/1", "a1", file("c2.pmtiles", 20_000_000), search = AddressSearchEntry(file("s2.db", 2_000_000))),
            ),
        ),
    )

    @Test
    fun `i civici a griglia da scaricare contano le celle di ricerca`() {
        val item = regionUiItem(grid, null, noBytes)

        assertEquals(75_000_000L, item.packages.first { it.kind == PackageKind.ADDRESSES }.downloadBytes)
        assertEquals(60_000_000L + 75_000_000L, item.sizeBytes)
    }

    @Test
    fun `in un aggiornamento dei civici contano solo le celle nuove o cambiate`() {
        val cells = mapOf("1/0/0" to "a1", "1/0/1" to "vecchia")

        val item = regionUiItem(grid, local(addresses = "2024-01-15"), noBytes, installedAddressCells = { cells })

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(22_000_000L, item.sizeBytes)
        assertEquals(22_000_000L, item.packages.first { it.kind == PackageKind.ADDRESSES }.downloadBytes)
    }

    @Test
    fun `con tutte le celle gia' installate l'aggiornamento dei civici pesa zero`() {
        val cells = mapOf("1/0/0" to "a1", "1/0/1" to "a1")

        val item = regionUiItem(grid, local(addresses = "2024-01-15"), noBytes, installedAddressCells = { cells })

        assertEquals(0L, item.sizeBytes)
    }

    @Test
    fun `senza regione installata le celle installate non si chiedono`() {
        val item = regionUiItem(grid, null, noBytes, installedAddressCells = { error("non si chiede") })

        assertEquals(75_000_000L, item.packages.first { it.kind == PackageKind.ADDRESSES }.downloadBytes)
    }

    @Test
    fun `una regione senza griglia non chiede le celle installate`() {
        val item = regionUiItem(remote, local(), noBytes, installedAddressCells = { error("non si chiede") })

        assertEquals(RegionStatus.INSTALLED, item.status)
    }

    @Test
    fun `la guida delle citta' fa parte del download completo e pesa il file da scaricare`() {
        val withCities = remote.copy(cities = CitiesPackageEntry("c1", file("cities.db", 2_000_000), file("cities.db.xz", 500_000)))

        val item = regionUiItem(withCities, null, noBytes)

        assertEquals(500_000L, item.packages.first { it.kind == PackageKind.CITIES }.downloadBytes)
        assertEquals(60_500_000L, item.sizeBytes)
    }

    @Test
    fun `le citta' installate e cambiate sono un aggiornamento`() {
        val withCities = remote.copy(cities = CitiesPackageEntry("c2", file("cities.db", 2_000_000)))
        val installed = local(cities = "c1")

        val item = regionUiItem(withCities, installed, noBytes)

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(2_000_000L, item.sizeBytes)
        assertEquals(setOf(PackageKind.CITIES), outdatedKinds(withCities, installed))
    }

    @Test
    fun `una sola rete di mezzi pubblici mostra il nome nella riga e nessuna casella`() {
        val item = regionUiItem(remote.copy(transit = RegionTransitEntry(listOf(feed))), null, noBytes)

        val transit = item.packages.first { it.kind == PackageKind.TRANSIT }
        assertEquals("Riga", transit.detail)
        assertEquals(emptyList<TransitNetworkUi>(), transit.networks)
        assertEquals(4_000_000L, transit.downloadBytes)
    }

    @Test
    fun `i pacchetti diversi dai mezzi pubblici non hanno riga ne' motivo`() {
        val packages = regionUiItem(remote, null, noBytes).packages

        assertEquals(listOf<String?>(null, null, null), packages.map { it.detail })
        assertEquals(listOf(null, null, null), packages.map { it.transitDefaultReason })
    }

    @Test
    fun `l'aggiornamento non comprende la rete stradale nemmeno con i percorsi di navigazione attivi`() {
        val item = regionUiItem(remote, local(poi = "p1"), noBytes, withRouting = true)

        assertEquals(60_000_000L, item.sizeBytes)
        assertFalse(item.includesMap)
    }

    @Test
    fun `un aggiornamento di mappa e rete stradale pesa la rete e segnala la mappa`() {
        val item = regionUiItem(remote, local(map = "m1", routing = "r0"), noBytes)

        assertEquals(80_000_000L, item.sizeBytes)
        assertEquals(true, item.includesMap)
    }

    @Test
    fun `una regione installata senza pacchetti mantiene il peso sul dispositivo`() {
        val item = regionUiItem(remote, local(map = null, routing = null, poi = null, size = 9), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(9L, item.sizeBytes)
        assertFalse(item.includesMap)
    }

    @Test
    fun `gruppo e rettangolo del catalogo passano alla voce`() {
        val item = regionUiItem(remote.copy(groupName = "Italia", groupLabel = "Nord"), null, noBytes)

        assertEquals("Italia", item.groupName)
        assertEquals("Nord", item.groupLabel)
        assertEquals(RegionBbox(6.0, 36.0, 19.0, 47.0), item.bbox)
        assertNull(item.zone)
    }

    @Test
    fun `senza regione installata nessun pacchetto e' da aggiornare`() {
        assertEquals(emptySet<PackageKind>(), outdatedKinds(remote, null))
    }

    @Test
    fun `se un gruppo sostituito e' dichiarato due volte vale l'ultimo`() {
        val old = local().copy(regionId = "stati-uniti", displayName = "Stati Uniti")
        val replaced = listOf(ReplacedRegion("stati-uniti", "Primo"), ReplacedRegion("stati-uniti", "Secondo"))

        assertEquals(listOf("Secondo"), replacedItems(listOf(old), listOf(remote), replaced).map { it.groupName })
    }

    @Test
    fun `il trattino senza gli spazi giusti non fa una nazione divisa`() {
        assertFalse(isSplitCountryName("Francia-Bretagna", "fr", Locale.ITALIAN))
        assertFalse(isSplitCountryName("Francia -Bretagna", "fr", Locale.ITALIAN))
    }
}
