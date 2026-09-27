package com.pockettravel.core.sync

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Legge un archivio PMTiles v3 (stesso formato scritto da [PmtilesWriter]: header 127 byte,
 * root/leaf directory gzip-compresse, id delta-encoded + runLength + length + offset in varint,
 * offset 0 = contiguo al precedente) camminando la directory invece di provare le coordinate delle
 * tile una per una come ch.poole.geo.pmtiles.Reader.getTile(z,x,y) (l'unica API di lettura della
 * libreria): [RegionAddressGridInstaller] ne ha bisogno per le celle molto rade del seme (z3-z6),
 * dove provare ogni coordinata z14 discendente vorrebbe dire da milioni a centinaia di milioni di
 * chiamate quasi tutte a vuoto. Il costo di [forEachTile] e' proporzionale alle tile davvero
 * presenti (voci di directory), non allo spazio di coordinate della cella.
 *
 * Compressione interna (directory e metadati): nessuna o gzip; le celle arrivano dalla pipeline
 * (Planetiler, GenerateAddresses), il file installato da [PmtilesWriter]. Round-trip verificato nei test con [PmtilesWriter] stesso (root
 * e leaf directory comprese), la stessa garanzia di correttezza usata li' (nessun reader indipendente
 * scrive questo formato, solo ch.poole.geo.pmtiles.Reader lo legge, e non enumera la directory).
 */
class PmtilesDirectoryReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val rootDirOffset: Long
    private val rootDirLength: Long
    private val metadataOffset: Long
    private val metadataLength: Long
    private val leafDirOffset: Long
    private val tileDataOffset: Long
    private val internalGzip: Boolean

    val tileCompression: Byte
    val tileType: Byte
    val minZoom: Int
    val maxZoom: Int

    init {
        val header = ByteArray(HEADER_LENGTH)
        raf.seek(0)
        raf.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(MAGIC.indices.all { header[it] == MAGIC[it] }) { "Non e' un archivio PMTiles: ${file.name}" }
        rootDirOffset = buffer.getLong(8)
        rootDirLength = buffer.getLong(16)
        metadataOffset = buffer.getLong(24)
        metadataLength = buffer.getLong(32)
        leafDirOffset = buffer.getLong(40)
        tileDataOffset = buffer.getLong(56)
        val internalCompression = buffer.get(97).toInt()
        require(internalCompression == COMPRESSION_NONE || internalCompression == COMPRESSION_GZIP) {
            "Compressione interna non supportata ($internalCompression): ${file.name}"
        }
        internalGzip = internalCompression == COMPRESSION_GZIP
        tileCompression = buffer.get(98)
        tileType = buffer.get(99)
        minZoom = buffer.get(100).toInt() and 0xFF
        maxZoom = buffer.get(101).toInt() and 0xFF
    }

    val metadata: String by lazy { String(gunzip(readBytes(metadataOffset, metadataLength.toInt())), Charsets.UTF_8) }

    /**
     * Chiama [action] per ogni tile indirizzata dell'archivio (root e leaf directory comprese), con
     * il suo tileId (zoomOffset incluso, come [PmtilesEntry]/Hilbert.zxyToIndex + zoomOffset) e i
     * suoi dati cosi' come sono (compressi o meno a seconda di [tileCompression]). Le entry con
     * runLength > 1 condividono lo stesso contenuto: letto una volta sola, passato a [action] per
     * ogni tileId che copre. [ensureActive] e' invocato una volta per voce di directory (root e
     * leaf), per interrompere una camminata lunga (es. CoroutineScope.ensureActive del worker
     * annullato).
     */
    fun forEachTile(ensureActive: () -> Unit = {}, action: (tileId: Long, data: ByteArray) -> Unit) {
        visit(readDirectory(rootDirOffset, rootDirLength), ensureActive, action)
    }

    private fun visit(entries: List<DirEntry>, ensureActive: () -> Unit, action: (Long, ByteArray) -> Unit) {
        for (entry in entries) {
            ensureActive()
            if (entry.runLength == 0L) {
                // Puntatore a una leaf directory (spec v3): id non usato qui, si cammina anche quella.
                visit(readDirectory(leafDirOffset + entry.offset, entry.length), ensureActive, action)
            } else {
                val data = readBytes(tileDataOffset + entry.offset, entry.length.toInt())
                for (i in 0 until entry.runLength) action(entry.id + i, data)
            }
        }
    }

    private data class DirEntry(val id: Long, val runLength: Long, val length: Long, val offset: Long)

    /** Stesso formato di PmtilesWriter.encodeDirectory: num_entries, poi id delta-encoded, RunLength, Length, Offset — tutti varint. */
    private fun readDirectory(offset: Long, length: Long): List<DirEntry> {
        val raw = gunzip(readBytes(offset, length.toInt()))
        var position = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = raw[position++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val count = varint().toInt()
        var lastId = 0L
        val ids = LongArray(count) { lastId += varint(); lastId }
        val runLengths = LongArray(count) { varint() }
        val lengths = LongArray(count) { varint() }
        val entries = ArrayList<DirEntry>(count)
        var previousOffset = 0L
        var previousLength = 0L
        for (i in 0 until count) {
            val code = varint()
            val decodedOffset = if (code == 0L && i > 0) previousOffset + previousLength else code - 1
            entries += DirEntry(ids[i], runLengths[i], lengths[i], decodedOffset)
            previousOffset = decodedOffset
            previousLength = lengths[i]
        }
        return entries
    }

    private fun readBytes(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        if (internalGzip) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes

    override fun close() {
        raf.close()
    }

    private companion object {
        const val HEADER_LENGTH = 127
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        val MAGIC = byteArrayOf(0x50, 0x4D, 0x54, 0x69, 0x6C, 0x65, 0x73) // "PMTiles"
    }
}
