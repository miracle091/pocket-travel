package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidesChoiceTest {

    private fun entry(url: String) = GuidesManifestEntry(
        version = "2026.09.28.1.1",
        file = RegionManifestFile("guides.db", url, 1, "a".repeat(64)),
    )

    private val italian = entry("https://github.com/o/r/releases/download/region-data-guide/guides.db")
    private val english = entry("https://github.com/o/r/releases/download/region-data-guide/guides-en.db")

    private fun manifest(guidesEn: GuidesManifestEntry?) =
        RegionManifest(manifestVersion = 2, guides = italian, guidesEn = guidesEn, regions = emptyList())

    @Test
    fun `in inglese le guide inglesi, registrate con il prefisso della lingua`() {
        val choice = manifest(english).guidesChoice("en")
        assertEquals(english, choice.entry)
        assertEquals("en-2026.09.28.1.1", choice.installedVersion)
        assertTrue(isEnglishGuidesVersion(choice.installedVersion))
    }

    @Test
    fun `in italiano, o senza guide inglesi nel manifest, quelle italiane`() {
        assertEquals(GuidesChoice(italian, "2026.09.28.1.1"), manifest(english).guidesChoice("it"))
        assertEquals(GuidesChoice(italian, "2026.09.28.1.1"), manifest(null).guidesChoice("en"))
        assertFalse(isEnglishGuidesVersion("2026.09.28.1.1"))
        assertFalse(isEnglishGuidesVersion(null))
    }

    @Test
    fun `in inglese le citta' di ogni regione sono quelle inglesi, con la versione prefissata`() {
        val file = RegionManifestFile("cities.db", "https://github.com/o/r/releases/download/x/cities.db", 1, "a".repeat(64))
        val region = RegionManifestEntry(
            regionId = "italia", displayName = "Italia", updatedAt = "2026-09-28",
            map = MapPackageEntry("1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.0, 36.0, 19.0, 47.0, 0, 14)),
            routing = RoutingPackageEntry("1", listOf(file)),
            poi = PoiPackageEntry("1", file),
            cities = CitiesPackageEntry("1", file),
            citiesEn = CitiesPackageEntry("2", file.copy(name = "cities-en.db")),
        )
        val manifest = RegionManifest(manifestVersion = 2, guides = italian, regions = listOf(region))
        assertEquals("en-2", manifest.forLanguage("en").regions.single().cities?.version)
        assertEquals("cities-en.db", manifest.forLanguage("en").regions.single().cities?.file?.name)
        assertEquals(manifest, manifest.forLanguage("it"))
    }

    // Elenco letto in inglese, lingua cambiata in italiano prima del download (e viceversa).
    @Test
    fun `le citta' di una voce letta in un'altra lingua si riconoscono`() {
        val file = RegionManifestFile("cities.db", "https://github.com/o/r/releases/download/x/cities.db", 1, "a".repeat(64))
        val region = RegionManifestEntry(
            regionId = "liechtenstein", displayName = "Liechtenstein", updatedAt = "2026-09-28",
            map = MapPackageEntry("1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 9.4, 47.0, 9.7, 47.3, 0, 14)),
            routing = RoutingPackageEntry("1", listOf(file)),
            poi = PoiPackageEntry("1", file),
            cities = CitiesPackageEntry("1", file),
            citiesEn = CitiesPackageEntry("2", file.copy(name = "cities-en.db")),
        )
        val manifest = RegionManifest(manifestVersion = 2, guides = italian, regions = listOf(region))
        val readInEnglish = manifest.forLanguage("en").regions.single()
        val readInItalian = manifest.forLanguage("it").regions.single()

        assertFalse(readInEnglish.hasCitiesIn("it"))
        assertTrue(readInEnglish.hasCitiesIn("en"))
        assertTrue(readInItalian.hasCitiesIn("it"))
        assertFalse(readInItalian.hasCitiesIn("en"))
        // Senza guide delle citta' non c'e' niente da riprendere.
        assertTrue(region.copy(cities = null, citiesEn = null).hasCitiesIn("en"))
    }
}
