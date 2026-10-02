package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTileSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `sceglie la mappa completa se c'e', anche con anteprima, mondo online e rete disponibili`() {
        val resolved = selectSource(
            fullMapUrl = "pmtiles://file://map.pmtiles",
            previewUrl = "pmtiles://file://preview.pmtiles",
            previewMaxZoom = 9,
            worldMapUrl = "https://example.invalid/world.pmtiles",
            worldMapMaxZoom = 8,
            online = true,
        )
        assertEquals(MapSourceKind.FULL, resolved.kind)
        assertEquals("pmtiles://file://map.pmtiles", resolved.url)
        assertEquals(14, resolved.maxZoom)
    }

    @Test
    fun `la mappa completa leggera dichiara lo zoom del suo header, MapLibre ingrandisce la z13`() {
        val resolved = selectSource(
            fullMapUrl = "pmtiles://file://map.pmtiles",
            fullMapMaxZoom = 13,
            previewUrl = null,
            previewMaxZoom = 0,
            worldMapUrl = null,
            worldMapMaxZoom = 8,
            online = false,
        )
        assertEquals(MapSourceKind.FULL, resolved.kind)
        assertEquals(13, resolved.maxZoom)
    }

    @Test
    fun `senza mappa completa sceglie l'anteprima, con il suo maxzoom`() {
        val resolved = selectSource(
            fullMapUrl = null,
            previewUrl = "pmtiles://file://preview.pmtiles",
            previewMaxZoom = 9,
            worldMapUrl = "https://example.invalid/world.pmtiles",
            worldMapMaxZoom = 8,
            online = true,
        )
        assertEquals(MapSourceKind.PREVIEW, resolved.kind)
        assertEquals(9, resolved.maxZoom)
    }

    @Test
    fun `senza mappa ne' anteprima ma online sceglie il mondo, con lo schema pmtiles`() {
        val resolved = selectSource(
            fullMapUrl = null,
            previewUrl = null,
            previewMaxZoom = 0,
            worldMapUrl = "https://example.invalid/world.pmtiles",
            worldMapMaxZoom = 8,
            online = true,
        )
        assertEquals(MapSourceKind.ONLINE_WORLD, resolved.kind)
        assertEquals("pmtiles://https://example.invalid/world.pmtiles", resolved.url)
        assertEquals(8, resolved.maxZoom)
    }

    @Test
    fun `senza mappa, anteprima e offline (o senza mondo pubblicato) non resta nessuna sorgente`() {
        val offline = selectSource(
            fullMapUrl = null, previewUrl = null, previewMaxZoom = 0,
            worldMapUrl = "https://example.invalid/world.pmtiles", worldMapMaxZoom = 8, online = false,
        )
        assertEquals(MapSourceKind.NONE, offline.kind)

        val noWorldMap = selectSource(
            fullMapUrl = null, previewUrl = null, previewMaxZoom = 0,
            worldMapUrl = null, worldMapMaxZoom = 0, online = true,
        )
        assertEquals(MapSourceKind.NONE, noWorldMap.kind)
    }

    @Test
    fun `mondo online sotto la regione solo con mappa della regione e rete`() {
        val world = "https://example.invalid/world.pmtiles"
        assertEquals("pmtiles://$world", worldFallbackUrl(MapSourceKind.FULL, world, online = true))
        assertEquals("pmtiles://$world", worldFallbackUrl(MapSourceKind.PREVIEW, world, online = true))
        assertEquals(null, worldFallbackUrl(MapSourceKind.FULL, world, online = false))
        assertEquals(null, worldFallbackUrl(MapSourceKind.FULL, null, online = true))
        // Con ONLINE_WORLD il mondo e' gia' la sorgente principale: niente doppione.
        assertEquals(null, worldFallbackUrl(MapSourceKind.ONLINE_WORLD, world, online = true))
        assertEquals(null, worldFallbackUrl(MapSourceKind.NONE, world, online = true))
    }

    @Test
    fun `il ripiego offline ha solo i confini, con la rete anche il mondo sopra`() {
        val offline = worldFallbackStyle("#00f", "#eee", "#888", "#fc0", worldUrl = null, worldMaxZoom = 8)
        assertTrue(offline.sources.contains("asset://world/countries.geojson"))
        assertFalse(offline.sources.contains("\"world\""))
        assertFalse(offline.layers.contains("fallback_world"))

        val online = worldFallbackStyle("#00f", "#eee", "#888", "#fc0", worldUrl = "pmtiles://https://example.invalid/w.pmtiles", worldMaxZoom = 8)
        assertTrue(online.sources.contains("\"maxzoom\": 8"))
        val countries = online.layers.indexOf("fallback_countries")
        val worldEarth = online.layers.indexOf("fallback_world_earth")
        assertTrue(countries in 0 until worldEarth)
    }

    @Test
    fun `lo stile con piu' regioni ripete gli strati per ognuna, con id unici e uno strato alla volta`() {
        val a = RegionSource("region-a", "-a", "pmtiles://file://a.pmtiles", 14, null)
        val b = RegionSource("region-b", "-b", "pmtiles://file://b.pmtiles", 14, "pmtiles://file://b-addresses.pmtiles")
        val fallback = worldFallbackStyle("#1", "#2", "#3", "#4", null, 8)

        val style = regionsStyle(listOf(a, b), dark = false, label = labelField("it"), fallback = fallback)

        val ids = Regex(""""id": "([^"]+)"""").findAll(style).map { it.groupValues[1] }.toList()
        assertEquals("id degli strati unici", ids.size, ids.toSet().size)
        assertTrue(style.contains("\"region-a\": {") && style.contains("\"region-b\": {") && style.contains("\"addresses-b\": {"))
        assertFalse(style.contains("addresses-a"))
        // Prima il ripiego, poi per ogni strato prima A e poi B (le strade di A non finiscono sotto la terra di B).
        assertTrue(ids.indexOf("fallback_countries") < ids.indexOf("earth-a"))
        assertTrue(ids.indexOf("earth-b") < ids.indexOf("water-a"))
        assertTrue(ids.indexOf("water-a") + 1 == ids.indexOf("water-b"))
        assertTrue(ids.indexOf("roads_labels_minor-b") < ids.indexOf("addresses-b"))
        assertEquals("addresses-b", ids.last())
    }

    @Test
    fun `con una sola regione lo stile e' quello di sempre, con sorgente region e id senza suffisso`() {
        val only = RegionSource("region", "", "pmtiles://file://map.pmtiles", 14, null)

        val style = regionsStyle(listOf(only), dark = false, label = labelField("it"), fallback = null)

        assertTrue(style.contains("\"region\": {"))
        assertTrue(style.contains("{ \"id\": \"water\", \"type\": \"fill\", \"source\": \"region\""))
        assertTrue(style.contains("\"id\": \"roads_labels_minor\""))
    }

    @Test
    fun `legge il riquadro dai byte 102-117 dell'header PMTiles, anche con longitudini negative`() {
        val header = ByteArray(127)
        java.nio.ByteBuffer.wrap(header, 102, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(124_000_000).putInt(438_900_000).putInt(125_200_000).putInt(439_900_000)
        val bounds = pmtilesHeaderBounds(header)
        assertEquals(MapBounds(12.4, 43.89, 12.52, 43.99), bounds)
        assertTrue(bounds.contains(latitude = 43.94, longitude = 12.45))
        assertFalse(bounds.contains(latitude = 44.059, longitude = 12.568)) // Rimini

        java.nio.ByteBuffer.wrap(header, 102, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(-1_252_000_000).putInt(424_000_000).putInt(-1_142_000_000).putInt(490_000_000)
        assertEquals(-125.2, pmtilesHeaderBounds(header).minLon, 1e-9)
    }

    @Test
    fun `zoom della navigazione piu' vicino alla svolta, al massimo 11 fuori regione`() {
        assertEquals(17.0, followZoom(50.0, insideRegion = true), 0.0)
        assertEquals(16.0, followZoom(230.0, insideRegion = true), 0.0)
        assertEquals(15.0, followZoom(600.0, insideRegion = true), 0.0)
        assertEquals(14.0, followZoom(2_000.0, insideRegion = true), 0.0)
        assertEquals(13.0, followZoom(5_000.0, insideRegion = true), 0.0)
        assertEquals(12.0, followZoom(40_000.0, insideRegion = true), 0.0)
        assertEquals(11.0, followZoom(50.0, insideRegion = false), 0.0)
        assertEquals(11.0, followZoom(40_000.0, insideRegion = false), 0.0)
    }

    @Test
    fun `legge il maxzoom dal byte 101 dell'header PMTiles`() {
        val header = ByteArray(127)
        header[101] = 9
        assertEquals(9, pmtilesHeaderMaxZoom(header))
    }

    @Test
    fun `legge il maxzoom da un file vero, byte oltre 127 ignorati`() {
        val file = tempFolder.newFile("preview.pmtiles")
        val header = ByteArray(127)
        header[101] = 6
        file.writeBytes(header + "resto del file pmtiles, ignorato".toByteArray())
        assertEquals(6, pmtilesHeaderMaxZoom(file))
    }

    @Test
    fun `il byte del maxzoom si legge senza segno`() {
        // 200 non ha senso come zoom vero, ma verifica che un byte "alto" (bit piu' significativo a
        // 1, negativo se letto con segno) non torni un maxzoom negativo.
        val header = ByteArray(127)
        header[101] = 200.toByte()
        assertEquals(200, pmtilesHeaderMaxZoom(header))
    }
}
