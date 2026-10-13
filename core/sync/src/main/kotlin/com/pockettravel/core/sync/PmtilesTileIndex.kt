package com.pockettravel.core.sync

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Legge `length` byte a partire da `position` di un archivio (file locale o sorgente HTTP range) o lancia [IOException]. */
internal fun interface PmtilesRangeSource {
    fun read(position: Long, length: Int): ByteArray
}

/**
 * Dove si trovano, dentro un archivio, le tile richieste: [offsets] (assoluti nel file) e [lengths]
 * (-1 = tile assente) sono allineati alla lista di tileId data a [PmtilesTileIndex.locate].
 */
internal class PmtilesTileLocations(val offsets: LongArray, val lengths: IntArray)

/**
 * Indice di un archivio PMTiles v3 (locale o remoto) limitato a un insieme di tile: cammina la
 * directory leggendo solo le leaf che possono contenere una delle tile cercate (per il pianeta
 * Protomaps, solo quelle del rettangolo della regione), senza leggere nessun dato di tile. Serve
 * all'aggiornamento incrementale della mappa (vedi [PmtilesExtractor]): la lunghezza di una tile
 * nella directory basta a riconoscere quelle cambiate tra due build. Stesso formato letto da
 * [PmtilesDirectoryReader], che invece cammina tutto l'archivio locale leggendo anche i dati.
 */
internal class PmtilesTileIndex(private val source: PmtilesRangeSource) {
    private val rootDirOffset: Long
    private val rootDirLength: Long
    private val leafDirOffset: Long
    private val tileDataOffset: Long
    private val internalGzip: Boolean
    val tileCompression: Byte
    val tileType: Byte

    init {
        val header = source.read(0, HEADER_LENGTH)
        if (!MAGIC.indices.all { header[it] == MAGIC[it] }) throw IOException("Non e' un archivio PMTiles")
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get(OFFSET_VERSION).toInt() != SUPPORTED_VERSION) {
            throw IOException("Versione PMTiles non supportata: ${buffer.get(OFFSET_VERSION)}")
        }
        rootDirOffset = buffer.getLong(OFFSET_ROOT_DIR)
        rootDirLength = buffer.getLong(OFFSET_ROOT_DIR_LENGTH)
        leafDirOffset = buffer.getLong(OFFSET_LEAF_DIRS)
        tileDataOffset = buffer.getLong(OFFSET_TILE_DATA)
        val internalCompression = buffer.get(OFFSET_INTERNAL_COMPRESSION).toInt()
        if (internalCompression != COMPRESSION_NONE && internalCompression != COMPRESSION_GZIP) {
            throw IOException("Compressione interna non supportata ($internalCompression)")
        }
        internalGzip = internalCompression == COMPRESSION_GZIP
        tileCompression = buffer.get(OFFSET_TILE_COMPRESSION)
        tileType = buffer.get(OFFSET_TILE_TYPE)
    }

    /**
     * [tileIds] deve essere ordinato e senza ripetizioni. [ensureActive] e' invocato prima di ogni
     * directory letta (ogni leaf puo' essere una richiesta HTTP).
     */
    fun locate(tileIds: LongArray, ensureActive: () -> Unit = {}): PmtilesTileLocations {
        val offsets = LongArray(tileIds.size)
        val lengths = IntArray(tileIds.size) { -1 }

        // upperId: primo id NON coperto dalla directory (quello della voce successiva nel padre).
        fun walk(offset: Long, length: Long, upperId: Long) {
            ensureActive()
            val directory = readDirectory(offset, length)
            for (i in directory.ids.indices) {
                val id = directory.ids[i]
                var next = lowerBound(tileIds, id)
                if (directory.runLengths[i] == 0L) {
                    // Puntatore a una leaf: la si legge solo se copre almeno una delle tile cercate.
                    val leafUpperId = if (i + 1 < directory.ids.size) directory.ids[i + 1] else upperId
                    if (next < tileIds.size && tileIds[next] < leafUpperId) {
                        walk(leafDirOffset + directory.offsets[i], directory.lengths[i], leafUpperId)
                    }
                } else {
                    val end = id + directory.runLengths[i]
                    while (next < tileIds.size && tileIds[next] < end) {
                        offsets[next] = tileDataOffset + directory.offsets[i]
                        lengths[next] = directory.lengths[i].toInt()
                        next++
                    }
                }
            }
        }
        walk(rootDirOffset, rootDirLength, Long.MAX_VALUE)
        return PmtilesTileLocations(offsets, lengths)
    }

    /** Indice del primo elemento di [sorted] >= [value] (sorted.size se non ce ne sono). */
    private fun lowerBound(sorted: LongArray, value: Long): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (sorted[middle] < value) low = middle + 1 else high = middle
        }
        return low
    }

    private class Directory(val ids: LongArray, val runLengths: LongArray, val lengths: LongArray, val offsets: LongArray)

    /** Stesso formato di PmtilesWriter.encodeDirectory: num_entries, poi id delta-encoded, RunLength, Length, Offset — tutti varint. */
    private fun readDirectory(offset: Long, length: Long): Directory {
        val compressed = source.read(offset, length.toInt())
        val raw = if (internalGzip) GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() } else compressed
        var position = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = raw[position++].toInt() and BYTE_MASK
                result = result or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
                if (b and VARINT_CONTINUATION_BIT == 0) return result
                shift += VARINT_PAYLOAD_BITS
            }
        }
        val count = varint().toInt()
        var lastId = 0L
        val ids = LongArray(count) { lastId += varint(); lastId }
        val runLengths = LongArray(count) { varint() }
        val lengths = LongArray(count) { varint() }
        val offsets = LongArray(count)
        for (i in 0 until count) {
            val code = varint()
            offsets[i] = if (code == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else code - 1
        }
        return Directory(ids, runLengths, lengths, offsets)
    }

    private companion object {
        const val HEADER_LENGTH = 127
        const val SUPPORTED_VERSION = 3
        const val OFFSET_VERSION = 7
        const val OFFSET_ROOT_DIR = 8
        const val OFFSET_ROOT_DIR_LENGTH = 16
        const val OFFSET_LEAF_DIRS = 40
        const val OFFSET_TILE_DATA = 56
        const val OFFSET_INTERNAL_COMPRESSION = 97
        const val OFFSET_TILE_COMPRESSION = 98
        const val OFFSET_TILE_TYPE = 99
        const val BYTE_MASK = 0xFF
        const val VARINT_PAYLOAD_MASK = 0x7F
        const val VARINT_CONTINUATION_BIT = 0x80
        const val VARINT_PAYLOAD_BITS = 7
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"
    }
}
