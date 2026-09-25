package com.pockettravel.pipeline

import org.json.JSONObject
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

    private fun withAddresses(url: String) = validManifest().replace(
        "\"poi\": {",
        """"addresses": { "version": "2026.09.24", "file": { "name": "addresses.pmtiles", "url": "$url", "sizeBytes": 146238, "sha256": "${"d".repeat(64)}" } },
          "poi": {""",
    )

    @Test
    fun `accetta i civici facoltativi`() {
        validateManifestJson(withAddresses("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.24--addresses.pmtiles"), allowedHosts)
    }

    @Test
    fun `accetta i POI extra facoltativi e ne controlla l'host`() {
        fun withPoiExtra(url: String) = validManifest().replace(
            "\"poi\": {",
            """"poiExtra": { "version": "2026.09.25", "file": { "name": "poi-extra.db", "url": "$url", "sizeBytes": 1024, "sha256": "${"e".repeat(64)}" } },
              "poi": {""",
        )
        validateManifestJson(withPoiExtra("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.25--poi-extra.db"), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withPoiExtra("https://evil.example.com/poi-extra.db"), allowedHosts)
        }
    }

    @Test
    fun `accetta la copia compressa facoltativa dei POI e ne controlla l'host`() {
        fun withPoiXz(url: String) = validManifest().replace(
            "--poi.db\", \"sizeBytes\": 100, \"sha256\": \"${"a".repeat(64)}\" }",
            "--poi.db\", \"sizeBytes\": 100, \"sha256\": \"${"a".repeat(64)}\" },\n" +
                """"fileXz": { "name": "poi.db.xz", "url": "$url", "sizeBytes": 40, "sha256": "${"f".repeat(64)}" }""",
        )
        validateManifestJson(withPoiXz("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.14--poi.db.xz"), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withPoiXz("https://evil.example.com/poi.db.xz"), allowedHosts)
        }
    }

    @Test
    fun `rifiuta civici su un host non consentito`() {
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withAddresses("https://evil.example.com/addresses.pmtiles"), allowedHosts)
        }
    }

    @Test
    fun `rifiuta un manifestVersion non supportata`() {
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(validManifest().replace("\"manifestVersion\": 2", "\"manifestVersion\": 1"), allowedHosts)
        }
    }

    @Test
    fun `controlla che una regione sostituita non ci sia piu' e che il suo gruppo esista`() {
        val grouped = JSONObject(validManifest()).apply {
            getJSONArray("regions").getJSONObject(0).put("groupName", "Stati Uniti d'America")
        }
        fun withReplaced(regionId: String, groupName: String) = JSONObject(grouped.toString()).put(
            "replacedRegions",
            org.json.JSONArray().put(JSONObject().put("regionId", regionId).put("groupName", groupName)),
        ).toString()
        val presentId = grouped.getJSONArray("regions").getJSONObject(0).getString("regionId")

        validateManifestJson(withReplaced("stati-uniti", "Stati Uniti d'America"), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withReplaced("stati-uniti", "Canada"), allowedHosts)
        }
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withReplaced(presentId, "Stati Uniti d'America"), allowedHosts)
        }
    }
}
