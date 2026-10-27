package com.pockettravel.core.sync

import android.annotation.SuppressLint
import ch.poole.geo.pmtiles.Reader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PmtilesExtractionException(message: String) : Exception(message)

/** Errore di lettura dalla build remota (rete): l'aggiornamento incrementale non ripiega, il worker ritenta. */
private class RemoteReadException(cause: IOException) : IOException(cause.message, cause)

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
    // Zoom massimo scritto nel file: quello della sorgente, o uno in meno per la mappa leggera.
    val maxZoom: Int,
)

/**
 * Dettaglio della mappa: [FULL] tutti gli zoom della sorgente, [LIGHT] senza l'ultimo (la z14, circa il 40%
 * del peso: si perdono gli edifici piccoli e i nomi di molte vie minori, MapLibre ingrandisce la z13).
 */
enum class MapDetail { FULL, LIGHT }

/**
 * Estrae, lato dispositivo, solo le tile dentro il bounding box di una regione dalla build
 * whole-planet pubblica di Protomaps (mapSource.sourceUrl) — mai scaricato il file intero
 * (~120 GB): PMTiles legge prima la (piccola) directory delle tile via HTTP range, poi solo
 * le tile richieste (ch.poole.geo.pmtiles-reader, BSD-3). Il contenitore .pmtiles locale
 * risultante e' scritto da PmtilesWriter (nessuna libreria pronta scrive questo formato).
 *
 * Con una mappa gia' installata (previousMap) l'aggiornamento e' incrementale: si confrontano le
 * directory della build nuova e del file locale per le tile del rettangolo, e si scaricano solo le
 * tile nuove o con lunghezza diversa; le altre si copiano dal file locale, byte per byte, senza
 * ricompressione. Tra due build una tile con lo stesso contenuto ha la stessa lunghezza; il caso
 * inverso (stessa lunghezza, contenuto diverso) e' accettato: la tile resta quella vecchia fino al
 * prossimo aggiornamento che la cambia di lunghezza. Non e' raro alle zoom basse, che coprono aree
 * grandi (su San Marino, tra le build del 24 e del 30 settembre 2026, 3 tile su 75 a z0, z7 e z10):
 * fino a [alwaysDownloadMaxZoom] le tile si riscaricano sempre, circa 1,7 MB per l'Italia fino a z6
 * (115 MB fino a z10). Se qualcosa non torna (file locale
 * illeggibile, compressione o tipo di tile diversi) si ripiega sull'estrazione completa; gli errori
 * di rete e l'annullamento invece si propagano (il worker ritenta).
 *
 * Chiamato dentro RegionPackageDownloadWorker, non un meccanismo di download separato: per
 * l'utente e' lo stesso "Scarica" degli altri pacchetti.
 */
class PmtilesExtractor internal constructor(
    private val okHttpClient: OkHttpClient,
    private val alwaysDownloadMaxZoom: Int,
    private val retryDelaysMs: List<Long>,
    private val readTimeoutMs: Long,
    private val rangeTuning: RangeTuning = RangeTuning(),
) {

    @Inject constructor(okHttpClient: OkHttpClient) : this(okHttpClient, ALWAYS_DOWNLOAD_MAX_ZOOM, RETRY_DELAYS_MS, READ_TIMEOUT_MS)

    /**
     * Bloccante (richieste HTTP range): va chiamato fuori dal main thread. [ensureActive] e'
     * invocato prima di ogni richiesta e deve lanciare un'eccezione per interrompere
     * l'estrazione (es. CoroutineScope.ensureActive del worker annullato). [onProgress] riceve i
     * byte di tile scaricati sul totale da scaricare; riparte da 0 se si ripiega sull'estrazione completa.
     * [isCancelled], chiamato da un altro thread mentre si aspetta la rete, interrompe la richiesta in corso
     * (altrimenti l'annullamento arriverebbe solo alla richiesta successiva).
     */
    fun extract(
        mapSource: MapExtractionSource,
        outputFile: File,
        previousMap: File? = null,
        detail: MapDetail = MapDetail.FULL,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        ensureActive: () -> Unit = {},
    ): PmtilesExtractionStats {
        val directory = requireNotNull(outputFile.absoluteFile.parentFile)
        // Spool di un tentativo ucciso dal sistema (il close() non e' mai arrivato): nella cartella di
        // staging della regione gira una sola estrazione alla volta, quindi i .tmp rimasti sono orfani.
        directory.listFiles { file -> file.name.startsWith(SPOOL_PREFIX) && file.name.endsWith(SPOOL_SUFFIX) }?.forEach { it.delete() }
        val client = okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
        OkHttpRangeChannel(client, mapSource.sourceUrl, retryDelaysMs, rangeTuning, isCancelled).use { channel ->
            Reader(channel).use { reader ->
                if (previousMap != null && previousMap.isFile) {
                    var interruption: Throwable? = null
                    try {
                        // Le tile finiscono subito su un file temporaneo accanto all'output (non in RAM,
                        // vedi PmtilesTileSpool), cancellato alla chiusura anche su errore o annullamento.
                        return PmtilesTileSpool(directory).use { tiles ->
                            val stats = fetchTiles(channel, mapSource, previousMap, detail, tiles, onProgress) {
                                try {
                                    ensureActive()
                                } catch (error: Throwable) {
                                    interruption = error
                                    throw error
                                }
                            }
                            writeArchive(reader, mapSource, stats.maxZoom, tiles, outputFile)
                            stats
                        }
                    } catch (error: PmtilesExtractionException) {
                        throw error
                    } catch (error: RemoteReadException) {
                        // Rete: l'estrazione completa fallirebbe uguale e scaricherebbe tutto da capo.
                        throw error
                    } catch (error: Exception) {
                        if (error === interruption) throw error
                        // Disco pieno: l'estrazione completa riscaricherebbe tutte le tile per fallire uguale. Conta lo
                        // spazio libero reale dopo l'errore di scrittura, non quello che il sistema potrebbe liberare.
                        @SuppressLint("UsableSpace")
                        val lowSpace = directory.usableSpace < LOW_SPACE_BYTES
                        if (error is IOException && (error.message.orEmpty().contains("ENOSPC") || lowSpace)) throw error
                        // altrimenti ripiego sull'estrazione completa qui sotto
                    }
                }
                return PmtilesTileSpool(directory).use { tiles ->
                    val stats = fetchTiles(channel, mapSource, null, detail, tiles, onProgress, ensureActive)
                    writeArchive(reader, mapSource, stats.maxZoom, tiles, outputFile)
                    stats
                }
            }
        }
    }

    private fun writeArchive(reader: Reader, mapSource: MapExtractionSource, maxZoom: Int, tiles: PmtilesTileSpool, outputFile: File) {
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
            maxZoom = maxZoom,
            minLon = mapSource.minLon,
            minLat = mapSource.minLat,
            maxLon = mapSource.maxLon,
            maxLat = mapSource.maxLat,
        )
    }

    /**
     * Tile del rettangolo dalla build remota. Con [previousMap] (aggiornamento incrementale) si scaricano
     * solo le tile assenti o di lunghezza diversa nella mappa installata, piu' tutte quelle fino a
     * [alwaysDownloadMaxZoom]; senza, tutte.
     *
     * Una richiesta HTTP per tile sarebbe lenta (circa 3 MB al minuto sulla Lettonia, limitati dalla
     * latenza): la build Protomaps e' in ordine di tileId (clustered), quindi le tile vicine del
     * rettangolo stanno quasi sempre una dopo l'altra nel file e si leggono a blocchi, una richiesta
     * per blocco di dimensione adattiva ([OkHttpRangeChannel.nextBatchBytes]: da 256 KB a 16 MB, secondo la velocita' delle richieste
     * precedenti), scaricando anche i buchi tra una tile e l'altra fino a
     * [MAX_GAP_BYTES]. [onProgress] riceve i byte delle tile da scaricare (buchi esclusi).
     *
     * I tileId si trattano a gruppi di qualche migliaio ([forEachTileIdBlock]): per le regioni piu' grandi
     * (decine di milioni di tile) l'elenco intero e le posizioni nei due archivi non starebbero in memoria. Il
     * totale dei byte per l'avanzamento richiede una prima passata sull'indice, poi una seconda scarica.
     */
    private fun fetchTiles(
        channel: OkHttpRangeChannel,
        mapSource: MapExtractionSource,
        previousMap: File?,
        detail: MapDetail,
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

            // Mappa leggera: senza l'ultimo zoom.
            val light = detail == MapDetail.LIGHT && mapSource.maxZoom > mapSource.minZoom
            val lastZoom = if (light) mapSource.maxZoom - 1 else mapSource.maxZoom

            // tileId ordinati: le tile fino a alwaysDownloadMaxZoom sono le prime.
            val firstReusableId = zoomOffset(alwaysDownloadMaxZoom + 1)
            fun forEachBlock(action: (tileIds: LongArray, remote: PmtilesTileLocations, installed: PmtilesTileLocations?) -> Unit) {
                forEachTileIdBlock(mapSource.minLon, mapSource.minLat, mapSource.maxLon, mapSource.maxLat, mapSource.minZoom, lastZoom) { tileIds ->
                    val remote = remoteIndex.locate(tileIds, ensureActive)
                    action(tileIds, remote, localIndex?.locate(tileIds, ensureActive))
                }
            }
            fun reusable(i: Int, tileIds: LongArray, remote: PmtilesTileLocations, installed: PmtilesTileLocations?) =
                installed != null && installed.lengths[i] == remote.lengths[i] && tileIds[i] >= firstReusableId

            // Le tile di una stessa voce con runLength > 1 (mare, terra vuota) hanno lo stesso offset:
            // contano (e si scaricano) una volta sola.
            var bytesToDownload = 0L
            var previousOffset = -1L
            forEachBlock { tileIds, remote, installed ->
                for (i in tileIds.indices) {
                    if (remote.lengths[i] < 0 || reusable(i, tileIds, remote, installed) || remote.offsets[i] == previousOffset) continue
                    bytesToDownload += remote.lengths[i]
                    previousOffset = remote.offsets[i]
                }
            }

            var downloaded = 0
            var bytesDownloaded = 0L
            var reused = 0
            var bytesReused = 0L
            // Blocco letto per ultimo: [bufferStart, bufferStart + buffer.size) nel file remoto.
            var bufferStart = -1L
            var buffer = ByteArray(0)
            var lastDownloadedOffset = -1L
            var processed = 0L
            forEachBlock { tileIds, remote, installed ->
                for (i in tileIds.indices) {
                    if (processed++ % ENSURE_ACTIVE_EVERY == 0L) ensureActive()
                    val length = remote.lengths[i]
                    if (length < 0) continue
                    if (reusable(i, tileIds, remote, installed)) {
                        reused++
                        bytesReused += length
                        // Chiave dello spool: la posizione nel file, quella locale distinta da quella remota.
                        val localOffset = requireNotNull(installed).offsets[i]
                        val key = LOCAL_KEY_FLAG or localOffset
                        if (!tiles.addKnown(tileIds[i], key, length)) tiles.add(tileIds[i], readLocal(requireNotNull(local), localOffset, length), key)
                        continue
                    }
                    val offset = remote.offsets[i]
                    // Contenuti condivisi da molte tile (il mare) sono salvati una volta sola nella build e nello spool.
                    if (!tiles.addKnown(tileIds[i], offset, length)) {
                        val data = if (offset >= bufferStart && offset + length <= bufferStart + buffer.size) {
                            val start = (offset - bufferStart).toInt()
                            buffer.copyOfRange(start, start + length)
                        } else if (offset < bufferStart) {
                            // Prima del blocco: letto a parte senza spostarlo.
                            readRemote(channel, offset, length)
                        } else {
                            ensureActive()
                            val end = batchEnd(i, tileIds.size, offset, remote, channel.nextBatchBytes()) { reusable(it, tileIds, remote, installed) }
                            bufferStart = offset
                            buffer = readRemote(channel, offset, (end - offset).toInt())
                            buffer.copyOfRange(0, length)
                        }
                        tiles.add(tileIds[i], data, offset)
                    }
                    if (offset != lastDownloadedOffset) {
                        downloaded++
                        bytesDownloaded += length
                        lastDownloadedOffset = offset
                        onProgress(bytesDownloaded, bytesToDownload)
                    }
                }
            }
            return PmtilesExtractionStats(localIndex != null, downloaded, bytesDownloaded, reused, bytesReused, lastZoom)
        } finally {
            local?.close()
        }
    }

    /**
     * Fine (esclusa) del blocco da leggere a partire dalla tile [first] all'offset [start]: si
     * aggiungono le tile successive da scaricare finche' sono dopo la fine attuale con un buco di al
     * massimo [MAX_GAP_BYTES] e il blocco resta entro [maxBatchBytes]. Quelle gia' dentro il blocco
     * (stesso contenuto) o prima di [start] (lette a parte) non lo allungano.
     */
    private fun batchEnd(first: Int, count: Int, start: Long, remote: PmtilesTileLocations, maxBatchBytes: Long, reusable: (Int) -> Boolean): Long {
        var end = start + remote.lengths[first]
        for (j in first + 1 until count) {
            val length = remote.lengths[j]
            if (length < 0 || reusable(j)) continue
            val offset = remote.offsets[j]
            if (offset < start || offset + length <= end) continue
            if (offset < end || offset - end > MAX_GAP_BYTES || offset + length - start > maxBatchBytes) break
            end = offset + length
        }
        return end
    }

    private fun readRemote(channel: FileChannel, position: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        try {
            val count = channel.read(buffer, position)
            if (count != length) throw IOException("Lettura incompleta: $count byte di $length")
        } catch (error: IOException) {
            throw RemoteReadException(error)
        }
        return buffer.array()
    }

    private fun readLocal(file: RandomAccessFile, position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        file.seek(position)
        file.readFully(bytes)
        return bytes
    }

    private companion object {
        const val ALWAYS_DOWNLOAD_MAX_ZOOM = 6
        const val MAX_GAP_BYTES = 256L * 1024
        const val ENSURE_ACTIVE_EVERY = 256L
        const val CONNECT_TIMEOUT_MS = 15_000L
        // Tra due byte consecutivi della risposta: una connessione ferma scade e la richiesta si ritenta.
        const val READ_TIMEOUT_MS = 30_000L
        // Attese prima del secondo e del terzo tentativo della stessa richiesta.
        val RETRY_DELAYS_MS = listOf(1_000L, 3_000L)
        // Chiavi dello spool: le posizioni della mappa installata portano questo bit, per non confonderle con quelle remote.
        const val LOCAL_KEY_FLAG = 1L shl 62
        // Sotto questa soglia un errore di scrittura e' quasi certamente il disco pieno.
        const val LOW_SPACE_BYTES = 16L * 1024 * 1024
    }
}
