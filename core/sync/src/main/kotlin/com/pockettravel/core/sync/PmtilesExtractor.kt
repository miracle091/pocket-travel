package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel
import ch.poole.geo.pmtiles.Reader
import java.io.File
import java.net.URL
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

class PmtilesExtractionException(message: String) : Exception(message)

/**
 * Estrae, lato device, solo le tile dentro il bounding box di una regione dalla build
 * whole-planet pubblica di Protomaps (mapSource.sourceUrl) — mai scaricato il file intero
 * (~120 GB): PMTiles legge prima la (piccola) directory delle tile via HTTP range, poi solo
 * le tile richieste (ch.poole.geo.pmtiles-reader, BSD-3). Il contenitore .pmtiles locale
 * risultante e' scritto da PmtilesWriter (nessuna libreria pronta scrive questo formato).
 *
 * Chiamato dentro RegionPackageDownloadWorker, non un meccanismo di download separato: dal
 * punto di vista dell'utente resta lo stesso "Scarica" di sempre.
 */
class PmtilesExtractor @Inject constructor() {

    fun extract(mapSource: MapExtractionSource, outputFile: File) {
        HttpUrlConnectionChannel(URL(mapSource.sourceUrl)).use { channel ->
            Reader(channel).use { reader ->
                val entries = fetchEntries(reader, mapSource)
                if (entries.isEmpty()) {
                    throw PmtilesExtractionException("Nessuna tile trovata per il bounding box richiesto")
                }
                PmtilesWriter.write(
                    outputFile = outputFile,
                    entries = entries,
                    metadataJson = reader.metadata,
                    tileCompression = reader.tileCompression,
                    tileType = reader.tileType,
                    minZoom = mapSource.minZoom,
                    maxZoom = mapSource.maxZoom,
                    minLon = mapSource.minLon,
                    minLat = mapSource.minLat,
                    maxLon = mapSource.maxLon,
                    maxLat = mapSource.maxLat,
                )
            }
        }
    }

    private fun fetchEntries(reader: Reader, mapSource: MapExtractionSource): List<PmtilesEntry> {
        val entries = mutableListOf<PmtilesEntry>()
        for (zoom in mapSource.minZoom..mapSource.maxZoom) {
            val range = tileRangeFor(mapSource, zoom)
            for (x in range.minX..range.maxX) {
                for (y in range.minY..range.maxY) {
                    val data = reader.getTile(zoom, x, y) ?: continue
                    val tileId = zoomOffset(zoom) + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
                    entries += PmtilesEntry(tileId, data)
                }
            }
        }
        return entries
    }

    private data class TileRange(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    // Convenzione slippy-map standard (Google/OSM, la stessa usata da Hilbert.zxyToIndex).
    private fun tileRangeFor(mapSource: MapExtractionSource, zoom: Int): TileRange {
        val tilesPerAxis = 1 shl zoom
        fun lonToX(lon: Double) = (((lon + 180.0) / 360.0) * tilesPerAxis).toInt().coerceIn(0, tilesPerAxis - 1)
        fun latToY(lat: Double): Int {
            val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * tilesPerAxis
            return y.toInt().coerceIn(0, tilesPerAxis - 1)
        }
        return TileRange(
            minX = lonToX(mapSource.minLon),
            maxX = lonToX(mapSource.maxLon),
            minY = latToY(mapSource.maxLat), // latitudine massima -> Y minore
            maxY = latToY(mapSource.minLat),
        )
    }

    /** Somma cumulativa delle tile di tutti gli zoom precedenti a `zoom` (0 = nessuna): stesso
     *  offset che ch.poole.geo.pmtiles.Reader.getZoomOffset applica internamente — non esposto
     *  pubblicamente dalla libreria, quindi replicato qui in forma chiusa: sum(4^k, k=0..z-1). */
    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L
}
