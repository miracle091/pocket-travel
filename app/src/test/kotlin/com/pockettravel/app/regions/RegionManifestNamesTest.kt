package com.pockettravel.app.regions

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RegionManifestNamesTest {
    private fun file(name: String, size: Long) = RegionManifestFile(name, "https://github.com/$name", size, "a".repeat(64))

    private val entry = RegionManifestEntry(
        regionId = "francia",
        displayName = "Francia",
        updatedAt = "2026-09-23T00:00:00Z",
        map = MapPackageEntry("m2", MapExtractionSource("https://build.protomaps.com/x.pmtiles", -5.0, 41.0, 10.0, 51.0, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E0_N45.rd5", 80_000_000))),
        poi = PoiPackageEntry("p2", file("poi.db", 60_000_000)),
        countryCode = "fr",
    )

    private val brittany = entry.copy(
        regionId = "francia-bretagna",
        displayName = "Francia - Bretagna",
        groupName = "Francia",
        groupLabel = "Bretagna",
        groupLabelEn = "Brittany",
    )

    private fun installed(map: String?, routing: String?, poi: String?) = InstalledRegion(
        "francia", "Francia", "fr", mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = null,
        addressesVersion = null, poiSizeBytes = poi?.let { 1L }, poiExtraSizeBytes = null, sizeBytes = 500,
    )

    private val noBytes: (InstalledRegion, PackageKind) -> Long? = { _, _ -> null }

    @Test
    fun `la nazione intera prende il nome dal codice nella lingua dell'interfaccia`() {
        assertEquals("France", entry.localizedNames(Locale.ENGLISH).displayName)
        assertEquals("Francia", entry.localizedNames(Locale.ITALIAN).displayName)
    }

    @Test
    fun `senza codice o con un codice sconosciuto il nome resta quello del catalogo`() {
        val noCode = entry.copy(countryCode = null)
        // Non "zz": per il CLDR e' "Unknown Region", un nome vero.
        val unknown = entry.copy(countryCode = "qq")

        assertEquals(noCode, noCode.localizedNames(Locale.ENGLISH))
        assertEquals(unknown, unknown.localizedNames(Locale.ENGLISH))
    }

    @Test
    fun `una regione di una nazione divisa in inglese usa il nome inglese dell'etichetta`() {
        val localized = brittany.localizedNames(Locale.ENGLISH)

        assertEquals("France - Brittany", localized.displayName)
        assertEquals("France", localized.groupName)
        assertEquals("Brittany", localized.groupLabel)
    }

    @Test
    fun `in italiano l'etichetta resta quella del catalogo`() {
        val localized = brittany.localizedNames(Locale.ITALIAN)

        assertEquals("Francia - Bretagna", localized.displayName)
        assertEquals("Francia", localized.groupName)
        assertEquals("Bretagna", localized.groupLabel)
    }

    @Test
    fun `senza il nome inglese dell'etichetta anche l'inglese usa quella del catalogo`() {
        assertEquals("France - Bretagna", brittany.copy(groupLabelEn = null).localizedNames(Locale.ENGLISH).displayName)
    }

    @Test
    fun `senza etichetta il nome della regione resta quello del catalogo`() {
        val localized = brittany.copy(groupLabel = null, groupLabelEn = null).localizedNames(Locale.ENGLISH)

        assertEquals("Francia - Bretagna", localized.displayName)
        assertEquals("France", localized.groupName)
        assertNull(localized.groupLabel)
    }

    @Test
    fun `una regione di una nazione divisa si riconosce anche col nome tradotto`() {
        val localized = brittany.localizedNames(Locale.ENGLISH)

        assertTrue(isSplitCountryName(localized.displayName, localized.countryCode, Locale.ENGLISH))
        assertFalse(isSplitCountryName(entry.localizedNames(Locale.ENGLISH).displayName, "fr", Locale.ENGLISH))
    }

    @Test
    fun `senza catalogo le nazioni installate sono tutte installate e senza nulla da scaricare`() {
        val item = offlineRegionItem(installed(map = "m1", routing = null, poi = "p1")) { _, kind -> if (kind == PackageKind.MAP) 400L else null }

        assertEquals("francia", item.regionId)
        assertEquals("fr", item.countryCode)
        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(500L, item.sizeBytes)
        assertFalse(item.includesMap)
        assertEquals(listOf(PackageKind.MAP, PackageKind.POI), item.packages.map { it.kind })
        assertTrue(item.packages.all { it.status == RegionStatus.INSTALLED && it.downloadBytes == 0L })
        assertEquals(400L, item.packages.first { it.kind == PackageKind.MAP }.installedBytes)
        assertNull(item.packages.first { it.kind == PackageKind.POI }.installedBytes)
    }

    @Test
    fun `una regione installata ha le dimensioni sul dispositivo, non quelle del catalogo`() {
        val item = regionUiItem(entry, installed(map = "m2", routing = "r1", poi = "p2"), noBytes)

        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(500L, item.sizeBytes)
        assertFalse(item.includesMap)
    }

    @Test
    fun `una mappa cambiata e' un aggiornamento che non pesa nei byte ma si segnala`() {
        val item = regionUiItem(entry, installed(map = "m1", routing = "r1", poi = "p2"), noBytes)

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(0L, item.sizeBytes)
        assertTrue(item.includesMap)
        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.packages.first { it.kind == PackageKind.MAP }.status)
        assertEquals(RegionStatus.INSTALLED, item.packages.first { it.kind == PackageKind.POI }.status)
    }

    @Test
    fun `un aggiornamento pesa solo i pacchetti cambiati, anche senza i percorsi di navigazione`() {
        val item = regionUiItem(entry, installed(map = "m2", routing = "r0", poi = "p2"), noBytes, withRouting = false)

        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(80_000_000L, item.sizeBytes)
        assertFalse(item.includesMap)
    }

    @Test
    fun `continente, paese e rettangolo arrivano dal catalogo`() {
        val item = regionUiItem(entry.copy(continent = "Europa"), null, noBytes)

        assertEquals("Europa", item.continent)
        assertEquals("fr", item.countryCode)
        assertEquals(RegionBbox(-5.0, 41.0, 10.0, 51.0), item.bbox)
        assertTrue(item.includesMap)
    }
}
