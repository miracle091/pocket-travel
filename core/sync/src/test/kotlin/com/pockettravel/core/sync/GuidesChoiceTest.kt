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
        assertEquals("en/2026.09.28.1.1", choice.installedVersion)
        assertTrue(isEnglishGuidesVersion(choice.installedVersion))
    }

    @Test
    fun `in italiano, o senza guide inglesi nel manifest, quelle italiane`() {
        assertEquals(GuidesChoice(italian, "2026.09.28.1.1"), manifest(english).guidesChoice("it"))
        assertEquals(GuidesChoice(italian, "2026.09.28.1.1"), manifest(null).guidesChoice("en"))
        assertFalse(isEnglishGuidesVersion("2026.09.28.1.1"))
        assertFalse(isEnglishGuidesVersion(null))
    }
}
