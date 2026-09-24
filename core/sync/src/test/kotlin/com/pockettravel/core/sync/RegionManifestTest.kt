package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `download size counts only the requested packages, the map is extracted on device`() {
        val region = parse().regions.single()

        assertEquals(28_000_000L, region.downloadBytes(setOf(PackageKind.ROUTING)))
        assertEquals(3_500_000L, region.downloadBytes(setOf(PackageKind.POI)))
        assertEquals(0L, region.downloadBytes(setOf(PackageKind.MAP)))
        assertEquals(31_500_000L, region.downloadBytes(PackageKind.entries.toSet()))
    }

    private val withAddresses = sampleManifest.replace(
        "\"continent\": \"Europa\"",
        """"addresses": { "version": "2026.03.04", "file": { "name": "addresses.pmtiles", "url": "https://github.com/o/r/releases/download/region-data/addresses.pmtiles", "sizeBytes": 150000, "sha256": "$sha" } },
              "continent": "Europa"""",
    )

    @Test
    fun `i civici sono facoltativi e contano solo se offerti`() {
        val without = parse().regions.single()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), without.availableKinds)
        assertNull(without.versionOf(PackageKind.ADDRESSES))

        val with = parse(withAddresses).regions.single()
        with.validate()
        assertEquals(PackageKind.entries.toSet(), with.availableKinds)
        assertEquals("2026.03.04", with.versionOf(PackageKind.ADDRESSES))
        assertEquals(150_000L, with.downloadBytes(setOf(PackageKind.ADDRESSES)))
        assertEquals(31_650_000L, with.downloadBytes(with.availableKinds))
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
}
