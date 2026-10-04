package com.pockettravel.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenerateAddressGridTest {

    private fun cellEntry(id: String, version: String = "2026.09.30.1") = """
        { "id": "$id", "version": "$version",
          "file": { "name": "cell.pmtiles", "url": "https://example.org/$id/cell.pmtiles", "sizeBytes": 100, "sha256": "${"a".repeat(64)}" },
          "fileXz": { "name": "cell.pmtiles.xz", "url": "https://example.org/$id/cell.pmtiles.xz", "sizeBytes": 40, "sha256": "${"b".repeat(64)}" } }
    """.trimIndent()

    private val osmAttribution = GridAttribution("OpenStreetMap", "ODbL-1.0", "https://www.openstreetmap.org/copyright")

    private fun cellsOf(indexJson: String): List<String> {
        val cells = JSONObject(indexJson).getJSONArray("cells")
        return (0 until cells.length()).map { cells.getJSONObject(it).getString("id") }
    }

    @Test
    fun `senza indice pubblicato scrive solo le celle nuove, ordinate per id`() {
        // Sottoalberi diversi (nessuna id qui e' antenata di un'altra): l'ordine e' solo (z, x, y).
        val index = mergeAddressGridJson(
            "1", null,
            listOf(cellEntry("12/50/51"), cellEntry("11/1/1"), cellEntry("12/50/50")),
            listOf(osmAttribution),
        )

        assertEquals(listOf("11/1/1", "12/50/50", "12/50/51"), cellsOf(index))
        assertEquals(14, JSONObject(index).getInt("tileZoom"))
        assertEquals(1, JSONObject(index).getJSONArray("attributions").length())
    }

    @Test
    fun `scrive publishedAt e non eredita quello dell'indice pubblicato`() {
        val published = """{ "version": "0", "publishedAt": 5, "tileZoom": 14, "cells": [], "attributions": [] }"""

        assertEquals(1_700_000_000L, JSONObject(mergeAddressGridJson("2", published, listOf(cellEntry("11/1/1")), listOf(osmAttribution), 1_700_000_000L)).getLong("publishedAt"))
        // Senza valore esplicito: l'ora corrente in secondi, non zero.
        assertTrue(JSONObject(mergeAddressGridJson("2", null, listOf(cellEntry("11/1/1")), listOf(osmAttribution))).getLong("publishedAt") > 1_700_000_000L)
    }

    @Test
    fun `tiene le celle pubblicate non toccate in questa run`() {
        val published = """{ "version": "0", "tileZoom": 14, "cells": [${cellEntry("11/1/1")}], "attributions": [] }"""

        val index = mergeAddressGridJson("2", published, listOf(cellEntry("12/5/5")), listOf(osmAttribution))

        assertEquals(listOf("11/1/1", "12/5/5"), cellsOf(index))
    }

    @Test
    fun `una cella nuova sostituisce quella pubblicata con lo stesso id`() {
        val published = """{ "version": "0", "tileZoom": 14, "cells": [${cellEntry("11/1/1", "vecchia")}], "attributions": [] }"""

        val index = mergeAddressGridJson("2", published, listOf(cellEntry("11/1/1", "nuova")), listOf(osmAttribution))

        val cells = JSONObject(index).getJSONArray("cells")
        assertEquals(1, cells.length())
        assertEquals("nuova", cells.getJSONObject(0).getString("version"))
    }

    private fun cellEntryWithSearch(id: String, version: String) = cellEntry(id, version).replace(
        "\"fileXz\":",
        "\"search\": { \"file\": { \"name\": \"s.db\", \"url\": \"https://example.org/$id/s.db\", \"sizeBytes\": 90, \"sha256\": \"${"c".repeat(64)}\" }, " +
            "\"fileXz\": { \"name\": \"s.db.xz\", \"url\": \"https://example.org/$id/s.db.xz\", \"sizeBytes\": 30, \"sha256\": \"${"d".repeat(64)}\" } }, \"fileXz\":",
    )

    @Test
    fun `il campo search della cella sopravvive al merge e alla riscrittura, e le celle senza search restano senza`() {
        val published = """{ "version": "0", "tileZoom": 14, "cells": [${cellEntryWithSearch("11/1/1", "v1")}, ${cellEntry("11/2/2", "v1")}], "attributions": [] }"""

        // 11/1/1 non toccata: tiene search. 12/9/9 nuova con search, 11/2/2 sostituita da una voce senza search.
        val index = mergeAddressGridJson("2", published, listOf(cellEntryWithSearch("12/9/9", "v2"), cellEntry("11/2/2", "v2")), listOf(osmAttribution))

        val cells = JSONObject(index).getJSONArray("cells")
        val byId = (0 until cells.length()).map { cells.getJSONObject(it) }.associateBy { it.getString("id") }
        assertEquals("s.db.xz", byId.getValue("11/1/1").getJSONObject("search").getJSONObject("fileXz").getString("name"))
        assertEquals("c".repeat(64), byId.getValue("11/1/1").getJSONObject("search").getJSONObject("file").getString("sha256"))
        assertEquals("s.db", byId.getValue("12/9/9").getJSONObject("search").getJSONObject("file").getString("name"))
        assertTrue(!byId.getValue("11/2/2").has("search"))
        // Lo stesso JSON riletto dal validatore: indice con e senza search insieme.
        validateAddressGridJson(index.replace("example.org", "github.com").replace("\"attributions\": []", "\"attributions\": [{\"source\":\"a\",\"license\":\"b\",\"url\":\"c\"}]"), setOf("github.com"))
    }

    @Test
    fun `una cella divisa in figli toglie la voce del genitore pubblicato`() {
        val published = """{ "version": "0", "tileZoom": 14, "cells": [${cellEntry("11/1/1")}], "attributions": [] }"""
        val children = listOf("12/2/2", "12/2/3", "12/3/2", "12/3/3").map { cellEntry(it) }

        val index = mergeAddressGridJson("2", published, children, listOf(osmAttribution))

        assertEquals(listOf("12/2/2", "12/2/3", "12/3/2", "12/3/3"), cellsOf(index))
    }

    @Test
    fun `isAncestorCell riconosce solo gli antenati propri`() {
        assertTrue(isAncestorCell("11/1/1", "12/2/2"))
        assertTrue(isAncestorCell("11/1/1", "12/3/3"))
        assertTrue(!isAncestorCell("11/1/1", "11/1/1"))
        assertTrue(!isAncestorCell("12/2/2", "11/1/1"))
        assertTrue(!isAncestorCell("11/1/1", "12/4/4"))
    }

    @Test
    fun `buildAddressGridFragmentJson scrive la voce addressGrid con dimensione e hash del file`() {
        val indexFile = File.createTempFile("pocket-travel-test", "address-grid.json")
        try {
            indexFile.writeText("""{ "cells": [] }""")

            val fragment = JSONObject(buildAddressGridFragmentJson("2026.09.30.1", indexFile, "https://example.org/address-grid.json"))

            val addressGrid = fragment.getJSONObject("addressGrid")
            assertEquals("2026.09.30.1", addressGrid.getString("version"))
            assertEquals("https://example.org/address-grid.json", addressGrid.getString("url"))
            assertEquals(indexFile.length(), addressGrid.getLong("sizeBytes"))
            assertEquals(sha256Of(indexFile), addressGrid.getString("sha256"))
        } finally {
            indexFile.delete()
        }
    }
}
