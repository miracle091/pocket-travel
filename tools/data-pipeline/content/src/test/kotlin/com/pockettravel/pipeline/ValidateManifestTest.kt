package com.pockettravel.pipeline

import org.junit.Assert.assertThrows
import org.junit.Test

class ValidateManifestTest {

    private val allowedHosts = setOf("miracle091.github.io", "github.com", "build.protomaps.com")

    private fun region(regionId: String = "san-marino") = """
        {
          "regionId": "$regionId",
          "displayName": "San Marino",
          "updatedAt": "2026-09-14T00:00:00Z",
          "map": {
            "version": "2026.09.14",
            "source": {
              "sourceUrl": "https://build.protomaps.com/20260914.pmtiles",
              "minLon": 12.40, "minLat": 43.89, "maxLon": 12.52, "maxLat": 43.99,
              "minZoom": 0, "maxZoom": 14
            }
          },
          "routing": {
            "version": "2026.09.14",
            "files": [
              { "name": "E10_N40.rd5", "url": "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/$regionId--2026.09.14--E10_N40.rd5", "sizeBytes": 200, "sha256": "${"b".repeat(64)}" }
            ]
          },
          "poi": {
            "version": "2026.09.14",
            "file": { "name": "poi.db", "url": "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/$regionId--2026.09.14--poi.db", "sizeBytes": 100, "sha256": "${"a".repeat(64)}" }
          }
        }
    """.trimIndent()

    private val guides = """
        "guides": {
          "version": "2026.09.23",
          "file": { "name": "guides.db", "url": "https://github.com/miracle091/pocket-travel/releases/download/region-data-guide/guides--2026.09.23--guides.db", "sizeBytes": 900000, "sha256": "${"c".repeat(64)}" }
        }
    """.trimIndent()

    private fun validManifest(vararg regions: String = arrayOf(region())) =
        """{ "manifestVersion": 2, $guides, "regions": [${regions.joinToString(",")}] }"""

    @Test
    fun `un manifest valido non lancia eccezioni`() {
        validateManifestJson(validManifest(), allowedHosts)
    }

    @Test
    fun `rifiuta un host non consentito`() {
        val json = validManifest().replace("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.14--E10_N40.rd5", "https://evil.example.com/E10_N40.rd5")
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta uno sha256 malformato`() {
        val json = validManifest().replace("a".repeat(64), "not-a-hash")
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta un bounding box invertito`() {
        val json = validManifest().replace("\"minLon\": 12.40", "\"minLon\": 99.0")
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta regionId duplicati tra due regioni`() {
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(validManifest(region(), region()), allowedHosts) }
    }

    @Test
    fun `rifiuta un manifest senza pacchetto guide`() {
        val json = """{ "manifestVersion": 2, "regions": [${region()}] }"""
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta un routing senza segmenti`() {
        val json = validManifest().replace(Regex(""""files": \[[^\]]*]"""), "\"files\": []")
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta un manifestVersion non supportata`() {
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(validManifest().replace("\"manifestVersion\": 2", "\"manifestVersion\": 1"), allowedHosts)
        }
    }
}
