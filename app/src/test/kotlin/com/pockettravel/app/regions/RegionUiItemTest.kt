package com.pockettravel.app.regions

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.sync.AddressGridCell
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
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.attachTransitFeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
        InstalledRegion(
            "italia", "Italia", "it", mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = poiExtra,
            addressesVersion = addresses, poiSizeBytes = poi?.let { 60_000_000L }, poiExtraSizeBytes = poiExtra?.let { 1_000_000L }, sizeBytes = 123,
        )

    private val withAddresses = remote.copy(
        addressGrid = RegionAddressGridEntry(listOf(AddressGridCell("1/0/0", "a1", file("cell-1-0-0--addresses.pmtiles", 50_000_000)))),
    )

    private val withTransit = remote.copy(
        transit = RegionTransitEntry(
            listOf(
                TransitFeed("mdb-1", "Riga", listOf("italia"), "CC0-1.0", "Riga", null, "v1", file("transit.db", 4_000_000), file("transit.db.xz", 800_000)),
                TransitFeed("mdb-2", "Jurmala", listOf("italia"), "CC0-1.0", "Jurmala", null, "v1", file("transit.db", 1_000_000)),
            ),
        ),
    )

    private val noBytes: (InstalledRegion, PackageKind) -> Long? = { _, _ -> null }

    @Test
    fun `gli orari dei mezzi pubblici sono un pacchetto con i nomi delle reti e il peso, fuori dal download completo`() {
        val item = regionUiItem(withTransit, null, noBytes)

        assertEquals(60_000_000L, item.sizeBytes)
        val transit = item.packages.first { it.kind == PackageKind.TRANSIT }
        // Piu' reti: una casella ciascuna invece della riga con i nomi.
        assertEquals(null, transit.detail)
        assertEquals(
            listOf(TransitNetworkUi("mdb-1", "Riga", 800_000, true), TransitNetworkUi("mdb-2", "Jurmala", 1_000_000, true)),
            transit.networks,
        )
        assertEquals(1_800_000L, transit.downloadBytes)
        assertEquals(RegionStatus.NOT_INSTALLED, transit.status)
        assertEquals("solo i civici sono 'non disponibili'", listOf(PackageKind.ADDRESSES), item.unavailableKinds)
        assertEquals(null, item.packages.first { it.kind == PackageKind.MAP }.detail)
    }

    @Test
    fun `una rete tolta non si scarica e la sua casella resta, vuota`() {
        val index = TransitIndex("v1", withTransit.transit!!.feeds)
        val chosen = attachTransitFeeds(listOf(remote), index, mapOf("italia" to setOf("mdb-2"))).single()
        val transit = regionUiItem(chosen, null, noBytes).packages.first { it.kind == PackageKind.TRANSIT }

        assertEquals(800_000L, transit.downloadBytes)
        assertEquals(listOf(true, false), transit.networks.map { it.selected })
    }

    @Test
    fun `una regione installata senza gli orari resta installata, con gli orari vecchi e' da aggiornare`() {
        assertEquals(RegionStatus.INSTALLED, regionUiItem(withTransit, local(map = "m2", routing = "r1", poi = "p2"), noBytes).status)

        val old = local(map = "m2", routing = "r1", poi = "p2").copy(transitVersion = "transit-vecchia")
        val item = regionUiItem(withTransit, old, noBytes)
        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(1_800_000L, item.sizeBytes)
        assertEquals(setOf(PackageKind.TRANSIT), outdatedKinds(withTransit, old))
    }

    @Test
    fun `una regione non installata mostra la dimensione del download completo, senza percorsi`() {
        val item = regionUiItem(remote, null, noBytes)

        assertEquals(RegionStatus.NOT_INSTALLED, item.status)
        assertEquals(60_000_000L, item.sizeBytes)
        assertEquals(List(3) { RegionStatus.NOT_INSTALLED }, item.packages.map { it.status })
    }

    @Test
    fun `con le indicazioni il download completo comprende i percorsi`() {
        val item = regionUiItem(remote, null, noBytes, withRouting = true)

        assertEquals(140_000_000L, item.sizeBytes)
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), remote.downloadKinds(withRouting = true))
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), remote.downloadKinds(withRouting = false))
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
    fun `la mappa da scaricare si segnala a parte, il suo peso non e' nei byte`() {
        assertEquals(true, regionUiItem(remote, null, noBytes).includesMap)
        // Solo i POI da aggiornare: niente mappa.
        assertEquals(false, regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p1"), noBytes).includesMap)

        val onlyMap = regionUiItem(remote, local(map = "m1", routing = "r1", poi = "p2"), noBytes)
        assertEquals(RegionStatus.UPDATE_AVAILABLE, onlyMap.status)
        assertEquals(true, onlyMap.includesMap)
        assertEquals(0L, onlyMap.sizeBytes)
        assertEquals(false, regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p2"), noBytes).includesMap)
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
        val item = regionUiItem(remote, local("m2", "r1", "p2"), installedBytes = { _, kind -> kind.ordinal.toLong() + 1 })

        assertEquals(listOf(1L, 2L, 3L), item.packages.map { it.installedBytes })
    }

    @Test
    fun `i civici offerti dal manifest contano nella prima installazione`() {
        val item = regionUiItem(withAddresses, null, noBytes)

        assertEquals(110_000_000L, item.sizeBytes)
        assertEquals(listOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI, PackageKind.ADDRESSES), item.packages.map { it.kind })
    }

    @Test
    fun `civici nuovi su una regione installata non sono un aggiornamento`() {
        val item = regionUiItem(withAddresses, local(map = "m2", routing = "r1", poi = "p2"), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(RegionStatus.NOT_INSTALLED, item.packages.first { it.kind == PackageKind.ADDRESSES }.status)
    }

    @Test
    fun `civici installati con una version legacy (percorso di oggi) sono un aggiornamento quando il manifest offre la griglia`() {
        val item = regionUiItem(withAddresses, local(map = "m2", routing = "r1", poi = "p2", addresses = "2024-01-15"), noBytes)

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.packages.first { it.kind == PackageKind.ADDRESSES }.status)
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

        assertEquals(60_000_000L, item.sizeBytes)
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

    @Test
    fun `una regione installata e sostituita nel manifest viene proposta con il suo gruppo`() {
        val oldRegion = InstalledRegion(
            "stati-uniti", "Stati Uniti (contigui)", "us", mapVersion = "m1", routingVersion = "r1", poiVersion = "p1",
            poiExtraVersion = null, addressesVersion = null, poiSizeBytes = 1, poiExtraSizeBytes = null, sizeBytes = 2_000,
        )
        val replaced = listOf(ReplacedRegion("stati-uniti", "Stati Uniti d'America"))

        val items = replacedItems(listOf(oldRegion, local("m2", "r1", "p2")), listOf(remote), replaced)

        assertEquals(listOf(ReplacedRegionItem("stati-uniti", "Stati Uniti (contigui)", "us", "Stati Uniti d'America", 2_000)), items)
        // Ancora nel manifest (o non sostituita): nessuna proposta.
        assertEquals(emptyList<ReplacedRegionItem>(), replacedItems(listOf(local("m2", "r1", "p2")), listOf(remote), listOf(ReplacedRegion("italia", "x"))))
    }

    @Test
    fun `una regione di una nazione divisa si riconosce dal nome, anche tradotto`() {
        assertTrue(isSplitCountryName("Francia - Bretagna", "fr", Locale.ITALIAN))
        assertTrue(isSplitCountryName("France - Bretagna", "fr", Locale.ENGLISH))
        assertTrue(isSplitCountryName("Stati Uniti - Alaska", "us", Locale.ITALIAN))
        assertFalse(isSplitCountryName("Francia", "fr", Locale.ITALIAN))
        assertFalse(isSplitCountryName("Sint Maarten (Paesi Bassi)", "sx", Locale.ITALIAN))
        assertFalse(isSplitCountryName("Francia - Bretagna", null, Locale.ITALIAN))
        assertTrue(RegionUiItem("x", "Qualsiasi", 0, RegionStatus.INSTALLED, groupName = "Francia").splitCountry)
    }
}
