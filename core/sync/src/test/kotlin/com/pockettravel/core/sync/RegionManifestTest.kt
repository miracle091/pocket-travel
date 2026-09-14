package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sampleManifest = """
        {
          "manifestVersion": 1,
          "regions": [
            {
              "regionId": "it-toscana",
              "displayName": "Italia — Toscana",
              "version": "2026.03.01",
              "updatedAt": "2026-03-01T00:00:00Z",
              "files": [
                { "name": "content.db", "url": "https://example.org/it-toscana/content.db", "sizeBytes": 3500000, "sha256": "aa" },
                { "name": "E5_N45.rd5", "url": "https://example.org/it-toscana/E5_N45.rd5", "sizeBytes": 28000000, "sha256": "dd" }
              ],
              "mapSource": {
                "sourceUrl": "https://build.protomaps.com/20260901.pmtiles",
                "minLon": 10.0, "minLat": 42.0, "maxLon": 12.0, "maxLat": 44.0,
                "minZoom": 0, "maxZoom": 14
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses manifest json into region entries`() {
        val manifest = json.decodeFromString(RegionManifest.serializer(), sampleManifest)

        assertEquals(1, manifest.manifestVersion)
        assertEquals(1, manifest.regions.size)

        val region = manifest.regions.first()
        assertEquals("it-toscana", region.regionId)
        assertEquals("Italia — Toscana", region.displayName)
        assertEquals("2026.03.01", region.version)
        assertEquals(2, region.files.size)
    }

    @Test
    fun `sums file sizes into the total package size`() {
        val manifest = json.decodeFromString(RegionManifest.serializer(), sampleManifest)

        assertEquals(3_500_000L + 28_000_000L, manifest.regions.first().sizeBytes)
    }

    @Test
    fun `parses mapSource for device-side pmtiles extraction`() {
        val manifest = json.decodeFromString(RegionManifest.serializer(), sampleManifest)

        val mapSource = manifest.regions.first().mapSource
        assertEquals("https://build.protomaps.com/20260901.pmtiles", mapSource?.sourceUrl)
        assertEquals(14, mapSource?.maxZoom)
    }

    @Test
    fun `ignores unknown fields for forward compatibility`() {
        val manifestWithExtraField = sampleManifest.replaceFirst(
            "\"manifestVersion\": 1,",
            "\"manifestVersion\": 1, \"generatedBy\": \"pipeline-x\",",
        )

        val manifest = json.decodeFromString(RegionManifest.serializer(), manifestWithExtraField)

        assertEquals(1, manifest.regions.size)
    }
}
