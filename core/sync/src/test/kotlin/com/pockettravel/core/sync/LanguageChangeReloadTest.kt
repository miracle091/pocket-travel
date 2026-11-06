package com.pockettravel.core.sync

import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.sync.testing.FakeManifestClient
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageChangeReloadTest {

    private val file = RegionManifestFile("cities.db", "https://github.com/o/r/releases/download/x/cities.db", 1, "a".repeat(64))
    private val guides = GuidesManifestEntry(version = "1", file = file.copy(name = "guides.db"))

    private fun region(id: String, withEnglish: Boolean = true) = RegionManifestEntry(
        regionId = id, displayName = id, updatedAt = "2026-11-06",
        map = MapPackageEntry("1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.0, 36.0, 19.0, 47.0, 0, 14)),
        routing = RoutingPackageEntry("1", listOf(file)),
        poi = PoiPackageEntry("1", file),
        cities = CitiesPackageEntry("1", file),
        citiesEn = if (withEnglish) CitiesPackageEntry("2", file.copy(name = "cities-en.db")) else null,
    )

    private fun installed(id: String, citiesVersion: String?) = InstalledRegion(
        regionId = id, displayName = id, countryCode = null, mapVersion = "1", routingVersion = "1", poiVersion = "1",
        poiExtraVersion = null, addressesVersion = null, poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 1,
        citiesVersion = citiesVersion,
    )

    private val catalog = RegionManifest(
        manifestVersion = 2, guides = guides, guidesEn = guides.copy(version = "1"),
        regions = listOf(region("corsica"), region("italia"), region("malta", withEnglish = false), region("grecia")),
    )

    @Test
    fun `in italiano si riscaricano le citta' installate in inglese, una sola lettura del catalogo`() = runTest {
        val repository = FakeRegionRepository(listOf(installed("corsica", "en-2"), installed("italia", "1"), installed("grecia", null)))
        val manifests = FakeManifestClient(catalog.forLanguage("it")).apply { saved = catalog.forLanguage("it") }
        val scheduler = FakeRegionSyncScheduler()

        LanguageChangeReload(manifests, repository, scheduler).run("it")

        assertEquals(listOf(listOf("corsica")), scheduler.citiesReloads.map { entries -> entries.map { it.regionId } })
        assertEquals("1", scheduler.citiesReloads.single().single().cities?.version)
        assertEquals(1, manifests.fetchCount)
    }

    @Test
    fun `in inglese si riscaricano le citta' italiane, tranne dove il catalogo non ha quelle inglesi`() = runTest {
        val repository = FakeRegionRepository(listOf(installed("italia", "1"), installed("malta", "1")))
        val manifests = FakeManifestClient(catalog.forLanguage("en")).apply { saved = catalog.forLanguage("en") }
        val scheduler = FakeRegionSyncScheduler()

        LanguageChangeReload(manifests, repository, scheduler).run("en")

        assertEquals(listOf("italia"), scheduler.citiesReloads.single().map { it.regionId })
        assertEquals("en-2", scheduler.citiesReloads.single().single().cities?.version)
    }

    // Malta senza citta' inglesi: a ogni avvio in inglese non si scaricano 3 MB di catalogo per niente.
    @Test
    fun `con le citta' gia' nella lingua giusta il catalogo non si scarica e le ricariche in coda si annullano`() = runTest {
        val repository = FakeRegionRepository(listOf(installed("italia", "en-2"), installed("malta", "1")))
        val manifests = FakeManifestClient(catalog.forLanguage("en")).apply { saved = catalog.forLanguage("en") }
        val scheduler = FakeRegionSyncScheduler()

        LanguageChangeReload(manifests, repository, scheduler).run("en")

        assertEquals(listOf(emptyList<RegionManifestEntry>()), scheduler.citiesReloads)
        assertEquals(0, manifests.fetchCount)
    }

    @Test
    fun `le guide nell'altra lingua si riscaricano su qualunque rete`() = runTest {
        val repository = FakeRegionRepository().apply { this.guides.value = InstalledGuides("en-1", 1) }
        val scheduler = FakeRegionSyncScheduler()

        LanguageChangeReload(FakeManifestClient(), repository, scheduler).run("it")
        LanguageChangeReload(FakeManifestClient(), repository, scheduler).run("en")

        assertEquals(listOf(FakeRegionSyncScheduler.GuidesSyncRequest(onlyOnWifi = false, automatic = false)), scheduler.guidesSyncs)
    }
}
