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
    fun `accetta minAppVersionCode positivo e rifiuta zero`() {
        validateManifestJson(validManifest().replace("\"manifestVersion\": 2,", "\"manifestVersion\": 2, \"minAppVersionCode\": 9,"), allowedHosts)
        val json = validManifest().replace("\"manifestVersion\": 2,", "\"manifestVersion\": 2, \"minAppVersionCode\": 0,")
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
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
    fun `accetta la variante auto facoltativa della rete stradale e ne controlla i file`() {
        val carFile = """{ "name": "E10_N40.rd5", "url": "https://github.com/miracle091/pocket-travel/releases/download/region-data-car-r03/san-marino--2026.09.14--car-E10_N40.rd5", "sizeBytes": 90, "sha256": "${"d".repeat(64)}" }"""
        val withCar = { files: String -> validManifest().replace("\"poi\": {", "\"routingCar\": { \"version\": \"2026.09.14\", \"files\": [$files] },\n  \"poi\": {") }
        validateManifestJson(withCar(carFile), allowedHosts)
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(withCar(""), allowedHosts) }
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(withCar("$carFile, $carFile"), allowedHosts) }
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(withCar(carFile.replace("https://github.com", "https://evil.example.com")), allowedHosts) }
    }

    @Test
    fun `accetta la copia compressa facoltativa delle guide e ne controlla l'host`() {
        fun withGuidesXz(url: String) = validManifest().replace(
            "--guides.db\", \"sizeBytes\": 900000, \"sha256\": \"${"c".repeat(64)}\" }",
            "--guides.db\", \"sizeBytes\": 900000, \"sha256\": \"${"c".repeat(64)}\" },\n" +
                """"fileXz": { "name": "guides.db.xz", "url": "$url", "sizeBytes": 250000, "sha256": "${"4".repeat(64)}" }""",
        )
        validateManifestJson(withGuidesXz("https://github.com/miracle091/pocket-travel/releases/download/region-data-guide/guides--2026.09.23--guides.db.xz"), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withGuidesXz("https://evil.example.com/guides.db.xz"), allowedHosts)
        }
    }

    private fun withAddresses(url: String, fileXz: String = "") = validManifest().replace(
        "\"poi\": {",
        """"addresses": { "version": "2026.09.24", "file": { "name": "addresses.pmtiles", "url": "$url", "sizeBytes": 146238, "sha256": "${"d".repeat(64)}" }$fileXz },
          "poi": {""",
    )

    @Test
    fun `accetta i civici facoltativi`() {
        validateManifestJson(withAddresses("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.24--addresses.pmtiles"), allowedHosts)
    }

    @Test
    fun `accetta la copia compressa facoltativa dei civici e ne controlla l'host`() {
        val goodUrl = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.24--addresses.pmtiles.xz"
        fun fileXz(url: String) = ""","fileXz": { "name": "addresses.pmtiles.xz", "url": "$url", "sizeBytes": 130192, "sha256": "${"3".repeat(64)}" }"""
        validateManifestJson(withAddresses(goodUrl, fileXz(goodUrl)), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withAddresses(goodUrl, fileXz("https://evil.example.com/addresses.pmtiles.xz")), allowedHosts)
        }
    }

    private fun withCities(url: String, fileXz: String = "") = validManifest().replace(
        "\"poi\": {",
        """"cities": { "version": "2026.09.27", "file": { "name": "cities.db", "url": "$url", "sizeBytes": 62000, "sha256": "${"5".repeat(64)}" }$fileXz },
          "poi": {""",
    )

    @Test
    fun `accetta le citta' facoltative`() {
        validateManifestJson(withCities("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.27--cities.db"), allowedHosts)
    }

    @Test
    fun `accetta la copia compressa facoltativa delle citta' e ne controlla l'host`() {
        val goodUrl = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.27--cities.db.xz"
        fun fileXz(url: String) = ""","fileXz": { "name": "cities.db.xz", "url": "$url", "sizeBytes": 15000, "sha256": "${"6".repeat(64)}" }"""
        validateManifestJson(withCities(goodUrl, fileXz(goodUrl)), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withCities(goodUrl, fileXz("https://evil.example.com/cities.db.xz")), allowedHosts)
        }
    }

    @Test
    fun `rifiuta citta' su un host non consentito`() {
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withCities("https://evil.example.com/cities.db"), allowedHosts)
        }
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

    private fun withPreview(url: String, fileXz: String = "") = validManifest().replace(
        "\"poi\": {",
        """"preview": { "version": "2026.09.26", "maxZoom": 9, "file": { "name": "preview.pmtiles", "url": "$url", "sizeBytes": 1200000, "sha256": "${"1".repeat(64)}" }$fileXz },
          "poi": {""",
    )

    @Test
    fun `accetta l'anteprima facoltativa e ne controlla l'host`() {
        val url = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.26--preview.pmtiles.xz"
        validateManifestJson(withPreview(url), allowedHosts)
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(withPreview("https://evil.example.com/preview.pmtiles.xz"), allowedHosts) }
    }

    @Test
    fun `accetta la copia compressa facoltativa dell'anteprima e ne controlla l'host`() {
        val goodUrl = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.26--preview.pmtiles.xz"
        fun fileXz(url: String) = ""","fileXz": { "name": "preview.pmtiles.xz", "url": "$url", "sizeBytes": 900000, "sha256": "${"2".repeat(64)}" }"""
        validateManifestJson(withPreview(goodUrl, fileXz(goodUrl)), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withPreview(goodUrl, fileXz("https://evil.example.com/preview.pmtiles.xz")), allowedHosts)
        }
    }

    @Test
    fun `rifiuta un maxZoom non valido per l'anteprima`() {
        val url = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.26--preview.pmtiles.xz"
        val json = withPreview(url).replace("\"maxZoom\": 9", "\"maxZoom\": 99")
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    private fun withWorldMap(url: String) =
        """{ "manifestVersion": 2, $guides, "worldMap": { "version": "20260926", "maxZoom": 8, "url": "$url", "sizeBytes": 555000000 }, "regions": [${region()}] }"""

    @Test
    fun `accetta la mappa del mondo facoltativa e ne controlla l'host`() {
        validateManifestJson(withWorldMap("https://github.com/miracle091/pocket-travel/releases/download/world-map/world-20260926-z8.pmtiles"), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withWorldMap("https://evil.example.com/world-20260926-z8.pmtiles"), allowedHosts)
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

    private fun withAddressGrid(url: String) =
        """{ "manifestVersion": 2, $guides, "addressGrid": { "version": "2026.09.30.1", "url": "$url", "sizeBytes": 300000, "sha256": "${"7".repeat(64)}" }, "regions": [${region()}] }"""

    @Test
    fun `accetta l'indice dei civici a celle e ne controlla l'host`() {
        validateManifestJson(withAddressGrid("https://miracle091.github.io/pocket-travel/address-grid.json"), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withAddressGrid("https://evil.example.com/address-grid.json"), allowedHosts)
        }
    }

    private fun gridCell(id: String, version: String = "2026.09.30.1") = """
        { "id": "$id", "version": "$version",
          "file": { "name": "cell.pmtiles", "url": "https://github.com/miracle091/pocket-travel/releases/download/address-cells-0/$id/cell.pmtiles", "sizeBytes": 100, "sha256": "${"a".repeat(64)}" },
          "fileXz": { "name": "cell.pmtiles.xz", "url": "https://github.com/miracle091/pocket-travel/releases/download/address-cells-0/$id/cell.pmtiles.xz", "sizeBytes": 40, "sha256": "${"b".repeat(64)}" } }
    """.trimIndent()

    private fun gridCellWithSearch(
        id: String,
        searchXzUrl: String = "https://github.com/miracle091/pocket-travel/releases/download/address-cells-0/$id/cell-search.db.xz",
        withXz: Boolean = true,
    ): String {
        val xz = """, "fileXz": { "name": "cell-search.db.xz", "url": "$searchXzUrl", "sizeBytes": 300, "sha256": "${"d".repeat(64)}" }"""
        val search = """, "search": { "file": { "name": "cell-search.db", "url": "${searchXzUrl.removeSuffix(".xz")}", "sizeBytes": 900, "sha256": "${"c".repeat(64)}" }${if (withXz) xz else ""} }"""
        return gridCell(id).removeSuffix("}") + search + " }"
    }

    private val gridAttributions = """[{ "source": "OpenStreetMap", "license": "ODbL-1.0", "url": "https://www.openstreetmap.org/copyright" }]"""

    private fun addressGridIndex(vararg cells: String) =
        """{ "version": "2026.09.30.1", "tileZoom": 14, "cells": [${cells.joinToString(",")}], "attributions": $gridAttributions }"""

    @Test
    fun `un indice dei civici valido non lancia eccezioni`() {
        validateAddressGridJson(addressGridIndex(gridCell("11/1/1"), gridCell("12/50/50")), allowedHosts)
    }

    @Test
    fun `una cella con indice di ricerca valido non lancia eccezioni, con o senza fileXz`() {
        validateAddressGridJson(addressGridIndex(gridCellWithSearch("11/1/1"), gridCell("12/50/50")), allowedHosts)
        validateAddressGridJson(addressGridIndex(gridCellWithSearch("11/1/1", withXz = false)), allowedHosts)
    }

    @Test
    fun `rifiuta un indice di ricerca con URL non consentito`() {
        assertThrows(ManifestValidationException::class.java) {
            validateAddressGridJson(addressGridIndex(gridCellWithSearch("11/1/1", "https://evil.example.com/cell-search.db.xz")), allowedHosts)
        }
    }

    @Test
    fun `rifiuta un id di cella con zoom oltre 14`() {
        assertThrows(ManifestValidationException::class.java) {
            validateAddressGridJson(addressGridIndex(gridCell("15/1/1")), allowedHosts)
        }
    }

    @Test
    fun `accetta una cella a z14, la singola tile non divisibile oltre`() {
        validateAddressGridJson(addressGridIndex(gridCell("14/1/1")), allowedHosts)
    }

    @Test
    fun `rifiuta una cella antenata di un'altra`() {
        assertThrows(ManifestValidationException::class.java) {
            validateAddressGridJson(addressGridIndex(gridCell("11/1/1"), gridCell("12/2/2")), allowedHosts)
        }
    }

    @Test
    fun `rifiuta celle non ordinate per id`() {
        assertThrows(ManifestValidationException::class.java) {
            validateAddressGridJson(addressGridIndex(gridCell("12/50/51"), gridCell("12/50/50")), allowedHosts)
        }
    }

    @Test
    fun `rifiuta un indice senza attribuzioni`() {
        val json = addressGridIndex(gridCell("11/1/1")).replace(gridAttributions, "[]")
        assertThrows(ManifestValidationException::class.java) { validateAddressGridJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta un id di cella duplicato`() {
        assertThrows(ManifestValidationException::class.java) {
            validateAddressGridJson(addressGridIndex(gridCell("11/1/1"), gridCell("11/1/1")), allowedHosts)
        }
    }

    private fun transitFeed(id: String = "mdb-3502", host: String = "github.com", bbox: String = "[23.9, 56.8, 24.4, 57.1]") = """
        { "id": "$id", "name": "Riga", "regions": ["lettonia"], "license": "CC0-1.0", "attribution": "Rigas satiksme",
          "version": "2026.09.29.1", "validUntil": "2026-12-27", "bbox": $bbox,
          "file": { "name": "transit.db", "url": "https://$host/miracle091/pocket-travel/releases/download/transit-feeds/$id--transit.db", "sizeBytes": 10, "sha256": "${"a".repeat(64)}" } }
    """.trimIndent()

    @Test
    fun `indice delle reti valido e i suoi errori`() {
        validateTransitJson("""{ "version": "2026.09.29.1", "feeds": [${transitFeed()}] }""", allowedHosts)
        listOf(
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed()}, ${transitFeed()}] }""",
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed(host = "evil.example")}] }""",
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed(bbox = "[25, 56.8, 24.4, 57.1]")}] }""",
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed(id = "../x")}] }""",
        ).forEach { json -> assertThrows(ManifestValidationException::class.java) { validateTransitJson(json, allowedHosts) } }
    }
}
