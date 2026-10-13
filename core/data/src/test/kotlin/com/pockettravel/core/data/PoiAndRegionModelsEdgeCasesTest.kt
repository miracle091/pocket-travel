package com.pockettravel.core.data

import com.pockettravel.core.data.db.BTREE_FILL_PERCENT
import com.pockettravel.core.data.db.FIXED_ROW_BYTES
import com.pockettravel.core.data.db.INDEXES_BYTES
import com.pockettravel.core.data.db.InstalledRegionEntity
import com.pockettravel.core.data.db.PERCENT
import com.pockettravel.core.data.db.PoiCodeEntity
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.PoiRegionEntity
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.estimatedStorageBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modelli di InstalledRegion e dei POI: ricerca della versione per pacchetto, pacchetti da riscaricare, stima del peso, valori di default. */
class PoiAndRegionModelsEdgeCasesTest {

    private fun region(
        map: String? = null, routing: String? = null, poi: String? = null, poiExtra: String? = null, addresses: String? = null,
        cities: String? = null, transit: String? = null,
    ) = InstalledRegion(
        regionId = "r", displayName = "R", countryCode = null, mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = poiExtra,
        addressesVersion = addresses, poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 0, citiesVersion = cities, transitVersion = transit,
    )

    // ---- InstalledRegion ----

    @Test
    fun `versionOf rende la versione del pacchetto giusto per ogni tipo`() {
        val all = region(map = "m", routing = "r", poi = "p", poiExtra = "pe", addresses = "a", cities = "c", transit = "t")
        val expected = mapOf(
            PackageKind.MAP to "m", PackageKind.ROUTING to "r", PackageKind.POI to "p", PackageKind.POI_EXTRA to "pe",
            PackageKind.ADDRESSES to "a", PackageKind.CITIES to "c", PackageKind.TRANSIT to "t",
        )
        // Un tipo nuovo di PackageKind senza voce qui fa fallire il test.
        assertEquals(PackageKind.entries.toSet(), expected.keys)
        for ((kind, version) in expected) assertEquals("$kind", version, all.versionOf(kind))
        for (kind in PackageKind.entries) assertNull("$kind", region().versionOf(kind))
    }

    @Test
    fun `versionOf non confonde un pacchetto con un altro quando ne c'e' uno solo`() {
        for (only in PackageKind.entries) {
            val one = when (only) {
                PackageKind.MAP -> region(map = "x")
                PackageKind.ROUTING -> region(routing = "x")
                PackageKind.POI -> region(poi = "x")
                PackageKind.POI_EXTRA -> region(poiExtra = "x")
                PackageKind.ADDRESSES -> region(addresses = "x")
                PackageKind.CITIES -> region(cities = "x")
                PackageKind.TRANSIT -> region(transit = "x")
            }
            assertEquals(listOf(only), PackageKind.entries.filter { one.versionOf(it) != null })
        }
    }

    @Test
    fun `una stringa vuota e' una versione presente, non assente`() {
        assertEquals("", region(map = "").versionOf(PackageKind.MAP))
    }

    @Test
    fun `poiToRedownload elenca solo i pacchetti POI con la versione segnaposto`() {
        assertEquals(emptySet<PackageKind>(), region().poiToRedownload())
        assertEquals(emptySet<PackageKind>(), region(poi = "2026.01", poiExtra = "2026.01").poiToRedownload())
        assertEquals(setOf(PackageKind.POI), region(poi = POI_TO_REDOWNLOAD_VERSION, poiExtra = "1").poiToRedownload())
        assertEquals(setOf(PackageKind.POI_EXTRA), region(poi = null, poiExtra = POI_TO_REDOWNLOAD_VERSION).poiToRedownload())
        assertEquals(
            setOf(PackageKind.POI, PackageKind.POI_EXTRA),
            region(poi = POI_TO_REDOWNLOAD_VERSION, poiExtra = POI_TO_REDOWNLOAD_VERSION).poiToRedownload(),
        )
    }

    @Test
    fun `poiToRedownload ignora il segnaposto negli altri pacchetti e le varianti di scrittura`() {
        assertEquals(
            emptySet<PackageKind>(),
            region(map = POI_TO_REDOWNLOAD_VERSION, routing = POI_TO_REDOWNLOAD_VERSION, cities = POI_TO_REDOWNLOAD_VERSION).poiToRedownload(),
        )
        assertEquals(emptySet<PackageKind>(), region(poi = POI_TO_REDOWNLOAD_VERSION.uppercase()).poiToRedownload())
        assertEquals(emptySet<PackageKind>(), region(poi = " $POI_TO_REDOWNLOAD_VERSION").poiToRedownload())
        assertEquals("da-riscaricare", POI_TO_REDOWNLOAD_VERSION)
    }

    @Test
    fun `i valori di default di InstalledRegion e dell'entita' sono null`() {
        val domain = region()
        assertNull(domain.previewVersion)
        assertNull(domain.citiesVersion)
        assertNull(domain.citiesSizeBytes)
        assertNull(domain.transitVersion)

        val entity = InstalledRegionEntity(
            regionId = "r", displayName = "R", countryCode = null, mapVersion = null, routingVersion = null, poiVersion = null, poiExtraVersion = null,
            addressesVersion = null, previewVersion = null, poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 0, installedAt = 0,
            citiesVersion = null, citiesSizeBytes = null,
        )
        assertNull(entity.transitVersion)
        assertEquals("S", entity.copy(displayName = "S").displayName)
        assertEquals(entity, entity.copy())
    }

    // ---- POI ----

    @Test
    fun `PoiEntity e PoiRow hanno i default previsti`() {
        val entity = PoiEntity(regionId = "r", name = "n", category = "c", lat = 0.0, lon = 0.0, osmTag = "t")
        assertEquals(0L, entity.id)
        assertFalse(entity.extra)
        assertNull(entity.phone)
        assertNull(entity.capacityDisabled)

        val row = PoiRow(regionRef = 1, code = 2, latE6 = 3, lonE6 = 4, name = "n")
        assertEquals(0L, row.id)
        assertFalse(row.extra)
        assertNull(row.nameIt)
        assertEquals(PoiRegionEntity(regionId = "r").ref, 0L)
        assertEquals(PoiCodeEntity(category = "c", osmTag = "t").code, 0L)
    }

    @Test
    fun `PoiRow con coordinate agli estremi del globo e' un valore valido`() {
        val corner = PoiRow(regionRef = 1, code = 1, latE6 = -90_000_000, lonE6 = 180_000_000, name = "Polo")
        assertEquals(-90_000_000L, corner.latE6)
        // La stima non dipende dalle coordinate.
        assertEquals(corner.estimatedStorageBytes(), corner.copy(latE6 = 90_000_000, lonE6 = -180_000_000).estimatedStorageBytes())
    }

    private fun row(name: String = "", vararg others: Pair<String, String?>): PoiRow {
        val o = others.toMap()
        return PoiRow(
            regionRef = 1, code = 1, latE6 = 0, lonE6 = 0, name = name, phone = o["phone"], wheelchair = o["wheelchair"], openingHours = o["openingHours"],
            address = o["address"], website = o["website"], email = o["email"], country = o["country"], nameEn = o["nameEn"], nameIt = o["nameIt"],
            toiletsWheelchair = o["toiletsWheelchair"],
        )
    }

    private fun expected(stringBytes: Long) = (FIXED_ROW_BYTES + stringBytes + INDEXES_BYTES) * BTREE_FILL_PERCENT / PERCENT

    @Test
    fun `la stima di una riga vuota e' la sola parte fissa con il margine dei B-tree`() {
        // (32 + 0 + 36) * 105 / 100 = 71 con la divisione intera.
        assertEquals(71L, row().estimatedStorageBytes())
        assertEquals(expected(0), row().estimatedStorageBytes())
    }

    @Test
    fun `la stima conta ogni colonna di testo e ignora il numero di posti per disabili`() {
        val base = row("ab").estimatedStorageBytes()
        for (column in listOf("phone", "wheelchair", "openingHours", "address", "website", "email", "country", "nameEn", "nameIt", "toiletsWheelchair")) {
            assertEquals(column, expected(2 + 10), row("ab", column to "0123456789").estimatedStorageBytes())
            assertTrue(column, row("ab", column to "0123456789").estimatedStorageBytes() > base)
        }
        assertEquals(base, row("ab").copy(capacityDisabled = Int.MAX_VALUE).estimatedStorageBytes())
        assertEquals(base, row("ab").copy(extra = true).estimatedStorageBytes())
    }

    @Test
    fun `una stringa vuota pesa come una colonna assente`() {
        assertEquals(row("ab").estimatedStorageBytes(), row("ab", "phone" to "").estimatedStorageBytes())
    }

    @Test
    fun `la stima conta i byte UTF-8 anche per cinese ed emoji`() {
        // "東京" sono due caratteri da 3 byte; l'emoji, due unita' UTF-16, sono 4 byte.
        assertEquals(expected(6), row("東京").estimatedStorageBytes())
        assertEquals(expected(4), row("😀").estimatedStorageBytes())
        assertEquals(expected(2), row("è").estimatedStorageBytes())
        // Un surrogato spaiato diventa "?" (1 byte) nella conversione in UTF-8.
        assertEquals(expected(1), row("\ud83d").estimatedStorageBytes())
    }

    @Test
    fun `la stima di un nome enorme non va in overflow e cresce linearmente`() {
        val megabyte = "x".repeat(1_000_000)
        val one = row(megabyte).estimatedStorageBytes()
        assertEquals(expected(1_000_000), one)
        val ten = row(megabyte.repeat(10)).estimatedStorageBytes()
        assertTrue(ten > one)
        assertEquals(expected(10_000_000), ten)
    }

    @Test
    fun `la stima e' monotona nella lunghezza del testo`() {
        var previous = -1L
        for (length in 0..300) {
            val bytes = row("a".repeat(length)).estimatedStorageBytes()
            assertTrue("lunghezza $length", bytes >= previous)
            previous = bytes
        }
    }
}
