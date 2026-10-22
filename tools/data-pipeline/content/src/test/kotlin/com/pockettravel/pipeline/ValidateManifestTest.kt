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
    fun `un catalogo valido non lancia eccezioni`() {
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
    fun `rifiuta un catalogo senza pacchetto guide`() {
        val json = """{ "manifestVersion": 2, "regions": [${region()}] }"""
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta una rete stradale senza segmenti`() {
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
    fun `accetta la mappa d'insieme facoltativa e ne controlla l'host`() {
        val url = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.26--preview.pmtiles.xz"
        validateManifestJson(withPreview(url), allowedHosts)
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(withPreview("https://evil.example.com/preview.pmtiles.xz"), allowedHosts) }
    }

    @Test
    fun `accetta la copia compressa facoltativa della mappa d'insieme e ne controlla l'host`() {
        val goodUrl = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.26--preview.pmtiles.xz"
        fun fileXz(url: String) = ""","fileXz": { "name": "preview.pmtiles.xz", "url": "$url", "sizeBytes": 900000, "sha256": "${"2".repeat(64)}" }"""
        validateManifestJson(withPreview(goodUrl, fileXz(goodUrl)), allowedHosts)
        assertThrows(ManifestValidationException::class.java) {
            validateManifestJson(withPreview(goodUrl, fileXz("https://evil.example.com/preview.pmtiles.xz")), allowedHosts)
        }
    }

    @Test
    fun `rifiuta un maxZoom non valido per la mappa d'insieme`() {
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

    private fun transitFeed(
        id: String = "mdb-3502",
        host: String = "github.com",
        bbox: String = "[23.9, 56.8, 24.4, 57.1]",
        licenseUrl: String = "https://data.gov.lv/dati/lv/dataset/riga",
    ) = """
        { "id": "$id", "name": "Riga", "regions": ["lettonia"], "license": "CC0-1.0", "attribution": "Rigas satiksme", "licenseUrl": "$licenseUrl",
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
            // L'app rifiuta tutto l'indice se una rete ha la pagina della licenza fuori da https.
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed(licenseUrl = "http://acikveri.bizizmir.com/tr/license")}] }""",
            // Lo schema si confronta come nell'app, e senza host l'app scarta lo stesso.
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed(licenseUrl = "HTTPS://data.gov.lv/dati")}] }""",
            """{ "version": "2026.09.29.1", "feeds": [${transitFeed(licenseUrl = "https:///dati")}] }""",
        ).forEach { json -> assertThrows(ManifestValidationException::class.java) { validateTransitJson(json, allowedHosts) } }
    }

    private fun assertManifestInvalid(json: String) {
        assertThrows(ManifestValidationException::class.java) { validateManifestJson(json, allowedHosts) }
    }

    @Test
    fun `rifiuta un catalogo senza regioni`() {
        assertManifestInvalid("""{ "manifestVersion": 2, $guides, "regions": [] }""")
    }

    @Test
    fun `rifiuta un regionId con segmenti di percorso o caratteri non ammessi`() {
        listOf("..", ".", "a/b", "a\\b", "con spazio", "citta'", "").forEach { id ->
            assertManifestInvalid(validManifest(region(id)))
        }
    }

    @Test
    fun `accetta un regionId con punti, trattini e trattino basso`() {
        validateManifestJson(validManifest(region("stati-uniti_1.0")), allowedHosts)
    }

    @Test
    fun `rifiuta una version non valida in guide, mappa e POI`() {
        assertManifestInvalid(validManifest().replace("\"version\": \"2026.09.23\"", "\"version\": \"../x\""))
        val badMapVersion = JSONObject(validManifest()).apply {
            getJSONArray("regions").getJSONObject(0).getJSONObject("map").put("version", "a b")
        }
        assertManifestInvalid(badMapVersion.toString())
        val badPoiVersion = JSONObject(validManifest()).apply {
            getJSONArray("regions").getJSONObject(0).getJSONObject("poi").put("version", "")
        }
        assertManifestInvalid(badPoiVersion.toString())
    }

    @Test
    fun `rifiuta un nome di file con percorso o vuoto`() {
        assertManifestInvalid(validManifest().replace("\"name\": \"poi.db\"", "\"name\": \"../poi.db\""))
        assertManifestInvalid(validManifest().replace("\"name\": \"poi.db\"", "\"name\": \"\""))
        assertManifestInvalid(validManifest().replace("\"name\": \"poi.db\"", "\"name\": \"..\""))
    }

    @Test
    fun `rifiuta una dimensione negativa e accetta zero`() {
        assertManifestInvalid(validManifest().replace("\"sizeBytes\": 100,", "\"sizeBytes\": -1,"))
        validateManifestJson(validManifest().replace("\"sizeBytes\": 100,", "\"sizeBytes\": 0,"), allowedHosts)
    }

    @Test
    fun `rifiuta sha256 di lunghezza sbagliata e accetta le maiuscole`() {
        assertManifestInvalid(validManifest().replace("a".repeat(64), "a".repeat(63)))
        assertManifestInvalid(validManifest().replace("a".repeat(64), "a".repeat(65)))
        validateManifestJson(validManifest().replace("a".repeat(64), "A".repeat(64)), allowedHosts)
    }

    @Test
    fun `rifiuta URL non https, con credenziali, con frammento o malformati`() {
        val good = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.14--poi.db"
        listOf(
            good.replace("https://", "http://"),
            good.replace("https://", "https://utente:pw@"),
            "$good#frammento",
            "https://github.com/con spazio/poi.db",
            "ftp://github.com/poi.db",
            "/relativo/poi.db",
            "",
        ).forEach { url ->
            assertManifestInvalid(validManifest().replace(good, url))
        }
    }

    @Test
    fun `lo schema dell'URL ignora le maiuscole ma l'host deve coincidere esattamente`() {
        val good = "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.14--poi.db"
        validateManifestJson(validManifest().replace(good, good.replace("https://", "HTTPS://")), allowedHosts)
        assertManifestInvalid(validManifest().replace(good, good.replace("github.com", "sub.github.com")))
        assertManifestInvalid(validManifest().replace(good, good.replace("github.com", "github.com.evil.example")))
    }

    @Test
    fun `rifiuta un bounding box fuori dai limiti geografici o senza area`() {
        assertManifestInvalid(validManifest().replace("\"maxLon\": 12.52", "\"maxLon\": 181.0"))
        assertManifestInvalid(validManifest().replace("\"minLon\": 12.40", "\"minLon\": -181.0"))
        assertManifestInvalid(validManifest().replace("\"maxLat\": 43.99", "\"maxLat\": 90.5"))
        assertManifestInvalid(validManifest().replace("\"minLat\": 43.89", "\"minLat\": -91.0"))
        assertManifestInvalid(validManifest().replace("\"maxLon\": 12.52", "\"maxLon\": 12.40"))
        assertManifestInvalid(validManifest().replace("\"maxLat\": 43.99", "\"maxLat\": 43.89"))
    }

    @Test
    fun `accetta un rettangolo che copre esattamente il mondo`() {
        val json = validManifest()
            .replace("\"minLon\": 12.40", "\"minLon\": -180.0").replace("\"maxLon\": 12.52", "\"maxLon\": 180.0")
            .replace("\"minLat\": 43.89", "\"minLat\": -90.0").replace("\"maxLat\": 43.99", "\"maxLat\": 90.0")
        validateManifestJson(json, allowedHosts)
    }

    @Test
    fun `controlla i livelli di zoom della mappa`() {
        assertManifestInvalid(validManifest().replace("\"maxZoom\": 14", "\"maxZoom\": 23"))
        assertManifestInvalid(validManifest().replace("\"minZoom\": 0", "\"minZoom\": -1"))
        assertManifestInvalid(validManifest().replace("\"minZoom\": 0, \"maxZoom\": 14", "\"minZoom\": 10, \"maxZoom\": 9"))
        validateManifestJson(validManifest().replace("\"minZoom\": 0, \"maxZoom\": 14", "\"minZoom\": 22, \"maxZoom\": 22"), allowedHosts)
        validateManifestJson(validManifest().replace("\"minZoom\": 0, \"maxZoom\": 14", "\"minZoom\": 0, \"maxZoom\": 0"), allowedHosts)
    }

    @Test
    fun `rifiuta segmenti della rete stradale con nome duplicato`() {
        val file = """{ "name": "E10_N40.rd5", "url": "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.14--E10_N40.rd5", "sizeBytes": 200, "sha256": "${"b".repeat(64)}" }"""
        assertManifestInvalid(validManifest().replace(file, "$file, $file"))
    }

    @Test
    fun `rifiuta minAppVersionCode negativo`() {
        assertManifestInvalid(validManifest().replace("\"manifestVersion\": 2,", "\"manifestVersion\": 2, \"minAppVersionCode\": -3,"))
    }

    @Test
    fun `la mappa del mondo rifiuta zoom, dimensione e version non validi`() {
        val url = "https://github.com/miracle091/pocket-travel/releases/download/world-map/world-20260926-z8.pmtiles"
        assertManifestInvalid(withWorldMap(url).replace("\"maxZoom\": 8", "\"maxZoom\": 23"))
        assertManifestInvalid(withWorldMap(url).replace("\"maxZoom\": 8", "\"maxZoom\": -1"))
        assertManifestInvalid(withWorldMap(url).replace("555000000", "-5"))
        assertManifestInvalid(withWorldMap(url).replace("\"version\": \"20260926\"", "\"version\": \"x/y\""))
        validateManifestJson(withWorldMap(url).replace("\"maxZoom\": 8", "\"maxZoom\": 22"), allowedHosts)
    }

    @Test
    fun `l'indice dei civici nel catalogo rifiuta sha256, dimensione e version non validi`() {
        val url = "https://miracle091.github.io/pocket-travel/address-grid.json"
        assertManifestInvalid(withAddressGrid(url).replace("7".repeat(64), "xyz"))
        assertManifestInvalid(withAddressGrid(url).replace("\"sizeBytes\": 300000", "\"sizeBytes\": -1"))
        assertManifestInvalid(withAddressGrid(url).replace("2026.09.30.1", "2026 09"))
    }

    @Test
    fun `il puntatore alle reti di mezzi pubblici e' facoltativo e controllato`() {
        fun withTransit(url: String, sha: String = "8".repeat(64), size: Long = 5000) =
            """{ "manifestVersion": 2, $guides, "transit": { "version": "2026.09.29.1", "url": "$url", "sizeBytes": $size, "sha256": "$sha" }, "regions": [${region()}] }"""
        val good = "https://miracle091.github.io/pocket-travel/transit.json"
        validateManifestJson(withTransit(good), allowedHosts)
        assertManifestInvalid(withTransit("https://evil.example.com/transit.json"))
        assertManifestInvalid(withTransit(good, sha = "zz"))
        assertManifestInvalid(withTransit(good, size = -1))
    }

    @Test
    fun `la guida inglese facoltativa e' validata come quella italiana`() {
        fun withGuidesEn(url: String, version: String = "2026.09.23") =
            """{ "manifestVersion": 2, $guides, "guidesEn": { "version": "$version", "file": { "name": "guides-en.db", "url": "$url", "sizeBytes": 10, "sha256": "${"9".repeat(64)}" } }, "regions": [${region()}] }"""
        val good = "https://github.com/miracle091/pocket-travel/releases/download/region-data-guide/guides-en--2026.09.23--guides-en.db"
        validateManifestJson(withGuidesEn(good), allowedHosts)
        assertManifestInvalid(withGuidesEn("https://evil.example.com/guides-en.db"))
        assertManifestInvalid(withGuidesEn(good, version = "a/b"))
    }

    @Test
    fun `le citta' inglesi facoltative sono validate come quelle italiane`() {
        fun withCitiesEn(url: String) = validManifest().replace(
            "\"poi\": {",
            """"citiesEn": { "version": "2026.09.27", "file": { "name": "cities-en.db", "url": "$url", "sizeBytes": 10, "sha256": "${"5".repeat(64)}" } },
              "poi": {""",
        )
        validateManifestJson(withCitiesEn("https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/san-marino--2026.09.27--cities-en.db"), allowedHosts)
        assertManifestInvalid(withCitiesEn("https://evil.example.com/cities-en.db"))
    }

    @Test
    fun `rifiuta una regione sostituita con regionId non sicuro o gruppo inesistente`() {
        val grouped = JSONObject(validManifest()).apply {
            getJSONArray("regions").getJSONObject(0).put("groupName", "Gruppo")
        }
        fun withReplaced(entry: JSONObject) = JSONObject(grouped.toString())
            .put("replacedRegions", org.json.JSONArray().put(entry)).toString()
        assertManifestInvalid(withReplaced(JSONObject().put("regionId", "../x").put("groupName", "Gruppo")))
        assertManifestInvalid(withReplaced(JSONObject().put("regionId", "ex").put("groupName", "")))
        // Un groupName bianco sulla regione non conta come gruppo esistente.
        val blankGroup = JSONObject(validManifest()).apply {
            getJSONArray("regions").getJSONObject(0).put("groupName", "  ")
            put("replacedRegions", org.json.JSONArray().put(JSONObject().put("regionId", "ex").put("groupName", "  ")))
        }
        assertManifestInvalid(blankGroup.toString())
    }

    @Test
    fun `validateRegion restituisce il regionId`() {
        org.junit.Assert.assertEquals("san-marino", validateRegion(JSONObject(region()), allowedHosts))
    }

    @Test
    fun `un JSON malformato o con campi mancanti lancia JSONException`() {
        assertThrows(org.json.JSONException::class.java) { validateManifestJson("non e' json", allowedHosts) }
        assertThrows(org.json.JSONException::class.java) { validateManifestJson("""{ "guides": {} }""", allowedHosts) }
    }

    // --- address-grid.json ---

    private fun assertGridInvalid(json: String) {
        assertThrows(ManifestValidationException::class.java) { validateAddressGridJson(json, allowedHosts) }
    }

    @Test
    fun `l'indice dei civici rifiuta id di cella malformati o fuori intervallo`() {
        listOf("11/1", "11/1/1/1", "a/b/c", "11/x/1", "-1/0/0", "11/2048/0", "11/0/2048", "11/-1/0", "0/1/0", "").forEach { id ->
            assertGridInvalid(addressGridIndex(gridCell(id)))
        }
    }

    @Test
    fun `l'indice dei civici accetta i confini dell'intervallo valido`() {
        validateAddressGridJson(addressGridIndex(gridCell("0/0/0")), allowedHosts)
        validateAddressGridJson(addressGridIndex(gridCell("11/2047/2047")), allowedHosts)
    }

    @Test
    fun `l'indice dei civici rifiuta nessuna cella, tileZoom e version non validi`() {
        assertGridInvalid(addressGridIndex())
        assertGridInvalid(addressGridIndex(gridCell("11/1/1")).replace("\"tileZoom\": 14", "\"tileZoom\": 23"))
        assertGridInvalid(addressGridIndex(gridCell("11/1/1")).replace("\"tileZoom\": 14", "\"tileZoom\": -1"))
        assertGridInvalid(addressGridIndex(gridCell("11/1/1")).replace("\"version\": \"2026.09.30.1\", \"tileZoom\"", "\"version\": \"a b\", \"tileZoom\""))
    }

    @Test
    fun `l'indice dei civici ordina le celle per il testo dell'id, come l'app`() {
        // L'app confronta l'elenco con cells.sortedBy { it.id }: "11/0/0" prima di "5/9/9", "4/11/4" prima di "4/9/3".
        validateAddressGridJson(addressGridIndex(gridCell("11/0/0"), gridCell("5/9/9")), allowedHosts)
        assertGridInvalid(addressGridIndex(gridCell("5/9/9"), gridCell("11/0/0")))
        validateAddressGridJson(addressGridIndex(gridCell("4/11/4"), gridCell("4/9/3")), allowedHosts)
        assertGridInvalid(addressGridIndex(gridCell("4/9/3"), gridCell("4/11/4")))
    }

    @Test
    fun `l'indice dei civici rifiuta file con hash, dimensione, host o version non validi nella cella`() {
        assertGridInvalid(addressGridIndex(gridCell("11/1/1").replace("a".repeat(64), "nope")))
        assertGridInvalid(addressGridIndex(gridCell("11/1/1").replace("\"sizeBytes\": 100", "\"sizeBytes\": -5")))
        assertGridInvalid(addressGridIndex(gridCell("11/1/1").replace("https://github.com", "https://evil.example.com")))
        assertGridInvalid(addressGridIndex(gridCell("11/1/1", version = "../v")))
    }

    @Test
    fun `l'indice dei civici controlla hash del file e del fileXz della ricerca`() {
        val cell = gridCellWithSearch("11/1/1")
        assertGridInvalid(addressGridIndex(cell.replace("d".repeat(64), "bad")))
        assertGridInvalid(addressGridIndex(cell.replace("c".repeat(64), "bad")))
    }

    @Test
    fun `l'indice dei civici richiede tutti i campi dell'attribuzione`() {
        val base = addressGridIndex(gridCell("11/1/1"))
        assertThrows(org.json.JSONException::class.java) {
            validateAddressGridJson(base.replace(gridAttributions, """[{ "source": "OSM", "license": "ODbL-1.0" }]"""), allowedHosts)
        }
        assertThrows(org.json.JSONException::class.java) {
            validateAddressGridJson(base.replace(gridAttributions, """[{ "license": "ODbL-1.0", "url": "https://x" }]"""), allowedHosts)
        }
    }

    @Test
    fun `l'antenata si rileva anche a piu' livelli di distanza e le sorelle sono ammesse`() {
        assertGridInvalid(addressGridIndex(gridCell("10/0/0"), gridCell("13/3/3")))
        validateAddressGridJson(addressGridIndex(gridCell("11/0/0"), gridCell("11/0/1"), gridCell("11/1/0")), allowedHosts)
    }

    // --- transit.json ---

    private fun assertTransitInvalid(json: String) {
        assertThrows(ManifestValidationException::class.java) { validateTransitJson(json, allowedHosts) }
    }

    private fun transitIndex(feed: String) = """{ "version": "2026.09.29.1", "feeds": [$feed] }"""

    @Test
    fun `tempo reale - realtimeUrl https solo con gtfsIds, peso non negativo`() {
        fun withRealtime(fields: String) = transitIndex(transitFeed().replace(""""version": """, """$fields, "version": """))
        validateTransitJson(withRealtime(""""gtfsIds": true, "realtimeUrl": "https://gtfsrt.renfe.com/trip_updates.pb", "realtimeSizeBytes": 30000"""), allowedHosts)
        // Peso assente (misura fallita) e id GTFS senza feed: validi.
        validateTransitJson(withRealtime(""""gtfsIds": true, "realtimeUrl": "https://gtfsrt.renfe.com/trip_updates.pb""""), allowedHosts)
        validateTransitJson(withRealtime(""""gtfsIds": true"""), allowedHosts)
        assertTransitInvalid(withRealtime(""""gtfsIds": true, "realtimeUrl": "http://percorsieorari.gtt.to.it/das_gtfsrt/trip_update.aspx""""))
        assertTransitInvalid(withRealtime(""""gtfsIds": true, "realtimeUrl": "https://user:pw@example.org/rt.pb""""))
        assertTransitInvalid(withRealtime(""""gtfsIds": true, "realtimeUrl": "HTTPS://gtfsrt.renfe.com/trip_updates.pb""""))
        assertTransitInvalid(withRealtime(""""realtimeUrl": "https://gtfsrt.renfe.com/trip_updates.pb""""))
        assertTransitInvalid(withRealtime(""""gtfsIds": true, "realtimeUrl": "https://gtfsrt.renfe.com/trip_updates.pb", "realtimeSizeBytes": -1"""))
    }

    @Test
    fun `l'indice delle reti accetta zero reti e una licenseUrl assente o vuota`() {
        validateTransitJson("""{ "version": "2026.09.29.1", "feeds": [] }""", allowedHosts)
        val noLicenseUrl = transitFeed().replace(Regex(""""licenseUrl": "[^"]*","""), "")
        validateTransitJson(transitIndex(noLicenseUrl), allowedHosts)
        validateTransitJson(transitIndex(transitFeed(licenseUrl = "")), allowedHosts)
    }

    @Test
    fun `l'indice delle reti rifiuta version, regioni, licenza e attribuzione non valide`() {
        assertTransitInvalid(transitIndex(transitFeed()).replace("\"version\": \"2026.09.29.1\", \"feeds\"", "\"version\": \"a b\", \"feeds\""))
        assertTransitInvalid(transitIndex(transitFeed().replace("[\"lettonia\"]", "[]")))
        assertTransitInvalid(transitIndex(transitFeed().replace("[\"lettonia\"]", "[\"../x\"]")))
        assertTransitInvalid(transitIndex(transitFeed().replace("\"license\": \"CC0-1.0\"", "\"license\": \"  \"")))
        assertTransitInvalid(transitIndex(transitFeed().replace("\"attribution\": \"Rigas satiksme\"", "\"attribution\": \"\"")))
    }

    @Test
    fun `l'indice delle reti rifiuta validUntil non valido`() {
        assertTransitInvalid(transitIndex(transitFeed().replace("2026-12-27", "27/12/2026")))
        assertTransitInvalid(transitIndex(transitFeed().replace("2026-12-27", "2026-02-30")))
        assertTransitInvalid(transitIndex(transitFeed().replace("2026-12-27", "")))
    }

    @Test
    fun `l'indice delle reti controlla il rettangolo`() {
        assertTransitInvalid(transitIndex(transitFeed(bbox = "[23.9, 56.8, 24.4]")))
        assertTransitInvalid(transitIndex(transitFeed(bbox = "[23.9, 56.8, 24.4, 57.1, 1]")))
        assertTransitInvalid(transitIndex(transitFeed(bbox = "[23.9, 58.0, 24.4, 57.1]")))
        // Un rettangolo ridotto a un punto passa: le regole rifiutano solo min > max.
        validateTransitJson(transitIndex(transitFeed(bbox = "[24.0, 57.0, 24.0, 57.0]")), allowedHosts)
    }

    @Test
    fun `l'indice delle reti controlla il file e il fileXz`() {
        assertTransitInvalid(transitIndex(transitFeed().replace("a".repeat(64), "bad")))
        assertTransitInvalid(transitIndex(transitFeed().replace("\"sizeBytes\": 10", "\"sizeBytes\": -1")))
        val xz = """, "fileXz": { "name": "transit.db.xz", "url": "https://evil.example.com/t.xz", "sizeBytes": 5, "sha256": "${"e".repeat(64)}" } }"""
        assertTransitInvalid(transitIndex(transitFeed().removeSuffix("}") + xz))
    }
}
