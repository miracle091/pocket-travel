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

    // --- mergeAddressGridJson: ordine, antenati, errori ---

    private fun merge(published: String?, vararg entries: String, attributions: List<GridAttribution> = listOf(osmAttribution)) =
        mergeAddressGridJson("2", published, entries.toList(), attributions)

    private fun publishedWith(vararg cells: String) = """{ "version": "0", "tileZoom": 14, "cells": [${cells.joinToString(",")}], "attributions": [] }"""

    @Test
    fun `l'ordine e' numerico per zoom, x e y e non alfabetico`() {
        val index = merge(null, cellEntry("12/10/2"), cellEntry("9/3/3"), cellEntry("12/9/2"), cellEntry("10/0/0"), cellEntry("12/9/10"), cellEntry("12/9/9"))

        assertEquals(listOf("9/3/3", "10/0/0", "12/9/2", "12/9/9", "12/9/10", "12/10/2"), cellsOf(index))
    }

    @Test
    fun `l'indice scritto passa il validatore del manifest`() {
        val index = merge(publishedWith(cellEntry("11/1/1")), cellEntry("12/5/5"), cellEntry("12/5/6"))

        validateAddressGridJson(index, setOf("example.org"))
    }

    @Test
    fun `senza attribuzioni scrive un elenco vuoto, con piu' attribuzioni le conserva in ordine e con unicode`() {
        assertEquals(0, JSONObject(merge(null, cellEntry("11/1/1"), attributions = emptyList())).getJSONArray("attributions").length())

        val attributions = listOf(osmAttribution, GridAttribution("Overture Å 東京", "CDLA-Permissive-2.0", "https://example.org/licenza"))
        val written = JSONObject(merge(null, cellEntry("11/1/1"), attributions = attributions)).getJSONArray("attributions")

        assertEquals(listOf("OpenStreetMap", "Overture Å 東京"), (0 until written.length()).map { written.getJSONObject(it).getString("source") })
        assertEquals("CDLA-Permissive-2.0", written.getJSONObject(1).getString("license"))
    }

    @Test
    fun `versione, publishedAt e zoom delle tile sono scritti al primo livello`() {
        val root = JSONObject(mergeAddressGridJson("2026.10.12.1", null, listOf(cellEntry("11/1/1")), listOf(osmAttribution), 1_760_000_000L))

        assertEquals("2026.10.12.1", root.getString("version"))
        assertEquals(1_760_000_000L, root.getLong("publishedAt"))
        assertEquals(14, root.getInt("tileZoom"))
    }

    @Test
    fun `le celle senza nuove voci restano com'erano e rilanciare il merge sul risultato non cambia le celle`() {
        val first = merge(publishedWith(cellEntry("11/1/1"), cellEntry("11/2/2")), cellEntry("12/9/9"))
        val second = merge(first)

        assertEquals(cellsOf(first), cellsOf(second))
        assertTrue(JSONObject(first).getJSONArray("cells").similar(JSONObject(second).getJSONArray("cells")))
    }

    @Test
    fun `lo stesso id ripetuto tra le voci nuove tiene l'ultima e i campi sconosciuti della cella si conservano`() {
        val withExtra = cellEntry("11/1/1", "uno").replace("{ \"id\"", "{ \"extra\": [1, 2], \"id\"")
        val index = merge(null, withExtra, cellEntry("11/1/1", "due"), cellEntry("11/2/2", "tre").replace("{ \"id\"", "{ \"extra\": [3], \"id\""))

        val cells = JSONObject(index).getJSONArray("cells")
        assertEquals(2, cells.length())
        assertEquals("due", cells.getJSONObject(0).getString("version"))
        assertEquals(false, cells.getJSONObject(0).has("extra"))
        assertEquals(3, cells.getJSONObject(1).getJSONArray("extra").getInt(0))
    }

    @Test
    fun `un antenato a piu' livelli di distanza toglie tutti i suoi discendenti ma non l'inverso`() {
        // Genitore pubblicato 11/1/1, nipote nuovo 13/4/4: il genitore sparisce.
        val grandchild = merge(publishedWith(cellEntry("11/1/1")), cellEntry("13/4/4"))
        assertEquals(listOf("13/4/4"), cellsOf(grandchild))

        // Catena di antenati pubblicati: restano solo le foglie.
        val chain = merge(publishedWith(cellEntry("10/0/0"), cellEntry("11/0/0")), cellEntry("12/0/0"))
        assertEquals(listOf("12/0/0"), cellsOf(chain))

        // Le celle non si fondono: una voce nuova piu' grossa di una figlia pubblicata sparisce, la figlia resta.
        val coarser = merge(publishedWith(cellEntry("12/2/2")), cellEntry("11/1/1"))
        assertEquals(listOf("12/2/2"), cellsOf(coarser))
    }

    @Test
    fun `celle sorelle e celle di sottoalberi diversi coesistono`() {
        val index = merge(null, cellEntry("12/2/2"), cellEntry("12/2/3"), cellEntry("12/3/2"), cellEntry("11/5/5"), cellEntry("1/1/1"))

        assertEquals(listOf("1/1/1", "11/5/5", "12/2/2", "12/2/3", "12/3/2"), cellsOf(index))
    }

    @Test
    fun `un indice pubblicato senza celle o senza il campo cells si tratta come vuoto`() {
        assertEquals(listOf("11/1/1"), cellsOf(merge("""{ "version": "0" }""", cellEntry("11/1/1"))))
        assertEquals(listOf("11/1/1"), cellsOf(merge(publishedWith(), cellEntry("11/1/1"))))
        assertEquals(emptyList<String>(), cellsOf(merge(null)))
    }

    @Test
    fun `un indice pubblicato o una voce malformati o con id non validi fanno fallire il merge`() {
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { merge("non json", cellEntry("11/1/1")) }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { merge(null, "non json") }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { merge(publishedWith("""{ "version": "x" }"""), cellEntry("11/1/1")) }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { merge(null, """{ "version": "x" }""") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { merge(null, cellEntry("11/1"), cellEntry("11/2/2")) }
        org.junit.Assert.assertThrows(NumberFormatException::class.java) { merge(null, cellEntry("a/b/c"), cellEntry("11/2/2")) }
    }

    // --- parseCellIdParts / isAncestorCell: casi limite ---

    @Test
    fun `parseCellIdParts legge z x y e rifiuta i formati diversi`() {
        assertEquals(Triple(12, 2189, 1490), parseCellIdParts("12/2189/1490"))
        assertEquals(Triple(0, 0, 0), parseCellIdParts("0/0/0"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parseCellIdParts("12/2189") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parseCellIdParts("1/2/3/4") }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { parseCellIdParts("") }
        org.junit.Assert.assertThrows(NumberFormatException::class.java) { parseCellIdParts("1/x/3") }
    }

    @Test
    fun `isAncestorCell - la radice contiene tutto, la profondita' e le sorelle contano`() {
        assertTrue(isAncestorCell("0/0/0", "14/16383/16383"))
        assertTrue(isAncestorCell("10/1/1", "14/16/16"))
        assertTrue(isAncestorCell("10/1/1", "14/31/31"))
        assertTrue(!isAncestorCell("10/1/1", "14/32/16"))
        assertTrue(!isAncestorCell("12/2/2", "12/2/3"))
        assertTrue(!isAncestorCell("13/5/5", "12/2/2"))
        assertTrue(!isAncestorCell("12/2/2", "12/2/2"))
    }

    // --- buildAddressGridFragmentJson e MergeManifests ---

    @Test
    fun `il frammento dell'indice ha manifestVersion 2, nessuna regione e passa il merge dei manifest`() {
        val indexFile = File.createTempFile("pocket-travel-test", "address-grid.json")
        try {
            indexFile.writeText(merge(null, cellEntry("11/1/1")))
            val fragment = buildAddressGridFragmentJson("2026.10.12.1", indexFile, "https://example.org/address-grid.json")
            val root = JSONObject(fragment)

            assertEquals(MANIFEST_VERSION, root.getInt("manifestVersion"))
            assertEquals(0, root.getJSONArray("regions").length())
            assertEquals(false, root.getJSONObject("addressGrid").has("fileXz"))

            val merged = JSONObject(mergeManifestJson(listOf(fragment)))
            assertEquals("2026.10.12.1", merged.getJSONObject("addressGrid").getString("version"))
            assertEquals(sha256Of(indexFile), merged.getJSONObject("addressGrid").getString("sha256"))
        } finally {
            indexFile.delete()
        }
    }

    @Test
    fun `cambiare un solo byte dell'indice cambia hash e dimensione nel frammento`() {
        val indexFile = File.createTempFile("pocket-travel-test", "address-grid.json")
        try {
            indexFile.writeText("""{ "cells": [] }""")
            val before = JSONObject(buildAddressGridFragmentJson("1", indexFile, "https://example.org/a.json")).getJSONObject("addressGrid")
            indexFile.writeText("""{ "cells": [ ] }""")
            val after = JSONObject(buildAddressGridFragmentJson("1", indexFile, "https://example.org/a.json")).getJSONObject("addressGrid")

            assertTrue(before.getString("sha256") != after.getString("sha256"))
            assertEquals(before.getLong("sizeBytes") + 1, after.getLong("sizeBytes"))
        } finally {
            indexFile.delete()
        }
    }
}
