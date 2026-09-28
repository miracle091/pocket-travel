package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sha = "a".repeat(64)

    private val sampleManifest = """
        {
          "manifestVersion": 2,
          "guides": {
            "version": "2026.09.20",
            "file": { "name": "guides.db", "url": "https://github.com/o/r/releases/download/region-data/guides.db", "sizeBytes": 900000, "sha256": "$sha" }
          },
          "regions": [
            {
              "regionId": "it-toscana",
              "displayName": "Italia — Toscana",
              "updatedAt": "2026-03-01T00:00:00Z",
              "map": {
                "version": "2026.03.01",
                "source": {
                  "sourceUrl": "https://build.protomaps.com/20260901.pmtiles",
                  "minLon": 10.0, "minLat": 42.0, "maxLon": 12.0, "maxLat": 44.0,
                  "minZoom": 0, "maxZoom": 14
                }
              },
              "routing": {
                "version": "2026.03.02",
                "files": [
                  { "name": "E5_N45.rd5", "url": "https://github.com/o/r/releases/download/region-data/E5_N45.rd5", "sizeBytes": 28000000, "sha256": "$sha" }
                ]
              },
              "poi": {
                "version": "2026.03.03",
                "file": { "name": "poi.db", "url": "https://github.com/o/r/releases/download/region-data/poi.db", "sizeBytes": 3500000, "sha256": "$sha" }
              },
              "continent": "Europa"
            }
          ]
        }
    """.trimIndent()

    private fun parse(text: String = sampleManifest) = json.decodeFromString(RegionManifest.serializer(), text)

    @Test
    fun `parses guides and the three packages of each region`() {
        val manifest = parse()

        assertEquals(2, manifest.manifestVersion)
        assertEquals("2026.09.20", manifest.guides.version)
        assertEquals("guides.db", manifest.guides.file.name)

        val region = manifest.regions.single()
        assertEquals("it-toscana", region.regionId)
        assertEquals("Europa", region.continent)
        assertEquals("2026.03.01", region.versionOf(PackageKind.MAP))
        assertEquals("2026.03.02", region.versionOf(PackageKind.ROUTING))
        assertEquals("2026.03.03", region.versionOf(PackageKind.POI))
        assertEquals("https://build.protomaps.com/20260901.pmtiles", region.map.source.sourceUrl)
        assertEquals(14, region.map.source.maxZoom)
    }

    @Test
    fun `se c'e' la copia compressa delle guide si scarica quella`() {
        fun withXz(url: String) = parse(sampleManifest.replace(
            "\"sizeBytes\": 900000, \"sha256\": \"$sha\" }",
            "\"sizeBytes\": 900000, \"sha256\": \"$sha\" },\n" +
                """"fileXz": { "name": "guides.db.xz", "url": "$url", "sizeBytes": 210000, "sha256": "$sha" }""",
        )).guides
        val guides = withXz("https://github.com/o/r/releases/download/region-data/guides.db.xz")
        guides.validate()
        assertEquals("guides.db.xz", guides.downloadFile.name)
        assertEquals("guides.db", parse().guides.downloadFile.name)
        assertThrows(IllegalArgumentException::class.java) { withXz("https://evil.example.com/guides.db.xz").validate() }
    }

    @Test
    fun `download size counts only the requested packages, the map is extracted on device`() {
        val region = parse().regions.single()

        assertEquals(28_000_000L, region.downloadBytes(setOf(PackageKind.ROUTING)))
        assertEquals(3_500_000L, region.downloadBytes(setOf(PackageKind.POI)))
        assertEquals(0L, region.downloadBytes(setOf(PackageKind.MAP)))
        assertEquals(31_500_000L, region.downloadBytes(PackageKind.entries.toSet()))
    }

    // Il vecchio pacchetto "addresses" per regione e' stato tolto dall'app (i civici vengono solo
    // dalla griglia, RegionManifestEntry.addressGrid): il manifest pubblicato lo contiene ancora per
    // un periodo, l'app deve continuare a leggerlo (ignoreUnknownKeys) senza offrire i civici.
    private val withLegacyAddresses = sampleManifest.replace(
        "\"continent\": \"Europa\"",
        """"addresses": { "version": "2026.03.04", "file": { "name": "addresses.pmtiles", "url": "https://github.com/o/r/releases/download/region-data/addresses.pmtiles", "sizeBytes": 150000, "sha256": "$sha" } },
              "continent": "Europa"""",
    )

    @Test
    fun `un vecchio oggetto addresses per regione non impedisce di leggere il manifest, ma i civici restano non disponibili`() {
        val region = parse(withLegacyAddresses).regions.single()
        region.validate()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), region.availableKinds)
        assertNull(region.versionOf(PackageKind.ADDRESSES))
    }

    private val withCities = sampleManifest.replace(
        "\"continent\": \"Europa\"",
        """"cities": { "version": "2026.03.06", "file": { "name": "cities.db", "url": "https://github.com/o/r/releases/download/region-data/cities.db", "sizeBytes": 250000, "sha256": "$sha" } },
              "continent": "Europa"""",
    )

    @Test
    fun `le guide di citta' sono facoltative, nel download completo e contano solo se offerte`() {
        val without = parse().regions.single()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), without.availableKinds)
        assertNull(without.versionOf(PackageKind.CITIES))

        val with = parse(withCities).regions.single()
        with.validate()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI, PackageKind.CITIES), with.availableKinds)
        assertEquals("2026.03.06", with.versionOf(PackageKind.CITIES))
        assertEquals(250_000L, with.downloadBytes(setOf(PackageKind.CITIES)))
        assertTrue("le guide di citta' sono nel download completo", PackageKind.CITIES in with.defaultKinds)
    }

    @Test
    fun `se c'e' la copia compressa delle guide di citta' si scarica quella`() {
        fun withXz(url: String) = parse(withCities.replace(
            "\"sizeBytes\": 250000, \"sha256\": \"$sha\" }",
            "\"sizeBytes\": 250000, \"sha256\": \"$sha\" },\n" +
                """"fileXz": { "name": "cities.db.xz", "url": "$url", "sizeBytes": 90000, "sha256": "$sha" }""",
        )).regions.single()
        val region = withXz("https://github.com/o/r/releases/download/region-data/cities.db.xz")
        region.validate()
        assertEquals("cities.db.xz", region.cities!!.downloadFile.name)
        assertEquals(90_000L, region.downloadBytes(setOf(PackageKind.CITIES)))
        assertEquals(250_000L, parse(withCities).regions.single().downloadBytes(setOf(PackageKind.CITIES)))
        assertThrows(IllegalArgumentException::class.java) { withXz("https://evil.example.com/cities.db.xz").validate() }
    }

    @Test
    fun `a valid manifest passes validation`() {
        val manifest = parse()
        manifest.guides.validate()
        manifest.regions.forEach { it.validate() }
    }

    @Test
    fun `validation rejects an unsafe package version`() {
        val region = parse(sampleManifest.replace("\"2026.03.03\"", "\"../x\"")).regions.single()
        assertThrows(IllegalArgumentException::class.java) { region.validate() }
    }

    @Test
    fun `validation rejects a guides file on a host outside the allowlist`() {
        val guides = parse(sampleManifest.replace("https://github.com/o/r/releases/download/region-data/guides.db", "https://evil.example/guides.db")).guides
        assertThrows(IllegalArgumentException::class.java) { guides.validate() }
    }

    @Test
    fun `validation rejects plain http without a debug manifest override`() {
        val region = parse(sampleManifest.replace("https://github.com/o/r/releases/download/region-data/poi.db", "http://github.com/poi.db")).regions.single()
        assertThrows(IllegalArgumentException::class.java) { region.validate() }
    }

    @Test
    fun `validation rejects a region without routing segments`() {
        val region = parse().regions.single()
        val withoutRouting = region.copy(routing = region.routing.copy(files = emptyList()))
        assertThrows(IllegalArgumentException::class.java) { withoutRouting.validate() }
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val manifest = parse(sampleManifest.replaceFirst("\"manifestVersion\": 2,", "\"manifestVersion\": 2, \"generatedBy\": \"pipeline-x\","))
        assertEquals(1, manifest.regions.size)
    }

    @Test
    fun `i POI extra sono facoltativi, convalidati e fuori dal download completo`() {
        val with = parse(sampleManifest.replace(
            "\"continent\": \"Europa\"",
            """"poiExtra": { "version": "2026.03.04", "file": { "name": "poi-extra.db", "url": "https://github.com/o/r/releases/download/region-data/poi-extra.db", "sizeBytes": 70000, "sha256": "$sha" } },
              "continent": "Europa"""",
        )).regions.single()
        with.validate()
        assertEquals("2026.03.04", with.versionOf(PackageKind.POI_EXTRA))
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), with.defaultKinds)
        assertEquals(70_000L, with.downloadBytes(setOf(PackageKind.POI_EXTRA)))
        assertNull(parse().regions.single().versionOf(PackageKind.POI_EXTRA))

        val badHost = parse(sampleManifest.replace(
            "\"continent\": \"Europa\"",
            """"poiExtra": { "version": "2026.03.04", "file": { "name": "poi-extra.db", "url": "https://evil.example.com/poi-extra.db", "sizeBytes": 70000, "sha256": "$sha" } },
              "continent": "Europa"""",
        )).regions.single()
        assertThrows(IllegalArgumentException::class.java) { badHost.validate() }
    }

    @Test
    fun `se c'e' la copia compressa dei POI si scarica quella`() {
        fun withXz(url: String) = parse(sampleManifest.replace(
            "\"sizeBytes\": 3500000, \"sha256\": \"$sha\" }",
            "\"sizeBytes\": 3500000, \"sha256\": \"$sha\" },\n" +
                """"fileXz": { "name": "poi.db.xz", "url": "$url", "sizeBytes": 1200000, "sha256": "$sha" }""",
        )).regions.single()
        val region = withXz("https://github.com/o/r/releases/download/region-data/poi.db.xz")
        region.validate()
        assertEquals("poi.db.xz", region.poi.downloadFile.name)
        assertEquals(1_200_000L, region.downloadBytes(setOf(PackageKind.POI)))
        assertEquals(3_500_000L, parse().regions.single().downloadBytes(setOf(PackageKind.POI)))
        assertThrows(IllegalArgumentException::class.java) { withXz("https://evil.example.com/poi.db.xz").validate() }
    }

    private val withPreview = sampleManifest.replace(
        "\"continent\": \"Europa\"",
        """"preview": { "version": "2026.03.05", "maxZoom": 9, "file": { "name": "preview.pmtiles", "url": "https://github.com/o/r/releases/download/region-data/preview.pmtiles", "sizeBytes": 1200000, "sha256": "$sha" } },
              "continent": "Europa"""",
    )

    @Test
    fun `l'anteprima e' facoltativa, non e' un pacchetto e non conta nel download completo`() {
        val without = parse().regions.single()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), without.availableKinds)

        val with = parse(withPreview).regions.single()
        with.validate()
        assertEquals(9, with.preview!!.maxZoom)
        assertEquals("preview.pmtiles", with.preview.file.name)
        assertEquals("preview.pmtiles", with.preview.downloadFile.name)
        // Non e' un PackageKind: non tocca ne' availableKinds ne' downloadBytes.
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), with.availableKinds)
        assertEquals(31_500_000L, with.downloadBytes(with.availableKinds))

        val badHost = parse(withPreview.replace(
            "https://github.com/o/r/releases/download/region-data/preview.pmtiles",
            "https://evil.example.com/preview.pmtiles",
        )).regions.single()
        assertThrows(IllegalArgumentException::class.java) { badHost.validate() }
    }

    @Test
    fun `se c'e' la copia compressa dell'anteprima si scarica quella`() {
        val withXz = parse(withPreview.replace(
            "\"sizeBytes\": 1200000, \"sha256\": \"$sha\" }",
            "\"sizeBytes\": 1200000, \"sha256\": \"$sha\" },\n" +
                """"fileXz": { "name": "preview.pmtiles.xz", "url": "https://github.com/o/r/releases/download/region-data/preview.pmtiles.xz", "sizeBytes": 900000, "sha256": "$sha" }""",
        )).regions.single()
        withXz.validate()
        assertEquals("preview.pmtiles.xz", withXz.preview!!.downloadFile.name)
    }

    private val withWorldMap = sampleManifest.trimEnd().removeSuffix("}") +
        """, "worldMap": { "version": "2026.09.20", "maxZoom": 8, "url": "https://github.com/o/r/releases/download/world-map/world-2026.09.20-z8.pmtiles", "sizeBytes": 555000000 } }"""

    @Test
    fun `il mondo online e' facoltativo, in cima al manifest`() {
        assertNull(parse().worldMap)

        val manifest = parse(withWorldMap)
        val worldMap = manifest.worldMap!!
        worldMap.validate()
        assertEquals(8, worldMap.maxZoom)
        assertEquals(555_000_000L, worldMap.sizeBytes)

        val badHost = parse(withWorldMap.replace(
            "https://github.com/o/r/releases/download/world-map/world-2026.09.20-z8.pmtiles",
            "https://evil.example.com/world.pmtiles",
        )).worldMap!!
        assertThrows(IllegalArgumentException::class.java) { badHost.validate() }
    }

    private val withAddressGridManifestEntry = sampleManifest.trimEnd().removeSuffix("}") +
        """, "addressGrid": { "version": "2026.10.01.123.1", "url": "https://miracle091.github.io/pocket-travel/address-grid.json", "sizeBytes": 250000, "sha256": "$sha" } }"""

    @Test
    fun `addressGrid e' facoltativo, in cima al manifest`() {
        assertNull(parse().addressGrid)

        val manifest = parse(withAddressGridManifestEntry)
        val addressGrid = manifest.addressGrid!!
        addressGrid.validate()
        assertEquals("2026.10.01.123.1", addressGrid.version)
        assertEquals(250_000L, addressGrid.sizeBytes)

        val badHost = parse(withAddressGridManifestEntry.replace(
            "https://miracle091.github.io/pocket-travel/address-grid.json",
            "https://evil.example.com/address-grid.json",
        )).addressGrid!!
        assertThrows(IllegalArgumentException::class.java) { badHost.validate() }
    }

    private fun addressCellFile(name: String) = RegionManifestFile(name, "https://github.com/o/r/releases/download/address-cells-1/$name", 8_000_000L, sha)

    @Test
    fun `i civici a griglia di una regione sostituiscono la version e la dimensione del percorso di oggi`() {
        val cells = listOf(
            AddressGridCell("12/2178/1500", "2026.09.30.120.1", addressCellFile("cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles")),
            AddressGridCell("12/2179/1500", "2026.09.30.118.2", addressCellFile("cell-12-2179-1500--2026.09.30.118.2--addresses.pmtiles")),
        )
        val region = parse().regions.single().copy(addressGrid = RegionAddressGridEntry(cells))
        region.validate()

        assertEquals(regionAddressesGridVersion(cells), region.versionOf(PackageKind.ADDRESSES))
        assertTrue(region.versionOf(PackageKind.ADDRESSES)!!.startsWith("grid-"))
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI, PackageKind.ADDRESSES), region.availableKinds)
        // Nessuna cella gia' installata: si scaricano entrambe.
        assertEquals(16_000_000L, region.downloadBytes(setOf(PackageKind.ADDRESSES)))
        // Una cella gia' installata alla stessa version: si scarica solo l'altra.
        assertEquals(8_000_000L, region.downloadBytes(setOf(PackageKind.ADDRESSES), mapOf("12/2178/1500" to "2026.09.30.120.1")))
        // Entrambe gia' installate alla stessa version: niente da scaricare.
        assertEquals(
            0L,
            region.downloadBytes(
                setOf(PackageKind.ADDRESSES),
                mapOf("12/2178/1500" to "2026.09.30.120.1", "12/2179/1500" to "2026.09.30.118.2"),
            ),
        )
    }

    @Test
    fun `un id di cella non valido in addressGrid non passa la convalida della regione`() {
        val cells = listOf(AddressGridCell("99/0/0", "v1", addressCellFile("cell.pmtiles")))
        val region = parse().regions.single().copy(addressGrid = RegionAddressGridEntry(cells))
        assertThrows(IllegalArgumentException::class.java) { region.validate() }
    }

    @Test
    fun `RegionManifestEntry con addressGrid sopravvive a un giro di json (RegionPackageDownloadWorker)`() {
        val cells = listOf(AddressGridCell("12/2178/1500", "2026.09.30.120.1", addressCellFile("cell.pmtiles")))
        val region = parse().regions.single().copy(addressGrid = RegionAddressGridEntry(cells))

        val roundTripped = json.decodeFromString(RegionManifestEntry.serializer(), json.encodeToString(RegionManifestEntry.serializer(), region))

        assertEquals(region, roundTripped)
        roundTripped.validate()
    }

    @Test
    fun `legge le regioni sostituite e i gruppi, facoltativi`() {
        assertEquals(emptyList<ReplacedRegion>(), parse().replacedRegions)
        val json = sampleManifest.trimEnd().removeSuffix("}") + """, "replacedRegions": [{ "regionId": "stati-uniti", "groupName": "Stati Uniti d'America" }] }"""
        val manifest = parse(json)
        assertEquals(listOf(ReplacedRegion("stati-uniti", "Stati Uniti d'America")), manifest.replacedRegions)
    }
}
