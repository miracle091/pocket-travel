package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** Una tile gia' pronta per l'archivio: id calcolato con la stessa curva di Hilbert usata da
 *  ch.poole.geo.pmtiles.Reader (PmtilesExtractor.tileId), dati esattamente come restituiti da
 *  Reader.getTile() — compressi o meno a seconda di tileCompression, mai ri-processati qui. */
data class PmtilesEntry(val tileId: Long, val data: ByteArray)

/**
 * Scrive un archivio PMTiles v3 valido a partire da un set di tile gia' risolte. Nessuna
 * libreria pronta offre un writer (solo lettura, vedi PmtilesExtractor): implementato da zero
 * seguendo lo spec ufficiale (github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md) e
 * verificato via round-trip nei test con la stessa libreria di lettura indipendente
 * (ch.poole.geo.pmtiles.Reader) usata per leggere la sorgente remota — non c'e' un device/
 * emulatore disponibile in ogni contesto di sviluppo per una verifica visiva, quindi la
 * compatibilita' col reader indipendente e' la garanzia di correttezza qui.
 *
 * Il file prodotto riusa compressione/tipo tile e metadata della sorgente cosi' come sono
 * (nessuna ri-decodifica): lo schema dei layer vettoriali (nomi/campi) resta quello della
 * sorgente, che deve gia' corrispondere a quanto atteso dallo style MapLibre lato app
 * (vedi PmtilesTileSource — schema basemap Protomaps ufficiale, non il nostro Shortbread).
 */
object PmtilesWriter {

    // Limite reale della root directory (16 KiB) meno un margine di sicurezza: lo spec fissa
    // 16384 come limite assoluto, qui si lascia un margine per non finire esattamente al bordo.
    private const val MAX_ROOT_DIR_BYTES = 16_257
    private const val INITIAL_LEAF_CHUNK_SIZE = 2_000

    fun write(
        outputFile: File,
        entries: List<PmtilesEntry>,
        metadataJson: String,
        tileCompression: Byte,
        tileType: Byte,
        minZoom: Int,
        maxZoom: Int,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
    ) {
        require(entries.isNotEmpty()) { "Nessuna tile da scrivere" }
        val sorted = entries.sortedBy { it.tileId }
        require(sorted.map { it.tileId }.toSet().size == sorted.size) { "tileId duplicati" }

        val tileData = ByteArrayOutputStream()
        val offsets = LongArray(sorted.size)
        val lengths = LongArray(sorted.size)
        var cursor = 0L
        sorted.forEachIndexed { index, entry ->
            offsets[index] = cursor
            lengths[index] = entry.data.size.toLong()
            tileData.write(entry.data)
            cursor += entry.data.size
        }
        val ids = LongArray(sorted.size) { sorted[it].tileId }
        val runLengths = LongArray(sorted.size) { 1L }

        val rootOnly = gzip(encodeDirectory(ids, runLengths, lengths, offsets))
        val leafDirs: List<ByteArray>
        val rootDirCompressed: ByteArray
        if (rootOnly.size <= MAX_ROOT_DIR_BYTES) {
            rootDirCompressed = rootOnly
            leafDirs = emptyList()
        } else {
            var chunkSize = INITIAL_LEAF_CHUNK_SIZE
            var built = buildWithLeaves(ids, runLengths, lengths, offsets, chunkSize)
            while (built.first.size > MAX_ROOT_DIR_BYTES && chunkSize < sorted.size) {
                chunkSize *= 2
                built = buildWithLeaves(ids, runLengths, lengths, offsets, chunkSize)
            }
            check(built.first.size <= MAX_ROOT_DIR_BYTES) {
                "Impossibile produrre una root directory valida (${built.first.size} byte)"
            }
            rootDirCompressed = built.first
            leafDirs = built.second
        }

        val metadataCompressed = gzip(metadataJson.toByteArray(Charsets.UTF_8))
        val tileDataBytes = tileData.toByteArray()

        val rootDirOffset = HEADER_LENGTH.toLong()
        val metadataOffset = rootDirOffset + rootDirCompressed.size
        val leafDirOffset = metadataOffset + metadataCompressed.size
        val leafDirLength = leafDirs.sumOf { it.size.toLong() }
        val tileDataOffset = leafDirOffset + leafDirLength

        val header = buildHeader(
            rootDirOffset = rootDirOffset,
            rootDirLength = rootDirCompressed.size.toLong(),
            metadataOffset = metadataOffset,
            metadataLength = metadataCompressed.size.toLong(),
            leafDirOffset = leafDirOffset,
            leafDirLength = leafDirLength,
            tileDataOffset = tileDataOffset,
            tileDataLength = tileDataBytes.size.toLong(),
            numTiles = sorted.size.toLong(),
            tileCompression = tileCompression,
            tileType = tileType,
            minZoom = minZoom,
            maxZoom = maxZoom,
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
        )

        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header)
            raf.write(rootDirCompressed)
            raf.write(metadataCompressed)
            leafDirs.forEach { raf.write(it) }
            raf.write(tileDataBytes)
        }
    }

    /** Divide le entry in blocchi da leafChunkSize, ciascuno una leaf directory a parte;
     *  la root diventa una directory di soli puntatori (runLength=0) a quelle leaf. */
    private fun buildWithLeaves(
        ids: LongArray,
        runLengths: LongArray,
        lengths: LongArray,
        offsets: LongArray,
        leafChunkSize: Int,
    ): Pair<ByteArray, List<ByteArray>> {
        val leafDirs = mutableListOf<ByteArray>()
        val rootIds = mutableListOf<Long>()
        val rootOffsets = mutableListOf<Long>()
        val rootLengths = mutableListOf<Long>()
        var cursor = 0L

        var start = 0
        while (start < ids.size) {
            val end = minOf(start + leafChunkSize, ids.size)
            val leafBytes = gzip(
                encodeDirectory(
                    ids.copyOfRange(start, end),
                    runLengths.copyOfRange(start, end),
                    lengths.copyOfRange(start, end),
                    offsets.copyOfRange(start, end),
                ),
            )
            rootIds += ids[start]
            rootOffsets += cursor
            rootLengths += leafBytes.size.toLong()
            leafDirs += leafBytes
            cursor += leafBytes.size
            start = end
        }

        val rootRunLengths = LongArray(rootIds.size) { 0L }
        val root = encodeDirectory(rootIds.toLongArray(), rootRunLengths, rootLengths.toLongArray(), rootOffsets.toLongArray())
        return gzip(root) to leafDirs
    }

    /** Formato esatto dello spec PMTiles v3: num_entries, poi TileID delta-encoded, RunLength,
     *  Length e infine Offset (0 = contiguo al precedente, altrimenti offset+1) — tutti varint. */
    private fun encodeDirectory(ids: LongArray, runLengths: LongArray, lengths: LongArray, offsets: LongArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarLong(out, ids.size.toLong())
        var lastId = 0L
        for (id in ids) {
            writeVarLong(out, id - lastId)
            lastId = id
        }
        for (runLength in runLengths) writeVarLong(out, runLength)
        for (length in lengths) writeVarLong(out, length)
        for (i in offsets.indices) {
            val contiguous = i > 0 && offsets[i] == offsets[i - 1] + lengths[i - 1]
            writeVarLong(out, if (contiguous) 0L else offsets[i] + 1)
        }
        return out.toByteArray()
    }

    private fun writeVarLong(out: ByteArrayOutputStream, valueIn: Long) {
        var value = valueIn
        while (true) {
            if (value and 0x7FL.inv() == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private const val HEADER_LENGTH = 127
    private val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"
    private const val PMTILES_VERSION: Byte = 3
    private const val CLUSTERED_YES: Byte = 1

    // Layout a byte esatto letto dal codice sorgente di ch.poole.geo.pmtiles.Reader.Header
    // (non solo dalla prosa dello spec), per garantire compatibilita' byte-per-byte.
    private fun buildHeader(
        rootDirOffset: Long, rootDirLength: Long,
        metadataOffset: Long, metadataLength: Long,
        leafDirOffset: Long, leafDirLength: Long,
        tileDataOffset: Long, tileDataLength: Long,
        numTiles: Long,
        tileCompression: Byte, tileType: Byte,
        minZoom: Int, maxZoom: Int,
        minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(PMTILES_VERSION)
        buffer.putLong(rootDirOffset)
        buffer.putLong(rootDirLength)
        buffer.putLong(metadataOffset)
        buffer.putLong(metadataLength)
        buffer.putLong(leafDirOffset)
        buffer.putLong(leafDirLength)
        buffer.putLong(tileDataOffset)
        buffer.putLong(tileDataLength)
        buffer.putLong(numTiles) // addressed tiles: nessuna deduplicazione RLE, 1 entry = 1 tile
        buffer.putLong(numTiles) // tile entries
        buffer.putLong(numTiles) // tile contents
        buffer.put(CLUSTERED_YES) // i dati sono scritti in ordine di tileId crescente
        buffer.put(Constants.COMPRESSION_GZIP) // compressione interna (directory/metadata)
        buffer.put(tileCompression)
        buffer.put(tileType)
        buffer.put(minZoom.toByte())
        buffer.put(maxZoom.toByte())
        buffer.putInt(toE7(minLon))
        buffer.putInt(toE7(minLat))
        buffer.putInt(toE7(maxLon))
        buffer.putInt(toE7(maxLat))
        buffer.put(minZoom.toByte()) // center zoom: nessuna preferenza, usa il minimo
        buffer.putInt(toE7((minLon + maxLon) / 2.0))
        buffer.putInt(toE7((minLat + maxLat) / 2.0))
        return buffer.array()
    }

    private fun toE7(value: Double): Int = (value * 10_000_000.0).toInt()
}
