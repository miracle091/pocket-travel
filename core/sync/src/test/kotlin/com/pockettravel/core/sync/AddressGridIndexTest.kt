package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddressGridIndexTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val sha = "a".repeat(64)

    private fun file(name: String, url: String = "https://github.com/o/r/releases/download/address-cells-1/$name") =
        RegionManifestFile(name, url, 1_000L, sha)

    private fun cell(id: String, version: String = "v1") = AddressGridCell(id, version, file("cell-${id.replace('/', '-')}--addresses.pmtiles"))

    private val sampleIndex = """
        {
          "version": "2026.10.01.123.1",
          "tileZoom": 14,
          "cells": [
            { "id": "12/2178/1500", "version": "2026.09.30.120.1",
              "file":   { "name": "cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles", "url": "https://github.com/o/r/releases/download/address-cells-1/cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles", "sizeBytes": 9000000, "sha256": "$sha" },
              "fileXz": { "name": "cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles.xz", "url": "https://github.com/o/r/releases/download/address-cells-1/cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles.xz", "sizeBytes": 6000000, "sha256": "$sha" } }
          ],
          "attributions": [ { "source": "OpenStreetMap", "license": "ODbL-1.0", "url": "https://www.openstreetmap.org/copyright" } ]
        }
    """.trimIndent()

    private fun parse(text: String = sampleIndex) = json.decodeFromString(AddressGridIndex.serializer(), text)

    @Test
    fun `legge celle e attribuzioni`() {
        val index = parse()

        assertEquals(14, index.tileZoom)
        val cell = index.cells.single()
        assertEquals("12/2178/1500", cell.id)
        assertEquals("cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles.xz", cell.downloadFile.name)
        assertEquals("OpenStreetMap", index.attributions.single().source)
        assertEquals("ODbL-1.0", index.attributions.single().license)
    }

    @Test
    fun `un indice valido passa la convalida`() {
        parse().validate()
    }

    @Test
    fun `attributions e' facoltativo`() {
        val withoutAttributions = parse("""{ "version": "v1", "tileZoom": 14, "cells": [] }""")
        assertEquals(emptyList<AddressGridAttribution>(), withoutAttributions.attributions)
    }

    @Test
    fun `la convalida rifiuta un file su un host fuori dalla lista consentita`() {
        val badHost = parse(sampleIndex.replace(
            "https://github.com/o/r/releases/download/address-cells-1/cell-12-2178-1500--2026.09.30.120.1--addresses.pmtiles\"",
            "https://evil.example.com/cell.pmtiles\"",
        ))
        assertThrows(IllegalArgumentException::class.java) { badHost.validate() }
    }

    // ---- convalida della struttura delle celle (costruite direttamente, senza passare dal json) ----

    @Test
    fun `la convalida rifiuta un id di cella non valido`() {
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("99/0/0")))
        assertThrows(IllegalArgumentException::class.java) { index.validate() }
    }

    @Test
    fun `la convalida rifiuta id di cella duplicati`() {
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("12/2178/1500", "v1"), cell("12/2178/1500", "v2")))
        assertThrows(IllegalArgumentException::class.java) { index.validate() }
    }

    @Test
    fun `la convalida rifiuta una cella discendente di un'altra`() {
        // 12/2180/1500 e' discendente di 10/545/375 (le celle non si sovrappongono mai).
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("10/545/375"), cell("12/2180/1500")))
        assertThrows(IllegalArgumentException::class.java) { index.validate() }
    }

    @Test
    fun `la convalida rifiuta celle non ordinate per id`() {
        val index = AddressGridIndex("v1", 14, cells = listOf(cell("2/1/0"), cell("1/0/0")))
        assertThrows(IllegalArgumentException::class.java) { index.validate() }
    }

    @Test
    fun `la convalida rifiuta un tileZoom fuori dai limiti`() {
        val index = AddressGridIndex("v1", 23, cells = listOf(cell("1/0/0")))
        assertThrows(IllegalArgumentException::class.java) { index.validate() }
    }

    // ---- AddressGridManifestEntry (voce "addressGrid" del manifest) ----

    private val sampleManifestEntry = """{ "version": "2026.10.01.123.1", "url": "https://miracle091.github.io/pocket-travel/address-grid.json", "sizeBytes": 250000, "sha256": "$sha" }"""

    @Test
    fun `AddressGridManifestEntry valido passa la convalida`() {
        json.decodeFromString(AddressGridManifestEntry.serializer(), sampleManifestEntry).validate()
    }

    @Test
    fun `AddressGridManifestEntry rifiuta un host non consentito`() {
        val badHost = json.decodeFromString(
            AddressGridManifestEntry.serializer(),
            sampleManifestEntry.replace("https://miracle091.github.io/pocket-travel/address-grid.json", "https://evil.example.com/address-grid.json"),
        )
        assertThrows(IllegalArgumentException::class.java) { badHost.validate() }
    }
}
