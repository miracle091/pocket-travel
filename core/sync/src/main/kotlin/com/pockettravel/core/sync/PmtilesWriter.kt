package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Una tile gia' pronta per l'archivio: id calcolato con la stessa curva di Hilbert usata da
 *  ch.poole.geo.pmtiles.Reader (PmtilesExtractor.tileId), dati esattamente come restituiti da
 *  Reader.getTile() — compressi o meno a seconda di tileCompression, mai ri-processati qui. */
data class PmtilesEntry(val tileId: Long, val data: ByteArray)

/**
 * Accumula le tile su un file temporaneo in [directory] man mano che arrivano, tenendo in
 * memoria solo tileId/offset/lunghezza (~20 byte per tile) invece dei dati: per regioni grandi
 * a z14-15 tenere tutte le ByteArray in RAM (piu' le copie fatte dal writer) portava a
 * OutOfMemoryError. Le tile identiche (mare, terra vuota: frequentissime) sono scritte una sola
 * volta e poi riusate per offset, riconosciute da SHA-256 (primi 128 bit) + lunghezza.
 * close() cancella sempre il file temporaneo, anche dopo un errore o un annullamento.
 */
class PmtilesTileSpool(directory: File) : Closeable {
    private val tempFile: File = File.createTempFile("pmtiles-", ".tiles.tmp", directory)
    private val out = BufferedOutputStream(FileOutputStream(tempFile))
    private val digest = MessageDigest.getInstance("SHA-256")
    private val offsetByContent = HashMap<ContentKey, Long>()
    private var ids = LongArray(INITIAL_CAPACITY)
    private var offsets = LongArray(INITIAL_CAPACITY)
    private var lengths = IntArray(INITIAL_CAPACITY)
    private var dataLength = 0L
    private var dataFinished = false

    /** Tile aggiunte, ripetute comprese (= "addressed tiles" dello spec). */
    var tileCount = 0
        private set

    /** Contenuti distinti davvero scritti sul file temporaneo (= "tile contents" dello spec). */
    var uniqueTileCount = 0L
        private set

    /** true finche' le tile arrivano in ordine di tileId crescente: solo allora i dati sono
     *  "clustered" nel senso dello spec (l'estrattore le scarica per colonne, non in Hilbert). */
    var inTileIdOrder = true
        private set

    private data class ContentKey(val high: Long, val low: Long, val length: Int)

    fun add(tileId: Long, data: ByteArray) {
        check(!dataFinished) { "Dati delle tile gia' chiusi" }
        if (tileCount > 0 && tileId <= ids[tileCount - 1]) inTileIdOrder = false
        val hash = ByteBuffer.wrap(digest.digest(data))
        val key = ContentKey(hash.getLong(0), hash.getLong(8), data.size)
        val offset = offsetByContent[key] ?: dataLength.also { newOffset ->
            out.write(data)
            dataLength += data.size
            uniqueTileCount++
            offsetByContent[key] = newOffset
        }
        if (tileCount == ids.size) {
            val capacity = ids.size * 2
            ids = ids.copyOf(capacity)
            offsets = offsets.copyOf(capacity)
            lengths = lengths.copyOf(capacity)
        }
        ids[tileCount] = tileId
        offsets[tileCount] = offset
        lengths[tileCount] = data.size
        tileCount++
    }

    /** Chiude la scrittura dei dati e ne restituisce la lunghezza totale in byte. */
    internal fun finishData(): Long {
        if (!dataFinished) {
            out.close()
            dataFinished = true
        }
        return dataLength
    }

    internal val dataFile: File get() = tempFile

    /** Entry di directory ordinate per tileId (come richiesto dallo spec). Tile con id
     *  consecutivi e stesso contenuto diventano una sola entry con runLength > 1. */
    internal fun directoryEntries(): DirectoryEntries {
        val n = tileCount
        val sortedIds = ids.copyOf(n).also { it.sort() }
        for (i in 1 until n) require(sortedIds[i] != sortedIds[i - 1]) { "tileId duplicati" }
        // Ordinamento con soli array primitivi (niente indici boxed): ogni id e' unico, quindi
        // la sua posizione finale si trova con una ricerca binaria sugli id ordinati.
        val sortedOffsets = LongArray(n)
        val sortedLengths = LongArray(n)
        for (i in 0 until n) {
            val position = sortedIds.binarySearch(ids[i])
            sortedOffsets[position] = offsets[i]
            sortedLengths[position] = lengths[i].toLong()
        }
        val runLengths = LongArray(n)
        var count = 0
        for (i in 0 until n) {
            val extendsRun = count > 0 &&
                sortedIds[i] == sortedIds[count - 1] + runLengths[count - 1] &&
                sortedOffsets[i] == sortedOffsets[count - 1] &&
                sortedLengths[i] == sortedLengths[count - 1]
            if (extendsRun) {
                runLengths[count - 1]++
            } else {
                sortedIds[count] = sortedIds[i]
                sortedOffsets[count] = sortedOffsets[i]
                sortedLengths[count] = sortedLengths[i]
                runLengths[count] = 1L
                count++
            }
        }
        return DirectoryEntries(
            ids = sortedIds.copyOf(count),
            runLengths = runLengths.copyOf(count),
            lengths = sortedLengths.copyOf(count),
            offsets = sortedOffsets.copyOf(count),
        )
    }

    override fun close() {
        try {
            out.close()
        } finally {
            tempFile.delete()
        }
    }

    internal class DirectoryEntries(
        val ids: LongArray,
        val runLengths: LongArray,
        val lengths: LongArray,
        val offsets: LongArray,
    )

    private companion object {
        const val INITIAL_CAPACITY = 1_024
    }
}

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
        // In ordine di tileId: dati "clustered" e, senza tile ripetute, file identico byte per
        // byte a quello del writer precedente (tutto in memoria).
        PmtilesTileSpool(outputFile.absoluteFile.parentFile).use { tiles ->
            entries.sortedBy { it.tileId }.forEach { tiles.add(it.tileId, it.data) }
            write(
                outputFile = outputFile,
                tiles = tiles,
                metadataJson = metadataJson,
                tileCompression = tileCompression,
                tileType = tileType,
                minZoom = minZoom,
                maxZoom = maxZoom,
                minLon = minLon,
                minLat = minLat,
                maxLon = maxLon,
                maxLat = maxLat,
            )
        }
    }

    /**
     * Scrive l'archivio in streaming: header, root directory, metadata e leaf directory, poi i
     * dati delle tile copiati a blocchi dal file temporaneo di [tiles] (mai caricati interi in
     * memoria). Il file temporaneo lo cancella chi possiede [tiles], chiudendolo.
     */
    fun write(
        outputFile: File,
        tiles: PmtilesTileSpool,
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
        require(tiles.tileCount > 0) { "Nessuna tile da scrivere" }
        val tileDataLength = tiles.finishData()
        val directory = tiles.directoryEntries()
        val ids = directory.ids
        val runLengths = directory.runLengths
        val lengths = directory.lengths
        val offsets = directory.offsets

        val rootOnly = gzip(encodeDirectory(ids, runLengths, lengths, offsets))
        val leafDirs: List<ByteArray>
        val rootDirCompressed: ByteArray
        if (rootOnly.size <= MAX_ROOT_DIR_BYTES) {
            rootDirCompressed = rootOnly
            leafDirs = emptyList()
        } else {
            var chunkSize = INITIAL_LEAF_CHUNK_SIZE
            var built = buildWithLeaves(ids, runLengths, lengths, offsets, chunkSize)
            while (built.first.size > MAX_ROOT_DIR_BYTES && chunkSize < ids.size) {
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
            tileDataLength = tileDataLength,
            addressedTiles = tiles.tileCount.toLong(),
            tileEntries = ids.size.toLong(),
            tileContents = tiles.uniqueTileCount,
            clustered = tiles.inTileIdOrder,
            tileCompression = tileCompression,
            tileType = tileType,
            minZoom = minZoom,
            maxZoom = maxZoom,
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
        )

        BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
            out.write(header)
            out.write(rootDirCompressed)
            out.write(metadataCompressed)
            leafDirs.forEach { out.write(it) }
            tiles.dataFile.inputStream().use { it.copyTo(out) }
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
    private const val CLUSTERED_NO: Byte = 0

    // Layout a byte esatto letto dal codice sorgente di ch.poole.geo.pmtiles.Reader.Header
    // (non solo dalla prosa dello spec), per garantire compatibilita' byte-per-byte.
    private fun buildHeader(
        rootDirOffset: Long, rootDirLength: Long,
        metadataOffset: Long, metadataLength: Long,
        leafDirOffset: Long, leafDirLength: Long,
        tileDataOffset: Long, tileDataLength: Long,
        addressedTiles: Long, tileEntries: Long, tileContents: Long,
        clustered: Boolean,
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
        buffer.putLong(addressedTiles) // tile indirizzabili, ripetute comprese
        buffer.putLong(tileEntries) // entry di directory (runLength > 1 ne copre piu' di una)
        buffer.putLong(tileContents) // contenuti distinti nella sezione dati
        // Clustered solo se i dati sono in ordine di tileId crescente (i duplicati puntano a
        // offset precedenti, come lo spec consente); l'estrattore scarica per colonne, quindi no.
        buffer.put(if (clustered) CLUSTERED_YES else CLUSTERED_NO)
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
