package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel
import ch.poole.geo.pmtiles.Reader
import java.io.File
import java.net.URL
import javax.inject.Inject

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

    /**
     * Bloccante (richieste HTTP range): va chiamato fuori dal main thread. [ensureActive] e'
     * invocato prima di ogni colonna di tile e deve lanciare un'eccezione per interrompere
     * l'estrazione (es. CoroutineScope.ensureActive del worker annullato).
     */
    fun extract(mapSource: MapExtractionSource, outputFile: File, ensureActive: () -> Unit = {}) {
        HttpUrlConnectionChannel(URL(mapSource.sourceUrl)).use { channel ->
            Reader(channel).use { reader ->
                // Le tile finiscono subito su un file temporaneo accanto all'output (non in RAM,
                // vedi PmtilesTileSpool), cancellato alla chiusura anche su errore o annullamento.
                PmtilesTileSpool(requireNotNull(outputFile.absoluteFile.parentFile)).use { tiles ->
                    fetchTiles(reader, mapSource, tiles, ensureActive)
                    if (tiles.tileCount == 0) {
                        throw PmtilesExtractionException("Nessuna tile trovata per il bounding box richiesto")
                    }
                    PmtilesWriter.write(
                        outputFile = outputFile,
                        tiles = tiles,
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
    }

    private fun fetchTiles(reader: Reader, mapSource: MapExtractionSource, tiles: PmtilesTileSpool, ensureActive: () -> Unit) {
        for (zoom in mapSource.minZoom..mapSource.maxZoom) {
            val range = tileRangeFor(mapSource.minLon, mapSource.minLat, mapSource.maxLon, mapSource.maxLat, zoom)
            for (x in range.minX..range.maxX) {
                ensureActive()
                for (y in range.minY..range.maxY) {
                    val data = reader.getTile(zoom, x, y) ?: continue
                    val tileId = zoomOffset(zoom) + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
                    tiles.add(tileId, data)
                }
            }
        }
    }

    /** Somma cumulativa delle tile di tutti gli zoom precedenti a `zoom` (0 = nessuna): stesso
     *  offset che ch.poole.geo.pmtiles.Reader.getZoomOffset applica internamente — non esposto
     *  pubblicamente dalla libreria, quindi replicato qui in forma chiusa: sum(4^k, k=0..z-1). */
    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L
}
