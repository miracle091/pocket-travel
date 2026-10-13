package com.pockettravel.app.regions

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.ReplacedRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionListLogicEdgeCasesTest {
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
        id: String = "italia",
        map: String? = null,
        routing: String? = null,
        poi: String? = null,
        poiExtra: String? = null,
        country: String? = "it",
        size: Long = 123,
        transit: String? = null,
        cities: String? = null,
    ) = InstalledRegion(
        id, id, country, mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = poiExtra,
        addressesVersion = null, poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = size,
        citiesVersion = cities, transitVersion = transit,
    )

    private val noBytes: (InstalledRegion, PackageKind) -> Long? = { _, _ -> null }

    // matchesQuery

    @Test
    fun `una ricerca di soli spazi mostra tutto`() {
        assertTrue(matchesQuery("Italia", "   "))
        assertTrue(matchesQuery("Italia", "\t\n"))
        assertTrue(matchesQuery("", " "))
    }

    @Test
    fun `un nome vuoto non corrisponde a una ricerca non vuota`() {
        assertFalse(matchesQuery("", "a"))
        assertTrue(matchesQuery("", ""))
    }

    @Test
    fun `la ricerca toglie gli accenti anche dal nome e dalla cediglia`() {
        assertTrue(matchesQuery("Côte d'Ivoire", "cote"))
        assertTrue(matchesQuery("Curaçao", "curacao"))
        assertTrue(matchesQuery("Curacao", "CURAÇAO"))
        assertFalse(matchesQuery("Cura", "curacao"))
    }

    @Test
    fun `la ricerca trova una parte qualsiasi del nome, spazi interni compresi`() {
        assertTrue(matchesQuery("Stati Uniti - Alaska", "uniti - al"))
        assertFalse(matchesQuery("Stati Uniti - Alaska", "uniti-al"))
    }

    // outdatedKinds

    @Test
    fun `senza versione installata non c'e' nulla da aggiornare`() {
        assertEquals(emptySet<PackageKind>(), outdatedKinds(remote, null))
        assertEquals(emptySet<PackageKind>(), outdatedKinds(remote, local()))
    }

    @Test
    fun `una versione uguale a quella del catalogo non e' da aggiornare`() {
        assertEquals(emptySet<PackageKind>(), outdatedKinds(remote, local(map = "m2", routing = "r1", poi = "p2")))
    }

    @Test
    fun `un pacchetto installato che il catalogo non offre piu' non si aggiorna`() {
        // Il catalogo non ha i POI extra: la versione installata non chiede un aggiornamento.
        assertEquals(emptySet<PackageKind>(), outdatedKinds(remote, local(map = "m2", poiExtra = "x1")))
    }

    @Test
    fun `ogni pacchetto cambiato si segnala da solo`() {
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING), outdatedKinds(remote, local(map = "m1", routing = "r0", poi = "p2")))
    }

    // offlineRegionItem

    @Test
    fun `una nazione senza catalogo mostra solo i pacchetti sul dispositivo, tutti installati e senza peso da scaricare`() {
        val item = offlineRegionItem(local(map = "m1", poi = "p1", size = 500)) { _, kind -> kind.ordinal * 10L }

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(500L, item.sizeBytes)
        assertEquals("it", item.countryCode)
        assertEquals(listOf(PackageKind.MAP, PackageKind.POI), item.packages.map { it.kind })
        assertEquals(listOf(0L, 20L), item.packages.map { it.installedBytes })
        assertTrue(item.packages.all { it.status == RegionStatus.INSTALLED && it.downloadBytes == 0L })
        assertEquals(null, item.bbox)
        assertFalse(item.includesMap)
    }

    @Test
    fun `senza pacchetti installati la nazione offline non ha righe`() {
        assertEquals(emptyList<PackageUiState>(), offlineRegionItem(local(), noBytes).packages)
    }

    @Test
    fun `la nazione offline segue l'ordine dei tipi di pacchetto e include citta' e mezzi pubblici`() {
        val item = offlineRegionItem(local(transit = "t1", cities = "c1", poi = "p1"), noBytes)

        assertEquals(listOf(PackageKind.POI, PackageKind.CITIES, PackageKind.TRANSIT), item.packages.map { it.kind })
        assertTrue(item.packages.all { it.installedBytes == null })
    }

    @Test
    fun `una nazione offline senza codice paese lo lascia vuoto`() {
        assertNull(offlineRegionItem(local(country = null), noBytes).countryCode)
    }

    // replacedItems

    @Test
    fun `senza regioni sostituite o installate non ci sono proposte`() {
        assertEquals(emptyList<ReplacedRegionItem>(), replacedItems(emptyList(), listOf(remote), emptyList()))
        assertEquals(emptyList<ReplacedRegionItem>(), replacedItems(listOf(local(id = "stati-uniti")), emptyList(), emptyList()))
        assertEquals(emptyList<ReplacedRegionItem>(), replacedItems(emptyList(), emptyList(), listOf(ReplacedRegion("stati-uniti", "Stati Uniti"))))
    }

    @Test
    fun `una regione sostituita senza catalogo letto e' proposta col suo gruppo`() {
        val items = replacedItems(listOf(local(id = "stati-uniti", country = null, size = 9)), emptyList(), listOf(ReplacedRegion("stati-uniti", "Stati Uniti d'America")))

        assertEquals(listOf(ReplacedRegionItem("stati-uniti", "stati-uniti", null, "Stati Uniti d'America", 9)), items)
    }

    @Test
    fun `le proposte seguono l'ordine delle regioni installate`() {
        val replaced = listOf(ReplacedRegion("b", "Gruppo B"), ReplacedRegion("a", "Gruppo A"))

        val items = replacedItems(listOf(local(id = "a"), local(id = "c"), local(id = "b")), listOf(remote), replaced)

        assertEquals(listOf("a", "b"), items.map { it.regionId })
        assertEquals(listOf("Gruppo A", "Gruppo B"), items.map { it.groupName })
    }

    // regionUiItem

    @Test
    fun `una regione installata e aggiornata mostra il peso sul dispositivo e nessuna mappa da scaricare`() {
        val item = regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p2", size = 777), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(777L, item.sizeBytes)
        assertFalse(item.includesMap)
        assertEquals(RegionBbox(6.0, 36.0, 19.0, 47.0), item.bbox)
        assertEquals("Europa", item.continent)
        assertEquals("it", item.countryCode)
    }

    @Test
    fun `i mezzi pubblici non offerti sono non disponibili solo se l'indice e' stato letto`() {
        val installed = local(map = "m2", routing = "r1", poi = "p2")

        assertEquals(listOf(PackageKind.ADDRESSES), regionUiItem(remote, installed, noBytes).unavailableKinds)
        assertEquals(
            listOf(PackageKind.ADDRESSES, PackageKind.TRANSIT),
            regionUiItem(remote, installed, noBytes, transitKnown = true).unavailableKinds,
        )
    }

    @Test
    fun `i mezzi pubblici installati ma non piu' offerti restano una riga`() {
        val item = regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p2", transit = "transit-1"), noBytes, transitKnown = true)

        assertTrue(PackageKind.TRANSIT in item.packages.map { it.kind })
        assertFalse(PackageKind.TRANSIT in item.unavailableKinds)
        assertEquals(RegionStatus.INSTALLED, item.packages.first { it.kind == PackageKind.TRANSIT }.status)
    }

    @Test
    fun `le guide delle citta' installate restano una riga anche se il catalogo non le offre`() {
        val item = regionUiItem(remote, local(map = "m2", routing = "r1", poi = "p2", cities = "c1"), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.packages.first { it.kind == PackageKind.CITIES }.status)
        assertEquals(0L, item.packages.first { it.kind == PackageKind.CITIES }.downloadBytes)
    }

    @Test
    fun `la dimensione installata si chiede solo per le regioni installate`() {
        var asked = 0
        val countingBytes: (InstalledRegion, PackageKind) -> Long? = { _, _ -> asked++; 1L }

        regionUiItem(remote, null, countingBytes)
        assertEquals(0, asked)

        regionUiItem(remote, local(map = "m2"), countingBytes)
        assertEquals(3, asked)
    }

    @Test
    fun `una regione di una nazione divisa porta il gruppo e l'etichetta del catalogo`() {
        val part = remote.copy(regionId = "francia-bretagna", displayName = "Francia - Bretagna", groupName = "Francia", groupLabel = "Bretagna")

        val item = regionUiItem(part, null, noBytes)

        assertEquals("Francia", item.groupName)
        assertEquals("Bretagna", item.groupLabel)
        assertTrue(item.splitCountry)
    }

    @Test
    fun `una nazione intera non e' divisa`() {
        assertFalse(regionUiItem(remote, null, noBytes).splitCountry)
        assertFalse(RegionUiItem("x", "Italia", 0, RegionStatus.INSTALLED, countryCode = "it").splitCountry)
    }

    @Test
    fun `senza codice paese un nome con trattino non e' una nazione divisa`() {
        assertFalse(isSplitCountryName("Francia - Bretagna", null))
        assertFalse(isSplitCountryName("", "fr"))
    }
}
