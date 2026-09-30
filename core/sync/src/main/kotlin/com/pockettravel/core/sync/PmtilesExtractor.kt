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
 *
 * Solo diagnostica: RegionPackageInstaller ignora il valore restituito e i campi li legge soltanto
 * PmtilesExtractorTest, per verificare che
 * l'aggiornamento incrementale riusi davvero le tile invariate e ricada sull'estrazione completa
 * quando serve.
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
 * inverso (stessa lunghezza, contenuto diverso) e' accettato: la tile resta quella vecchia fino al
 * prossimo aggiornamento che la cambia di lunghezza. Non e' raro alle zoom basse, che coprono aree
 * grandi (su San Marino, tra le build del 24 e del 30 settembre 2026, 3 tile su 75 a z0, z7 e z10):
 * fino a [alwaysDownloadMaxZoom] le tile si riscaricano sempre, circa 1,7 MB per l'Italia fino a z6
 * (115 MB fino a z10). Se qualcosa non torna (file locale
 * illeggibile, compressione o tipo di tile diversi, errori di rete) si ripiega sull'estrazione
 * completa; l'annullamento invece si propaga.
 *
 * Chiamato dentro RegionPackageDownloadWorker, non un meccanismo di download separato: dal
 * punto di vista dell'utente resta lo stesso "Scarica" di sempre.
 */
class PmtilesExtractor internal constructor(private val alwaysDownloadMaxZoom: Int) {

    @Inject constructor() : this(ALWAYS_DOWNLOAD_MAX_ZOOM)

    /**
     * Bloccante (richieste HTTP range): va chiamato fuori dal main thread. [ensureActive] e'
     * invocato prima di ogni richiesta e deve lanciare un'eccezione per interrompere
     * l'estrazione (es. CoroutineScope.ensureActive del worker annullato). [onProgress] riceve i
     * byte di tile scaricati sul totale da scaricare; riparte da 0 se si ripiega sull'estrazione completa.
     */
    fun extract(
        mapSource: MapExtractionSource,
        outputFile: File,
        previousMap: File? = null,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
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
                            val stats = fetchTiles(channel, mapSource, previousMap, tiles, onProgress) {
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
                    val stats = fetchTiles(channel, mapSource, null, tiles, onProgress, ensureActive)
                    writeArchive(reader, mapSource, tiles, outputFile)
                    stats
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

    /**
     * Tile del riquadro dalla build remota. Con [previousMap] (aggiornamento incrementale) si scaricano
     * solo le tile assenti o di lunghezza diversa nella mappa installata, piu' tutte quelle fino a
     * [alwaysDownloadMaxZoom]; senza, tutte.
     *
     * Una richiesta HTTP per tile era lenta (circa 3 MB al minuto sulla Lettonia, limitati dalla
     * latenza): la build Protomaps e' in ordine di tileId (clustered), quindi le tile vicine del
     * riquadro stanno quasi sempre una dopo l'altra nel file e si leggono a blocchi, una richiesta
     * per blocco fino a [MAX_BATCH_BYTES], scaricando anche i buchi tra una tile e l'altra fino a
     * [MAX_GAP_BYTES]. [onProgress] riceve i byte delle tile da scaricare (buchi esclusi).
     */
    private fun fetchTiles(
        channel: FileChannel,
        mapSource: MapExtractionSource,
        previousMap: File?,
        tiles: PmtilesTileSpool,
        onProgress: (Long, Long) -> Unit,
        ensureActive: () -> Unit,
    ): PmtilesExtractionStats {
        val local = previousMap?.let { RandomAccessFile(it, "r") }
        try {
            val remoteIndex = PmtilesTileIndex { position, length -> readRemote(channel, position, length) }
            val localIndex = local?.let { file -> PmtilesTileIndex { position, length -> readLocal(file, position, length) } }
            if (localIndex != null && (remoteIndex.tileCompression != localIndex.tileCompression || remoteIndex.tileType != localIndex.tileType)) {
                throw IOException("Compressione o tipo di tile diversi tra la mappa installata e la build")
            }
            val tileIds = wantedTileIds(mapSource)
            val remote = remoteIndex.locate(tileIds, ensureActive)
            val installed = localIndex?.locate(tileIds, ensureActive)

            // tileId ordinati: le tile fino a alwaysDownloadMaxZoom sono le prime.
            val firstReusableId = zoomOffset(alwaysDownloadMaxZoom + 1)
            fun reusable(i: Int) = installed != null && installed.lengths[i] == remote.lengths[i] && tileIds[i] >= firstReusableId

            // Le tile di una stessa voce con runLength > 1 (mare, terra vuota) hanno lo stesso offset:
            // contano (e si scaricano) una volta sola.
            var bytesToDownload = 0L
            var previousOffset = -1L
            for (i in tileIds.indices) {
                if (remote.lengths[i] < 0 || reusable(i) || remote.offsets[i] == previousOffset) continue
                bytesToDownload += remote.lengths[i]
                previousOffset = remote.offsets[i]
            }

            var downloaded = 0
            var bytesDownloaded = 0L
            var reused = 0
            var bytesReused = 0L
            // Blocco letto per ultimo: [bufferStart, bufferStart + buffer.size) nel file remoto.
            var bufferStart = -1L
            var buffer = ByteArray(0)
            // Tile prima del blocco: contenuti condivisi da molte tile (il mare) salvati una volta
            // sola nella build; letti a parte senza spostare il blocco.
            val earlier = HashMap<Long, ByteArray>()
            var lastDownloadedOffset = -1L
            for (i in tileIds.indices) {
                if (i % ENSURE_ACTIVE_EVERY == 0) ensureActive()
                val length = remote.lengths[i]
                if (length < 0) continue
                if (reusable(i)) {
                    reused++
                    bytesReused += length
                    tiles.add(tileIds[i], readLocal(local!!, installed!!.offsets[i], length))
                    continue
                }
                val offset = remote.offsets[i]
                val data = if (offset >= bufferStart && offset + length <= bufferStart + buffer.size) {
                    val start = (offset - bufferStart).toInt()
                    buffer.copyOfRange(start, start + length)
                } else if (offset < bufferStart) {
                    earlier.getOrPut(offset) { readRemote(channel, offset, length) }
                } else {
                    ensureActive()
                    val end = batchEnd(i, tileIds.size, offset, remote, ::reusable)
                    bufferStart = offset
                    buffer = readRemote(channel, offset, (end - offset).toInt())
                    buffer.copyOfRange(0, length)
                }
                tiles.add(tileIds[i], data)
                if (offset != lastDownloadedOffset) {
                    downloaded++
                    bytesDownloaded += length
                    lastDownloadedOffset = offset
                    onProgress(bytesDownloaded, bytesToDownload)
                }
            }
            return PmtilesExtractionStats(installed != null, downloaded, bytesDownloaded, reused, bytesReused)
        } finally {
            local?.close()
        }
    }

    /**
     * Fine (esclusa) del blocco da leggere a partire dalla tile [first] all'offset [start]: si
     * aggiungono le tile successive da scaricare finche' sono dopo la fine attuale con un buco di al
     * massimo [MAX_GAP_BYTES] e il blocco resta entro [MAX_BATCH_BYTES]. Quelle gia' dentro il blocco
     * (stesso contenuto) o prima di [start] (lette a parte) non lo allungano.
     */
    private fun batchEnd(first: Int, count: Int, start: Long, remote: PmtilesTileLocations, reusable: (Int) -> Boolean): Long {
        var end = start + remote.lengths[first]
        for (j in first + 1 until count) {
            val length = remote.lengths[j]
            if (length < 0 || reusable(j)) continue
            val offset = remote.offsets[j]
            if (offset < start || offset + length <= end) continue
            if (offset < end || offset - end > MAX_GAP_BYTES || offset + length - start > MAX_BATCH_BYTES) break
            end = offset + length
        }
        return end
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
        const val ALWAYS_DOWNLOAD_MAX_ZOOM = 6
        const val MAX_BATCH_BYTES = 16L * 1024 * 1024
        const val MAX_GAP_BYTES = 256L * 1024
        const val ENSURE_ACTIVE_EVERY = 256
        // Sotto questa soglia un errore di scrittura e' quasi certamente il disco pieno.
        const val LOW_SPACE_BYTES = 16L * 1024 * 1024
    }
}
