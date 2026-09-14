package com.pockettravel.pipeline

import org.junit.Assert.assertThrows
import org.junit.Test

class ValidateManifestTest {

    private val allowedHosts = setOf("miracle091.github.io", "brouter.de", "build.protomaps.com")

    private fun validManifest(regionId: String = "san-marino") = """
        {
          "manifestVersion": 1,
          "regions": [{
            "regionId": "$regionId",
            "displayName": "San Marino",
            "version": "2026.09.14",
            "updatedAt": "2026-09-14T00:00:00Z",
            "files": [
              { "name": "content.db", "url": "https://miracle091.github.io/pocket-travel/regions/$regionId/2026.09.14/content.db", "sizeBytes": 100, "sha256": "${"a".repeat(64)}" },
              { "name": "E10_N40.rd5", "url": "https://brouter.de/brouter/segments4/E10_N40.rd5", "sizeBytes": 200, "sha256": "${"b".repeat(64)}" }
            ],
            "mapSource": {
              "sourceUrl": "https://build.protomaps.com/20260914.pmtiles",
              "minLon": 12.40, "minLat": 43.89, "maxLon": 12.52, "maxLat": 43.99,
              "minZoom": 0, "maxZoom": 14
            }
          }]
        }
    """.trimIndent()

    @Test
    fun `un manifest valido non lancia eccezioni`() {
        validateManifestJson(validManifest(), allowedHosts)
    }

    @Test
    fun `rifiuta un host non consentito`() {
        val json = validManifest().replace("brouter.de", "evil.example.com")
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
        val single = validManifest()
        val region = single.substringAfter("\"regions\": [").substringBeforeLast("]")
        val duplicated = """{ "manifestVersion": 1, "regions": [$region, $region] }"""
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(duplicated, allowedHosts) }
    }

    @Test
    fun `rifiuta un manifestVersion non supportata`() {
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson("""{"manifestVersion": 2, "regions": []}""", allowedHosts)
        }
    }
}
