package com.pockettravel.core.data

import com.pockettravel.core.data.db.Converters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Piccole regole pure di core/data: zone, scelte della rete stradale, versioni installate, lingua delle guide, destinatari delle sezioni. */
class SmallModelsTest {

    // RegionZone

    @Test
    fun `una zona deve avere minimi sotto i massimi`() {
        RegionZone(10.0, 42.0, 12.0, 44.0)
        listOf(
            { RegionZone(12.0, 42.0, 10.0, 44.0) },
            { RegionZone(10.0, 44.0, 12.0, 42.0) },
            { RegionZone(10.0, 42.0, 10.0, 44.0) },
            { RegionZone(10.0, 42.0, 12.0, 42.0) },
            { RegionZone(Double.NaN, 42.0, 12.0, 44.0) },
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    @Test
    fun `la zona si salva e si rilegge, anche con coordinate negative o molto piccole`() {
        listOf(
            RegionZone(10.0, 42.0, 12.0, 44.0),
            RegionZone(-122.5, 37.2, -121.5, 38.1),
            RegionZone(-0.0000001, -0.0000002, 0.0000003, 0.0000004),
            RegionZone(-180.0, -90.0, 180.0, 90.0),
        ).forEach { assertEquals(it, RegionZone.decode(it.encode())) }
    }

    @Test
    fun `una zona salvata male si scarta invece di far fallire la lettura`() {
        listOf("", "1,2,3", "1,2,3,4,5", "a,b,c,d", "1,2,3,x", "5,5,1,1", "1;2;3;4", "NaN,0,1,1").forEach {
            assertNull(it, RegionZone.decode(it))
        }
    }

    // RoutingVariantChoices

    @Test
    fun `se una regione e' fra solo auto e bici e piedi vince l'auto`() {
        val choices = RoutingVariantChoices(
            carOnly = setOf("italia"), bikeFoot = setOf("italia"), explicit = setOf("italia"), default = RoutingVariantChoice.ALL,
        )
        assertEquals(RoutingVariantChoice.CAR, choices.choiceFor("italia"))
    }

    @Test
    fun `senza scelta esplicita le liste solo auto e bici e piedi non contano`() {
        val choices = RoutingVariantChoices(carOnly = setOf("italia"), bikeFoot = setOf("francia"), default = RoutingVariantChoice.BIKE_FOOT)
        assertEquals(RoutingVariantChoice.BIKE_FOOT, choices.choiceFor("italia"))
        assertEquals(RoutingVariantChoice.BIKE_FOOT, choices.choiceFor("francia"))
        assertFalse(choices.isCarOnly("italia"))
    }

    @Test
    fun `una scelta esplicita senza altre liste vuol dire Tutti`() {
        val choices = RoutingVariantChoices(explicit = setOf("italia"), default = RoutingVariantChoice.CAR)
        assertEquals(RoutingVariantChoice.ALL, choices.choiceFor("italia"))
        assertFalse(choices.isCarOnly("italia"))
        assertTrue(choices.isCarOnly("francia"))
    }

    // InstalledRegion

    private fun installed(
        poi: String? = null,
        poiExtra: String? = null,
    ) = InstalledRegion(
        regionId = "italia", displayName = "Italia", countryCode = "it",
        mapVersion = "m", routingVersion = "r", poiVersion = poi, poiExtraVersion = poiExtra, addressesVersion = "a",
        poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 0, citiesVersion = "c", transitVersion = "t",
    )

    @Test
    fun `la versione di ogni pacchetto e' quella del suo campo`() {
        val region = installed(poi = "p", poiExtra = "x")
        assertEquals(
            mapOf(
                PackageKind.MAP to "m", PackageKind.ROUTING to "r", PackageKind.POI to "p", PackageKind.POI_EXTRA to "x",
                PackageKind.ADDRESSES to "a", PackageKind.CITIES to "c", PackageKind.TRANSIT to "t",
            ),
            PackageKind.entries.associateWith { region.versionOf(it) },
        )
        assertNull(installed().versionOf(PackageKind.POI))
    }

    @Test
    fun `i POI tolti dalla migrazione si riconoscono dalla versione segnaposto`() {
        assertEquals(emptySet<PackageKind>(), installed(poi = "1", poiExtra = "2").poiToRedownload())
        assertEquals(setOf(PackageKind.POI), installed(poi = POI_TO_REDOWNLOAD_VERSION, poiExtra = "2").poiToRedownload())
        assertEquals(setOf(PackageKind.POI_EXTRA), installed(poi = "1", poiExtra = POI_TO_REDOWNLOAD_VERSION).poiToRedownload())
        assertEquals(
            setOf(PackageKind.POI, PackageKind.POI_EXTRA),
            installed(poi = POI_TO_REDOWNLOAD_VERSION, poiExtra = POI_TO_REDOWNLOAD_VERSION).poiToRedownload(),
        )
        assertEquals(emptySet<PackageKind>(), installed().poiToRedownload())
    }

    // Lingua delle guide

    @Test
    fun `le guide sono in inglese solo con l'interfaccia in inglese`() {
        val previous = Locale.getDefault()
        try {
            mapOf(
                Locale.ENGLISH to "en", Locale.UK to "en", Locale.US to "en", Locale.ITALIAN to "it", Locale.FRENCH to "it",
                Locale.GERMAN to "it", Locale.JAPANESE to "it", Locale.ROOT to "it",
            ).forEach { (locale, expected) ->
                Locale.setDefault(locale)
                assertEquals(locale.toString(), expected, currentGuidesLanguage())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    // Destinatari delle sezioni

    @Test
    fun `la nazionalita' della sezione non distingue le maiuscole`() {
        val url = "https://www.gov.uk/foreign-travel-advice/palestine#for-nationality=IL"
        assertTrue(isGuideSectionFor(url, "il"))
        assertTrue(isGuideSectionFor(url, "Il"))
        assertFalse(isGuideSectionFor("https://x.org#not-for-nationality=IL", "il"))
    }

    @Test
    fun `un suffisso vuoto non limita la sezione`() {
        assertTrue(isGuideSectionFor("https://x.org#for-nationality=", "IT"))
        assertTrue(isGuideSectionFor("https://x.org#not-for-nationality=", "IT"))
        assertEquals("https://x.org", guideSourceUrlWithoutAudience("https://x.org#for-nationality="))
    }

    @Test
    fun `il link senza suffisso resta com'e' anche con un frammento qualunque`() {
        assertEquals("https://x.org/p#sezione", guideSourceUrlWithoutAudience("https://x.org/p#sezione"))
        assertEquals("https://x.org/p#sezione", guideSourceUrlWithoutAudience("https://x.org/p#sezione#for-nationality=IL"))
    }

    // Converters

    @Test
    fun `ogni categoria di guida fa il giro del convertitore di Room`() {
        val converters = Converters()
        GuideCategory.entries.forEach { assertEquals(it, converters.toCategory(converters.fromCategory(it))) }
        assertEquals("DA_SAPERE", converters.fromCategory(GuideCategory.DA_SAPERE))
    }
}
