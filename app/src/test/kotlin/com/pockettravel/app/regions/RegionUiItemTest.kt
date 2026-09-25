package com.pockettravel.app.regions

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionPackage
import com.pockettravel.core.sync.AddressesPackageEntry
import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionUiItemTest {

    private fun file(name: String, size: Long) = RegionManifestFile(name, "https://github.com/$name", size, "a".repeat(64))

    private val remote = RegionManifestEntry(
        regionId = "italia",
        displayName = "Italia",
        updatedAt = "2026-09-23T00:00:00Z",
        map = MapPackageEntry("m2", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.0, 36.0, 19.0, 47.0, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E5_N45.rd5", 80_000_000))),
        poi = PoiPackageEntry("p2", file("poi.db", 60_000_000)),
    )

    private fun local(map: String?, routing: String?, poi: String?, addresses: String? = null, poiExtra: String? = null) =
        RegionPackage(
            "italia", "Italia", "it", mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = poiExtra,
            addressesVersion = addresses, poiSizeBytes = poi?.let { 60_000_000L }, poiExtraSizeBytes = poiExtra?.let { 1_000_000L }, sizeBytes = 123,
        )

    private val withAddresses = remote.copy(addresses = AddressesPackageEntry("a1", file("addresses.pmtiles", 50_000_000)))

    private val noBytes: (RegionPackage, PackageKind) -> Long? = { _, _ -> null }

    @Test
    fun `una regione non installata mostra la dimensione di tutti i pacchetti`() {
        val item = regionUiItem(remote, null, noBytes)

        assertEquals(RegionStatus.NOT_INSTALLED, item.status)
        assertEquals(140_000_000L, item.sizeBytes)
        assertEquals(List(3) { RegionStatus.NOT_INSTALLED }, item.packages.map { it.status })
    }

    @Test
    fun `da aggiornare mostra la dimensione dei soli pacchetti installati e cambiati`() {
        val item = regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p1"), noBytes)

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(60_000_000L, item.sizeBytes)
        assertEquals(
            listOf(RegionStatus.INSTALLED, RegionStatus.INSTALLED, RegionStatus.UPDATE_AVAILABLE),
            item.packages.map { it.status },
        )
    }

    @Test
    fun `un pacchetto non installato non conta come aggiornamento`() {
        val installed = local(map = null, routing = "r1", poi = "p2")
        val item = regionUiItem(remote, installed, noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(123L, item.sizeBytes)
        assertEquals(RegionStatus.NOT_INSTALLED, item.packages.first { it.kind == PackageKind.MAP }.status)
        assertEquals(emptySet<PackageKind>(), outdatedKinds(remote, installed))
    }

    @Test
    fun `la dimensione installata di ogni pacchetto arriva dal repository`() {
        val item = regionUiItem(remote, local("m2", "r1", "p2")) { _, kind -> kind.ordinal.toLong() + 1 }

        assertEquals(listOf(1L, 2L, 3L), item.packages.map { it.installedBytes })
    }

    @Test
    fun `i civici offerti dal manifest contano nella prima installazione`() {
        val item = regionUiItem(withAddresses, null, noBytes)

        assertEquals(190_000_000L, item.sizeBytes)
        assertEquals(listOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI, PackageKind.ADDRESSES), item.packages.map { it.kind })
    }

    @Test
    fun `civici nuovi su una regione installata non sono un aggiornamento`() {
        val item = regionUiItem(withAddresses, local(map = "m2", routing = "r1", poi = "p2"), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(RegionStatus.NOT_INSTALLED, item.packages.first { it.kind == PackageKind.ADDRESSES }.status)
    }

    @Test
    fun `civici installati ma non piu' offerti restano eliminabili e non chiedono aggiornamenti`() {
        val item = regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p2", addresses = "a1"), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(RegionStatus.INSTALLED, item.packages.first { it.kind == PackageKind.ADDRESSES }.status)
        assertEquals(emptyList<PackageKind>(), item.unavailableKinds)
    }

    @Test
    fun `civici non offerti e non installati sono segnalati come non disponibili`() {
        val item = regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p2"), noBytes)

        assertEquals(listOf(PackageKind.ADDRESSES), item.unavailableKinds)
        assertEquals(listOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), item.packages.map { it.kind })
    }

    @Test
    fun `civici offerti non sono tra i non disponibili`() {
        assertEquals(emptyList<PackageKind>(), regionUiItem(withAddresses, null, noBytes).unavailableKinds)
    }

    private val withPoiExtra = remote.copy(poiExtra = PoiPackageEntry("p2", file("poi-extra.db", 5_000_000)))

    @Test
    fun `i POI extra si offrono nel foglio ma non entrano nel download completo`() {
        val item = regionUiItem(withPoiExtra, null, noBytes)

        assertEquals(140_000_000L, item.sizeBytes)
        assertEquals(RegionStatus.NOT_INSTALLED, item.packages.first { it.kind == PackageKind.POI_EXTRA }.status)
        assertEquals(5_000_000L, item.packages.first { it.kind == PackageKind.POI_EXTRA }.downloadBytes)
    }

    @Test
    fun `POI extra mancanti non sono segnalati come non disponibili ne' come aggiornamento`() {
        val installed = local(map = "m2", routing = "r1", poi = "p2")

        assertEquals(listOf(PackageKind.ADDRESSES), regionUiItem(remote, installed, noBytes).unavailableKinds)
        assertEquals(RegionStatus.INSTALLED, regionUiItem(withPoiExtra, installed, noBytes).status)
    }

    @Test
    fun `POI extra installati e cambiati sono un aggiornamento`() {
        val item = regionUiItem(withPoiExtra, local(map = "m2", routing = "r1", poi = "p2", poiExtra = "p1"), noBytes)

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(5_000_000L, item.sizeBytes)
    }
}
