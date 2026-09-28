package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
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
