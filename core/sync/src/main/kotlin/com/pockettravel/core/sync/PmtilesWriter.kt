package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** Una tile gia' pronta per l'archivio: id calcolato con la stessa curva di Hilbert usata da
 *  ch.poole.geo.pmtiles.Reader (PmtilesExtractor.tileId), dati esattamente come restituiti da
 *  Reader.getTile() — compressi o meno a seconda di tileCompression, mai ri-processati qui. */
data class PmtilesEntry(val tileId: Long, val data: ByteArray)

// Nome dei file temporanei dello spool: PmtilesExtractor cancella quelli rimasti da un processo ucciso.
internal const val SPOOL_PREFIX = "pmtiles-"
internal const val SPOOL_SUFFIX = ".tiles.tmp"

private const val VARINT_PAYLOAD_MASK = 0x7F
private const val VARINT_CONTINUATION_BIT = 0x80
private const val VARINT_PAYLOAD_BITS = 7

// Gradi in interi a 1e-7, come i campi lon/lat dell'header PMTiles.
private const val E7_SCALE = 10_000_000.0

/**
 * Accumula le tile su un file temporaneo in [directory] man mano che arrivano, tenendo in
 * memoria solo la directory: una voce (tileId iniziale, tile consecutive, offset, lunghezza) per ogni
 * gruppo di tile consecutive con lo stesso contenuto, non una per tile (mare e terra vuota coprono
 * milioni di tile con poche voci). Tenere in memoria un indice per ogni tile di una regione grande,
 * o le ByteArray, esaurirebbe l'heap. I contenuti con la stessa chiave ([add], [addKnown]) sono
 * scritti una sola volta anche se non sono vicini. close() cancella sempre il file temporaneo, anche dopo
 * un errore o un annullamento.
 */
class PmtilesTileSpool(directory: File) : Closeable {
    private val tempFile: File = File.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX, directory)
    private val out = BufferedOutputStream(FileOutputStream(tempFile))
    private val offsetByKey = LongLongMap()
    private var ids = LongArray(INITIAL_CAPACITY)
    private var runLengths = LongArray(INITIAL_CAPACITY)
    private var offsets = LongArray(INITIAL_CAPACITY)
    private var lengths = IntArray(INITIAL_CAPACITY)
    private var entryCount = 0
    private var dataLength = 0L
    private var dataFinished = false

    /** Tile aggiunte, ripetute comprese (= "addressed tiles" dello spec). */
    var tileCount = 0
        private set

    /** Contenuti davvero scritti sul file temporaneo (= "tile contents" dello spec). */
    var uniqueTileCount = 0L
        private set

    /** true finche' le tile arrivano in ordine di tileId crescente: solo allora i dati sono
     *  "clustered" nel senso dello spec. */
    var inTileIdOrder = true
        private set

    /** Memoria trattenuta dalla directory in costruzione (array e tabella dei contenuti), in byte. */
    internal val retainedBytes: Long
        get() = (ids.size + runLengths.size + offsets.size) * Long.SIZE_BYTES + lengths.size * Int.SIZE_BYTES + offsetByKey.retainedBytes

    /**
     * Scrive [data] sul file temporaneo. Con una [contentKey] (>= 0) il contenuto si puo' riusare in seguito con
     * [addKnown] senza passare di nuovo i dati.
     */
    fun add(tileId: Long, data: ByteArray, contentKey: Long = NO_CONTENT_KEY) {
        check(!dataFinished) { "Dati delle tile gia' chiusi" }
        val offset = dataLength
        out.write(data)
        dataLength += data.size
        uniqueTileCount++
        if (contentKey != NO_CONTENT_KEY) offsetByKey.put(contentKey, offset)
        append(tileId, offset, data.size)
    }

    /** Aggiunge una tile con un contenuto gia' scritto con la stessa [contentKey]; false se la chiave non c'e'. */
    fun addKnown(tileId: Long, contentKey: Long, length: Int): Boolean {
        check(!dataFinished) { "Dati delle tile gia' chiusi" }
        val offset = offsetByKey.get(contentKey)
        if (offset < 0) return false
        append(tileId, offset, length)
        return true
    }

    /** Estende l'ultima voce se la tile la continua con lo stesso contenuto, altrimenti ne apre una. */
    private fun append(tileId: Long, offset: Long, length: Int) {
        tileCount++
        if (entryCount > 0) {
            val last = entryCount - 1
            val end = ids[last] + runLengths[last]
            if (tileId < end) {
                inTileIdOrder = false
            } else if (tileId == end && offsets[last] == offset && lengths[last] == length) {
                runLengths[last]++
                return
            }
        }
        if (entryCount == ids.size) {
            val capacity = ids.size * 2
            ids = ids.copyOf(capacity)
            runLengths = runLengths.copyOf(capacity)
            offsets = offsets.copyOf(capacity)
            lengths = lengths.copyOf(capacity)
        }
        ids[entryCount] = tileId
        runLengths[entryCount] = 1L
        offsets[entryCount] = offset
        lengths[entryCount] = length
        entryCount++
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
     *  consecutivi e stesso contenuto sono gia' una sola entry con runLength > 1. */
    internal fun directoryEntries(): DirectoryEntries {
        val n = entryCount
        if (inTileIdOrder) {
            return DirectoryEntries(
                ids = ids.copyOf(n),
                runLengths = runLengths.copyOf(n),
                lengths = LongArray(n) { lengths[it].toLong() },
                offsets = offsets.copyOf(n),
            )
        }
        val sortedIds = ids.copyOf(n).also { it.sort() }
        for (i in 1 until n) require(sortedIds[i] != sortedIds[i - 1]) { "tileId duplicati" }
        // Ordinamento con soli array primitivi (niente indici boxed): ogni id e' unico, quindi
        // la sua posizione finale si trova con una ricerca binaria sugli id ordinati.
        val sortedRunLengths = LongArray(n)
        val sortedOffsets = LongArray(n)
        val sortedLengths = LongArray(n)
        for (i in 0 until n) {
            val position = sortedIds.binarySearch(ids[i])
            sortedRunLengths[position] = runLengths[i]
            sortedOffsets[position] = offsets[i]
            sortedLengths[position] = lengths[i].toLong()
        }
        for (i in 1 until n) require(sortedIds[i] >= sortedIds[i - 1] + sortedRunLengths[i - 1]) { "tileId duplicati" }
        // Dopo l'ordinamento voci vicine possono continuarsi: si uniscono.
        var count = 0
        for (i in 0 until n) {
            val extendsRun = count > 0 &&
                sortedIds[i] == sortedIds[count - 1] + sortedRunLengths[count - 1] &&
                sortedOffsets[i] == sortedOffsets[count - 1] &&
                sortedLengths[i] == sortedLengths[count - 1]
            if (extendsRun) {
                sortedRunLengths[count - 1] += sortedRunLengths[i]
            } else {
                sortedIds[count] = sortedIds[i]
                sortedRunLengths[count] = sortedRunLengths[i]
                sortedOffsets[count] = sortedOffsets[i]
                sortedLengths[count] = sortedLengths[i]
                count++
            }
        }
        return DirectoryEntries(
            ids = sortedIds.copyOf(count),
            runLengths = sortedRunLengths.copyOf(count),
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

    companion object {
        /** Contenuto senza chiave: scritto sempre, mai riusabile con [addKnown]. */
        const val NO_CONTENT_KEY = -1L
        private const val INITIAL_CAPACITY = 1_024
    }
}

/** Mappa da Long (>= 0) a Long con indirizzamento aperto: senza un oggetto per voce, perche' i contenuti distinti sono milioni. */
private class LongLongMap {
    private var keys = LongArray(INITIAL_SLOTS) { EMPTY }
    private var values = LongArray(INITIAL_SLOTS)
    private var size = 0

    val retainedBytes: Long get() = keys.size * 2L * Long.SIZE_BYTES

    /** Il valore di [key], o -1 se manca. */
    fun get(key: Long): Long {
        var slot = slotOf(key, keys.size)
        while (keys[slot] != EMPTY) {
            if (keys[slot] == key) return values[slot]
            slot = (slot + 1) and (keys.size - 1)
        }
        return -1L
    }

    fun put(key: Long, value: Long) {
        require(key >= 0) { "Chiave negativa" }
        if ((size + 1) * 2 > keys.size) grow()
        var slot = slotOf(key, keys.size)
        while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
        if (keys[slot] == EMPTY) {
            keys[slot] = key
            size++
        }
        values[slot] = value
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = LongArray(oldKeys.size * 2)
        for (i in oldKeys.indices) {
            if (oldKeys[i] == EMPTY) continue
            var slot = slotOf(oldKeys[i], keys.size)
            while (keys[slot] != EMPTY) slot = (slot + 1) and (keys.size - 1)
            keys[slot] = oldKeys[i]
            values[slot] = oldValues[i]
        }
    }

    // Moltiplicazione per la costante aurea a 64 bit: gli offset sono molto regolari e senza mescolarli si ammassano.
    private fun slotOf(key: Long, slots: Int): Int = ((key * GOLDEN_RATIO) ushr HASH_SHIFT).toInt() and (slots - 1)

    private companion object {
        const val EMPTY = -1L
        const val INITIAL_SLOTS = 1_024
        const val GOLDEN_RATIO = -7046029254386353131L
        const val HASH_SHIFT = 32
    }
}

/**
 * Scrive un archivio PMTiles v3 valido a partire da un set di tile gia' risolte. Nessuna
 * libreria pronta offre un writer (solo lettura, vedi PmtilesExtractor): implementato da zero
 * seguendo lo spec ufficiale (github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md) e
 * verificato via round-trip nei test con la stessa libreria di lettura indipendente
 * (ch.poole.geo.pmtiles.Reader) usata per leggere la sorgente remota: la compatibilita' col
 * reader indipendente e' la garanzia di correttezza.
 *
 * Il file prodotto riusa compressione/tipo tile e metadata della sorgente cosi' come sono
 * (nessuna ri-decodifica): lo schema dei layer vettoriali (nomi/campi) resta quello della
 * sorgente, che deve gia' corrispondere a quanto atteso dallo style MapLibre lato app
 * (vedi PmtilesTileSource: schema basemap ufficiale di Protomaps, non Shortbread).
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
        // In ordine di tileId: dati "clustered" nel senso dello spec.
        PmtilesTileSpool(requireNotNull(outputFile.absoluteFile.parentFile)).use { tiles ->
            // Contenuti uguali scritti una volta sola: la chiave e' la posizione della prima entry con quei byte.
            val firstWithContent = HashMap<ByteBuffer, Int>()
            entries.sortedBy { it.tileId }.forEachIndexed { index, entry ->
                val key = firstWithContent.getOrPut(ByteBuffer.wrap(entry.data)) { index }.toLong()
                if (!tiles.addKnown(entry.tileId, key, entry.data.size)) tiles.add(entry.tileId, entry.data, key)
            }
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

        val rootRunLengths = LongArray(rootIds.size)
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
            if (value and VARINT_PAYLOAD_MASK.toLong().inv() == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and VARINT_PAYLOAD_MASK.toLong()) or VARINT_CONTINUATION_BIT.toLong()).toInt())
            value = value ushr VARINT_PAYLOAD_BITS
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

    private fun toE7(value: Double): Int = (value * E7_SCALE).toInt()
}
