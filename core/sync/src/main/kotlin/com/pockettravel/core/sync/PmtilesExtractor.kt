package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.HttpUrlConnectionChannel
import ch.poole.geo.pmtiles.Reader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject

class PmtilesExtractionException(message: String) : Exception(message)

/**
 * Esito di [PmtilesExtractor.extract]: quante tile sono state scaricate e quante riusate dalla mappa
 * gia' installata (0 con l'estrazione completa). I byte riusati sono quelli non scaricati.
 */
data class PmtilesExtractionStats(
    val incremental: Boolean,
    val tilesDownloaded: Int,
    val bytesDownloaded: Long,
    val tilesReused: Int,
    val bytesReused: Long,
)

/**
 * Estrae, lato device, solo le tile dentro il bounding box di una regione dalla build
 * whole-planet pubblica di Protomaps (mapSource.sourceUrl) — mai scaricato il file intero
 * (~120 GB): PMTiles legge prima la (piccola) directory delle tile via HTTP range, poi solo
 * le tile richieste (ch.poole.geo.pmtiles-reader, BSD-3). Il contenitore .pmtiles locale
 * risultante e' scritto da PmtilesWriter (nessuna libreria pronta scrive questo formato).
 *
 * Con una mappa gia' installata (previousMap) l'aggiornamento e' incrementale: si confrontano le
 * directory della build nuova e del file locale per le tile del riquadro, e si scaricano solo le
 * tile nuove o con lunghezza diversa; le altre si copiano dal file locale, byte per byte, senza
 * ricompressione. Tra due build una tile con lo stesso contenuto ha la stessa lunghezza; il caso
 * inverso (stessa lunghezza, contenuto diverso) e' raro e accettato: la tile resta quella vecchia
 * fino al prossimo aggiornamento che la cambia di lunghezza. Se qualcosa non torna (file locale
 * illeggibile, compressione o tipo di tile diversi, errori di rete) si ripiega sull'estrazione
 * completa; l'annullamento invece si propaga.
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
    fun extract(
        mapSource: MapExtractionSource,
        outputFile: File,
        previousMap: File? = null,
        ensureActive: () -> Unit = {},
    ): PmtilesExtractionStats {
        val directory = requireNotNull(outputFile.absoluteFile.parentFile)
        HttpUrlConnectionChannel(URL(mapSource.sourceUrl)).use { channel ->
            Reader(channel).use { reader ->
                if (previousMap != null && previousMap.isFile) {
                    var interruption: Throwable? = null
                    try {
                        // Le tile finiscono subito su un file temporaneo accanto all'output (non in RAM,
                        // vedi PmtilesTileSpool), cancellato alla chiusura anche su errore o annullamento.
                        return PmtilesTileSpool(directory).use { tiles ->
                            val stats = fetchTilesIncremental(channel, mapSource, previousMap, tiles) {
                                try {
                                    ensureActive()
                                } catch (error: Throwable) {
                                    interruption = error
                                    throw error
                                }
                            }
                            writeArchive(reader, mapSource, tiles, outputFile)
                            stats
                        }
                    } catch (error: PmtilesExtractionException) {
                        throw error
                    } catch (error: Exception) {
                        if (error === interruption) throw error
                        // Disco pieno: l'estrazione completa riscaricherebbe tutte le tile per fallire uguale.
                        if (error is IOException && (error.message.orEmpty().contains("ENOSPC") || directory.usableSpace < LOW_SPACE_BYTES)) throw error
                        // altrimenti ripiego sull'estrazione completa qui sotto
                    }
                }
                return PmtilesTileSpool(directory).use { tiles ->
                    val bytes = fetchTiles(reader, mapSource, tiles, ensureActive)
                    writeArchive(reader, mapSource, tiles, outputFile)
                    PmtilesExtractionStats(false, tiles.tileCount, bytes, 0, 0L)
                }
            }
        }
    }

    private fun writeArchive(reader: Reader, mapSource: MapExtractionSource, tiles: PmtilesTileSpool, outputFile: File) {
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

    /** Restituisce i byte scaricati. */
    private fun fetchTiles(reader: Reader, mapSource: MapExtractionSource, tiles: PmtilesTileSpool, ensureActive: () -> Unit): Long {
        var bytes = 0L
        for (zoom in mapSource.minZoom..mapSource.maxZoom) {
            val range = tileRangeFor(mapSource.minLon, mapSource.minLat, mapSource.maxLon, mapSource.maxLat, zoom)
            for (x in range.minX..range.maxX) {
                ensureActive()
                for (y in range.minY..range.maxY) {
                    val data = reader.getTile(zoom, x, y) ?: continue
                    val tileId = zoomOffset(zoom) + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
                    tiles.add(tileId, data)
                    bytes += data.size
                }
            }
        }
        return bytes
    }

    /** Come [fetchTiles], ma scarica solo le tile assenti o di lunghezza diversa in [previousMap]. */
    private fun fetchTilesIncremental(
        channel: FileChannel,
        mapSource: MapExtractionSource,
        previousMap: File,
        tiles: PmtilesTileSpool,
        ensureActive: () -> Unit,
    ): PmtilesExtractionStats {
        RandomAccessFile(previousMap, "r").use { local ->
            val remoteIndex = PmtilesTileIndex { position, length -> readRemote(channel, position, length) }
            val localIndex = PmtilesTileIndex { position, length -> readLocal(local, position, length) }
            if (remoteIndex.tileCompression != localIndex.tileCompression || remoteIndex.tileType != localIndex.tileType) {
                throw IOException("Compressione o tipo di tile diversi tra la mappa installata e la build")
            }
            val tileIds = wantedTileIds(mapSource)
            val remote = remoteIndex.locate(tileIds, ensureActive)
            val installed = localIndex.locate(tileIds, ensureActive)

            var downloaded = 0
            var bytesDownloaded = 0L
            var reused = 0
            var bytesReused = 0L
            // Le tile di una stessa voce con runLength > 1 (mare, terra vuota) hanno lo stesso offset:
            // scaricate una volta sola, come fa Reader con la sua tile in cache.
            var lastOffset = -1L
            var lastData = ByteArray(0)
            for (i in tileIds.indices) {
                if (i % ENSURE_ACTIVE_EVERY == 0) ensureActive()
                val length = remote.lengths[i]
                if (length < 0) continue
                val data = if (installed.lengths[i] == length) {
                    reused++
                    bytesReused += length
                    readLocal(local, installed.offsets[i], length)
                } else if (remote.offsets[i] == lastOffset && lastData.size == length) {
                    lastData
                } else {
                    downloaded++
                    bytesDownloaded += length
                    readRemote(channel, remote.offsets[i], length).also {
                        lastOffset = remote.offsets[i]
                        lastData = it
                    }
                }
                tiles.add(tileIds[i], data)
            }
            return PmtilesExtractionStats(true, downloaded, bytesDownloaded, reused, bytesReused)
        }
    }

    private fun readRemote(channel: FileChannel, position: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        val count = channel.read(buffer, position)
        if (count != length) throw IOException("Lettura incompleta: $count byte di $length")
        return buffer.array()
    }

    private fun readLocal(file: RandomAccessFile, position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        file.seek(position)
        file.readFully(bytes)
        return bytes
    }

    /** TileId (zoomOffset incluso) di tutte le tile del riquadro, ordinati: le stesse dell'estrazione completa. */
    private fun wantedTileIds(mapSource: MapExtractionSource): LongArray {
        val zooms = mapSource.minZoom..mapSource.maxZoom
        val ranges = zooms.map { tileRangeFor(mapSource.minLon, mapSource.minLat, mapSource.maxLon, mapSource.maxLat, it) }
        // Array primitivo (a z14 sono centinaia di migliaia di tile): niente liste di Long boxed.
        val ids = LongArray(ranges.sumOf { (it.maxX - it.minX + 1).toLong() * (it.maxY - it.minY + 1) }.toInt())
        var count = 0
        for ((zoom, range) in zooms.zip(ranges)) {
            for (x in range.minX..range.maxX) {
                for (y in range.minY..range.maxY) ids[count++] = zoomOffset(zoom) + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
            }
        }
        return ids.also { it.sort() }
    }

    /** Somma cumulativa delle tile di tutti gli zoom precedenti a `zoom` (0 = nessuna): stesso
     *  offset che ch.poole.geo.pmtiles.Reader.getZoomOffset applica internamente — non esposto
     *  pubblicamente dalla libreria, quindi replicato qui in forma chiusa: sum(4^k, k=0..z-1). */
    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L

    private companion object {
        const val ENSURE_ACTIVE_EVERY = 256
        // Sotto questa soglia un errore di scrittura e' quasi certamente il disco pieno.
        const val LOW_SPACE_BYTES = 16L * 1024 * 1024
    }
}
