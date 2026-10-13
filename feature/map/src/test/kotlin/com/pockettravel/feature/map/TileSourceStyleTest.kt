package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stile, etichette e lettura dell'header PMTiles non coperti da OfflineTileSourceTest. */
class TileSourceStyleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun ids(style: String) = Regex(""""id": "([^"]+)"""").findAll(style).map { it.groupValues[1] }.toList()

    private fun region(id: String, maxZoom: Int = 14, addresses: String? = null) =
        RegionSource("region-$id", "-$id", "pmtiles://file://$id.pmtiles", maxZoom, addresses)

    @Test
    fun `le etichette in italiano preferiscono name_it, poi name_en, poi name`() {
        val italian = labelField("it")

        assertTrue(italian.indexOf("name:it") < italian.indexOf("name:en"))
        assertTrue(italian.indexOf("name:en") < italian.indexOf("\"name\""))
    }

    @Test
    fun `le etichette in inglese non cercano name_it`() {
        val english = labelField("en")

        assertFalse(english.contains("name:it"))
        assertTrue(english.indexOf("name:en") < english.indexOf("\"name\""))
    }

    @Test
    fun `una lingua senza varianti usa le etichette italiane`() {
        assertEquals(labelField("it"), labelField("fr"))
        assertEquals(labelField("it"), labelField(""))
    }

    @Test
    fun `il tema scuro usa lo sfondo scuro e il chiaro lo sfondo chiaro`() {
        val only = region("a")

        val dark = regionsStyle(listOf(only), dark = true, label = labelField("it"), fallback = null)
        val light = regionsStyle(listOf(only), dark = false, label = labelField("it"), fallback = null)

        assertTrue(dark.contains("\"background-color\": \"#1d2226\""))
        assertFalse(dark.contains("#f2efe9"))
        assertTrue(light.contains("\"background-color\": \"#f2efe9\""))
        assertFalse(light.contains("#1d2226"))
    }

    @Test
    fun `senza ripiego lo sfondo e' il primo strato e le terre della regione non si disegnano`() {
        val style = regionsStyle(listOf(region("a")), dark = false, label = labelField("it"), fallback = null)

        assertEquals("background", ids(style).first())
        assertFalse(ids(style).any { it.startsWith("earth") })
    }

    @Test
    fun `con il ripiego le terre della regione stanno sopra i confini dei paesi`() {
        val fallback = worldFallbackStyle("#1", "#2", "#3", "#4", null, 8)

        val all = ids(regionsStyle(listOf(region("a")), dark = false, label = labelField("it"), fallback = fallback))

        assertFalse(all.contains("background"))
        assertTrue(all.indexOf("fallback_countries") < all.indexOf("earth-a"))
        assertTrue(all.indexOf("earth-a") < all.indexOf("water-a"))
    }

    @Test
    fun `le mappe d'insieme stanno sotto le mappe complete, in qualunque ordine arrivino`() {
        val preview = region("anteprima", maxZoom = 9)
        val full = region("completa")

        val all = ids(regionsStyle(listOf(full, preview), dark = false, label = labelField("it"), fallback = null))

        // Tutta la pila della mappa d'insieme prima di quella della mappa completa.
        assertTrue(all.indexOf("roads_labels_minor-anteprima") < all.indexOf("water-completa"))
    }

    @Test
    fun `lo zoom massimo della sorgente e' quello dichiarato dalla regione`() {
        val style = regionsStyle(listOf(region("a", maxZoom = 13)), dark = false, label = labelField("it"), fallback = null)

        assertTrue(style.contains("\"maxzoom\": 13"))
        assertTrue(style.contains("\"minzoom\": 0"))
    }

    @Test
    fun `i civici sono una sorgente a zoom 14 e uno strato di etichette da zoom 17`() {
        val style = regionsStyle(listOf(region("a", addresses = "pmtiles://file://a-addresses.pmtiles")), dark = false, label = labelField("it"), fallback = null)

        assertTrue(style.contains("\"addresses-a\": { \"type\": \"vector\", \"url\": \"pmtiles://file://a-addresses.pmtiles\""))
        val layer = style.lineSequence().first { "\"source-layer\": \"addresses\"" in it }
        assertTrue(layer.contains("\"minzoom\": 17"))
        assertTrue(layer.contains("[\"get\", \"number\"]"))
    }

    @Test
    fun `senza civici nello stile non c'e' nessuna sorgente di civici`() {
        val style = regionsStyle(listOf(region("a")), dark = false, label = labelField("it"), fallback = null)

        assertFalse(style.contains("addresses"))
    }

    @Test
    fun `le etichette dello stile usano il campo scelto`() {
        val style = regionsStyle(listOf(region("a")), dark = false, label = labelField("en"), fallback = null)

        val labels = style.lineSequence().filter { "\"text-field\"" in it }.toList()
        assertEquals(3, labels.size)
        assertTrue(labels.all { it.contains(labelField("en")) })
    }

    @Test
    fun `lo stile ha parentesi graffe e quadre bilanciate`() {
        val fallback = worldFallbackStyle("#1", "#2", "#3", "#4", "pmtiles://https://example.invalid/w.pmtiles", 8)
        val style = regionsStyle(
            listOf(region("a", addresses = "pmtiles://file://a-addresses.pmtiles"), region("b", maxZoom = 9)),
            dark = true,
            label = labelField("it"),
            fallback = fallback,
        )

        assertEquals(style.count { it == '{' }, style.count { it == '}' })
        assertEquals(style.count { it == '[' }, style.count { it == ']' })
    }

    @Test
    fun `gli id degli strati con il ripiego online sono unici`() {
        val fallback = worldFallbackStyle("#1", "#2", "#3", "#4", "pmtiles://https://example.invalid/w.pmtiles", 8)

        val all = ids(regionsStyle(listOf(region("a"), region("b", maxZoom = 9)), dark = false, label = labelField("it"), fallback = fallback))

        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `i colori del ripiego passati finiscono negli strati`() {
        val fallback = worldFallbackStyle(water = "#aa0001", land = "#aa0002", border = "#aa0003", majorRoad = "#aa0004", worldUrl = null, worldMaxZoom = 8)

        assertTrue(fallback.layers.contains("\"background-color\": \"#aa0001\""))
        assertTrue(fallback.layers.contains("\"fill-color\": \"#aa0002\""))
        assertTrue(fallback.layers.contains("\"fill-outline-color\": \"#aa0003\""))
    }

    @Test
    fun `lo zoom massimo della mappa del mondo finisce nella sorgente`() {
        val fallback = worldFallbackStyle("#1", "#2", "#3", "#4", "pmtiles://https://example.invalid/w.pmtiles", 6)

        assertTrue(fallback.sources.contains("\"url\": \"pmtiles://https://example.invalid/w.pmtiles\""))
        assertTrue(fallback.sources.contains("\"maxzoom\": 6"))
    }

    @Test
    fun `un header troncato non si legge`() {
        assertThrows(IllegalArgumentException::class.java) { pmtilesHeaderMaxZoom(ByteArray(126)) }
        assertThrows(IllegalArgumentException::class.java) { pmtilesHeaderBounds(ByteArray(100)) }
    }

    @Test
    fun `un file piu' corto dell'header ha zoom massimo zero`() {
        val file = tempFolder.newFile("troncato.pmtiles")
        file.writeBytes(ByteArray(10))

        assertEquals(0, pmtilesHeaderMaxZoom(file))
    }

    @Test
    fun `un file vuoto ha zoom massimo zero`() {
        assertEquals(0, pmtilesHeaderMaxZoom(tempFolder.newFile("vuoto.pmtiles")))
    }

    @Test
    fun `il riquadro include i bordi ma non i punti appena fuori`() {
        val bounds = MapBounds(12.0, 43.0, 13.0, 44.0)

        assertTrue(bounds.contains(latitude = 43.0, longitude = 12.0))
        assertTrue(bounds.contains(latitude = 44.0, longitude = 13.0))
        assertFalse(bounds.contains(latitude = 44.0001, longitude = 12.5))
        assertFalse(bounds.contains(latitude = 43.5, longitude = 13.0001))
    }

    @Test
    fun `il riquadro letto dall'header ha gradi con sette decimali`() {
        val header = ByteArray(127)
        java.nio.ByteBuffer.wrap(header, 102, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(-1).putInt(1).putInt(1_800_000_000).putInt(900_000_000)

        val bounds = pmtilesHeaderBounds(header)

        assertEquals(-1e-7, bounds.minLon, 1e-12)
        assertEquals(1e-7, bounds.minLat, 1e-12)
        assertEquals(180.0, bounds.maxLon, 1e-9)
        assertEquals(90.0, bounds.maxLat, 1e-9)
    }

    @Test
    fun `la sorgente scelta tra mappa del mondo e nessuna dipende solo dalla rete e dall'indirizzo`() {
        val online = selectSource(null, previewUrl = null, previewMaxZoom = 0, worldMapUrl = "https://example.invalid/w.pmtiles", worldMapMaxZoom = 7, online = true)
        val offline = selectSource(null, previewUrl = null, previewMaxZoom = 0, worldMapUrl = "https://example.invalid/w.pmtiles", worldMapMaxZoom = 7, online = false)

        assertEquals(ResolvedSource(MapSourceKind.ONLINE_WORLD, "pmtiles://https://example.invalid/w.pmtiles", 7), online)
        assertEquals(ResolvedSource(MapSourceKind.NONE, null, 0), offline)
    }
}
