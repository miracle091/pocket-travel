package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
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

// Una voce di directory sul disco: tileId, tile consecutive, offset nello spool (Long) e lunghezza (Int).
private const val RECORD_BYTES = 8 + 8 + 8 + 4
private const val FILE_BUFFER_BYTES = 16 * 1024

/** Riceve le voci della directory nell'ordine di tileId, senza che siano mai tutte in memoria. */
internal fun interface DirectoryEntrySink {
    fun accept(tileId: Long, runLength: Long, offset: Long, length: Int)
}

/**
 * Accumula le tile su un file temporaneo in [directory] man mano che arrivano. La directory (una voce
 * per ogni gruppo di tile consecutive con lo stesso contenuto: tileId iniziale, tile consecutive, offset,
 * lunghezza) non sta in memoria ma su un secondo file temporaneo, a record di dimensione fissa: in
 * memoria resta solo l'ultima voce, ancora estendibile, e una tabella dei contenuti ripetuti di dimensione
 * massima fissa. Una regione grande ha milioni di tile con contenuti distinti, e tenerne un indice in
 * memoria, o le ByteArray, esaurirebbe l'heap. close() cancella sempre i file temporanei, anche dopo
 * un errore o un annullamento.
 *
 * I contenuti piccoli con la stessa chiave ([add], [addKnown]) sono scritti una sola volta anche se non
 * sono vicini, finche' la tabella li ricorda: un contenuto dimenticato si riscrive, e l'archivio resta
 * corretto, solo un po' piu' grande. Le tile arrivano di norma in ordine di tileId; altrimenti
 * [finishData] ordina la directory a blocchi di [sortChunkRecords] voci, unendoli con un merge.
 */
class PmtilesTileSpool(
    private val directory: File,
    private val sortChunkRecords: Int = DEFAULT_SORT_CHUNK_RECORDS,
) : Closeable {
    private val tempFile: File = createTempFile()
    private var indexFile: File = createTempFile()
    private val out = BufferedOutputStream(FileOutputStream(tempFile))
    private var indexOut: DataOutputStream? = DataOutputStream(
        BufferedOutputStream(FileOutputStream(indexFile), FILE_BUFFER_BYTES),
    )
    private val contentTable = ContentTable()
    private var hasLast = false
    private var lastId = 0L
    private var lastRunLength = 0L
    private var lastOffset = 0L
    private var lastLength = 0
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

    /** Voci della directory finale; vale solo dopo [finishData]. */
    internal var directoryEntryCount = 0L
        private set

    /** Memoria trattenuta in attesa delle voci di directory (tabella dei contenuti e buffer dei file), in byte. */
    internal val retainedBytes: Long
        get() = contentTable.retainedBytes + FILE_BUFFER_BYTES + DATA_BUFFER_BYTES

    private fun createTempFile(): File = File.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX, directory)

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
        if (contentKey != NO_CONTENT_KEY && data.size <= MAX_REMEMBERED_TILE_BYTES) contentTable.put(contentKey, offset)
        append(tileId, offset, data.size)
    }

    /** Aggiunge una tile con un contenuto gia' scritto con la stessa [contentKey]; false se la chiave non c'e' (piu'). */
    fun addKnown(tileId: Long, contentKey: Long, length: Int): Boolean {
        check(!dataFinished) { "Dati delle tile gia' chiusi" }
        val offset = contentTable.get(contentKey)
        if (offset < 0) return false
        append(tileId, offset, length)
        return true
    }

    /** Estende l'ultima voce se la tile la continua con lo stesso contenuto, altrimenti la scrive su disco e ne apre una. */
    private fun append(tileId: Long, offset: Long, length: Int) {
        tileCount++
        if (hasLast) {
            val end = lastId + lastRunLength
            if (tileId < end) {
                inTileIdOrder = false
            } else if (tileId == end && lastOffset == offset && lastLength == length) {
                lastRunLength++
                return
            }
            writeLast()
        }
        hasLast = true
        lastId = tileId
        lastRunLength = 1L
        lastOffset = offset
        lastLength = length
    }

    private fun writeLast() {
        writeRecord(requireNotNull(indexOut), lastId, lastRunLength, lastOffset, lastLength)
    }

    /**
     * Chiude la scrittura dei dati e della directory, ne ordina le voci se servono, e restituisce la lunghezza dei dati in byte.
     * [checkActive] e' chiamato a intervalli regolari durante l'ordinamento e deve lanciare un'eccezione per interromperlo.
     */
    internal fun finishData(checkActive: () -> Unit = {}): Long {
        if (!dataFinished) {
            out.close()
            if (hasLast) writeLast()
            hasLast = false
            indexOut?.close()
            indexOut = null
            if (!inTileIdOrder) sortIndex(checkActive)
            directoryEntryCount = indexFile.length() / RECORD_BYTES
            dataFinished = true
        }
        return dataLength
    }

    internal val dataFile: File get() = tempFile

    /** Passa a [sink], in ordine di tileId (come richiesto dallo spec), le voci della directory; tile con id
     *  consecutivi e stesso contenuto sono gia' una sola voce con runLength > 1. Si puo' ripetere. */
    internal fun forEachDirectoryEntry(checkActive: () -> Unit = {}, sink: DirectoryEntrySink) {
        check(dataFinished) { "Dati delle tile ancora aperti" }
        DataInputStream(BufferedInputStream(FileInputStream(indexFile), FILE_BUFFER_BYTES)).use { input ->
            for (i in 0 until directoryEntryCount) {
                if (i % CHECK_EVERY_RECORDS == 0L) checkActive()
                sink.accept(input.readLong(), input.readLong(), input.readLong(), input.readInt())
            }
        }
    }

    /**
     * Ordina il file delle voci per tileId con un merge esterno: blocchi di [sortChunkRecords] voci ordinati in
     * memoria e scritti uno dopo l'altro su un file temporaneo, poi fusi in un file nuovo. Voci vicine dopo
     * l'ordinamento che si continuano si uniscono; voci sovrapposte sono tileId duplicati.
     */
    private fun sortIndex(checkActive: () -> Unit) {
        val runsFile = createTempFile()
        val sortedFile = createTempFile()
        var sorted = false
        try {
            val runSizes = writeSortedRuns(runsFile, checkActive)
            mergeRuns(runsFile, runSizes, sortedFile, checkActive)
            sorted = true
        } finally {
            runsFile.delete()
            if (!sorted) sortedFile.delete()
        }
        indexFile.delete()
        indexFile = sortedFile
    }

    /** Divide il file delle voci in blocchi ordinati, scritti in [runsFile]; restituisce le voci di ogni blocco. */
    private fun writeSortedRuns(runsFile: File, checkActive: () -> Unit): List<Int> {
        val total = indexFile.length() / RECORD_BYTES
        val capacity = minOf(sortChunkRecords.toLong(), total).toInt()
        val ids = LongArray(capacity)
        val runLengths = LongArray(capacity)
        val offsets = LongArray(capacity)
        val lengths = IntArray(capacity)
        val sizes = mutableListOf<Int>()
        DataInputStream(BufferedInputStream(FileInputStream(indexFile), FILE_BUFFER_BYTES)).use { input ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(runsFile), FILE_BUFFER_BYTES)).use { runs ->
                var remaining = total
                while (remaining > 0) {
                    checkActive()
                    val n = minOf(capacity.toLong(), remaining).toInt()
                    for (i in 0 until n) {
                        ids[i] = input.readLong()
                        runLengths[i] = input.readLong()
                        offsets[i] = input.readLong()
                        lengths[i] = input.readInt()
                    }
                    // Ordinamento con soli array primitivi (niente indici boxed): ogni id e' unico, quindi
                    // la sua posizione finale si trova con una ricerca binaria sugli id ordinati.
                    val sortedIds = ids.copyOf(n).also { it.sort() }
                    for (i in 1 until n) require(sortedIds[i] != sortedIds[i - 1]) { "tileId duplicati" }
                    val source = IntArray(n)
                    for (i in 0 until n) source[sortedIds.binarySearch(ids[i])] = i
                    for (position in 0 until n) {
                        val i = source[position]
                        writeRecord(runs, ids[i], runLengths[i], offsets[i], lengths[i])
                    }
                    sizes += n
                    remaining -= n
                }
            }
        }
        return sizes
    }

    private fun mergeRuns(runsFile: File, runSizes: List<Int>, sortedFile: File, checkActive: () -> Unit) {
        RandomAccessFile(runsFile, "r").use { source ->
            val queue = PriorityQueue<SortedRun>(runSizes.size.coerceAtLeast(1), compareBy { it.tileId })
            var start = 0L
            for (size in runSizes) {
                val run = SortedRun(source.channel, start, size.toLong())
                if (run.advance()) queue.add(run)
                start += size
            }
            DataOutputStream(BufferedOutputStream(FileOutputStream(sortedFile), FILE_BUFFER_BYTES)).use { merged ->
                val coalescer = RunCoalescer(merged)
                var mergedCount = 0L
                while (true) {
                    val run = queue.poll() ?: break
                    if (mergedCount++ % CHECK_EVERY_RECORDS == 0L) checkActive()
                    coalescer.add(run.tileId, run.runLength, run.offset, run.length)
                    if (run.advance()) queue.add(run)
                }
                coalescer.finish()
            }
        }
    }

    override fun close() {
        try {
            try {
                out.close()
            } finally {
                // Anche con il disco pieno: lo spazio di un file cancellato si libera solo chiudendolo.
                indexOut?.close()
            }
        } finally {
            tempFile.delete()
            indexFile.delete()
        }
    }

    /** Scrive le voci in ordine di tileId unendo quelle che si continuano; una voce che ne copre un'altra e' un tileId duplicato. */
    private class RunCoalescer(private val output: DataOutputStream) {
        private var hasPending = false
        private var tileId = 0L
        private var runLength = 0L
        private var offset = 0L
        private var length = 0

        fun add(nextId: Long, nextRunLength: Long, nextOffset: Long, nextLength: Int) {
            if (hasPending) {
                val end = tileId + runLength
                require(nextId >= end) { "tileId duplicati" }
                if (nextId == end && nextOffset == offset && nextLength == length) {
                    runLength += nextRunLength
                    return
                }
                finish()
            }
            hasPending = true
            tileId = nextId
            runLength = nextRunLength
            offset = nextOffset
            this.length = nextLength
        }

        fun finish() {
            if (hasPending) writeRecord(output, tileId, runLength, offset, length)
            hasPending = false
        }
    }

    /** Un blocco ordinato del file dei blocchi, letto con un piccolo buffer proprio; i campi sono la voce corrente. */
    private class SortedRun(private val channel: FileChannel, firstRecord: Long, private var left: Long) {
        private val buffer = ByteBuffer.allocate(RUN_BUFFER_RECORDS * RECORD_BYTES).also { it.limit(0) }
        private var nextRecord = firstRecord
        var tileId = 0L
            private set
        var runLength = 0L
            private set
        var offset = 0L
            private set
        var length = 0
            private set

        fun advance(): Boolean {
            if (left == 0L) return false
            if (!buffer.hasRemaining()) refill()
            tileId = buffer.getLong()
            runLength = buffer.getLong()
            offset = buffer.getLong()
            length = buffer.getInt()
            left--
            return true
        }

        private fun refill() {
            val records = minOf(left, RUN_BUFFER_RECORDS.toLong()).toInt()
            buffer.clear()
            buffer.limit(records * RECORD_BYTES)
            var position = nextRecord * RECORD_BYTES
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                check(read >= 0) { "File dei blocchi troncato" }
                position += read
            }
            buffer.flip()
            nextRecord += records
        }
    }

    companion object {
        /** Contenuto senza chiave: scritto sempre, mai riusabile con [addKnown]. */
        const val NO_CONTENT_KEY = -1L
        private const val DEFAULT_SORT_CHUNK_RECORDS = 65_536
        private const val RUN_BUFFER_RECORDS = 512
        // Ogni quante voci di directory si controlla l'annullamento.
        private const val CHECK_EVERY_RECORDS = 4_096L
        private const val DATA_BUFFER_BYTES = 8 * 1024

        // I contenuti ripetuti delle build Protomaps (mare, terra vuota, poligoni a copertura piena) sono di
        // poche centinaia di byte; una tile piu' grande ripetuta e' rara, e riscriverla costa quanto lei.
        private const val MAX_REMEMBERED_TILE_BYTES = 4_096
    }
}

private fun writeRecord(output: DataOutputStream, tileId: Long, runLength: Long, offset: Long, length: Int) {
    output.writeLong(tileId)
    output.writeLong(runLength)
    output.writeLong(offset)
    output.writeInt(length)
}

/**
 * Tabella da chiave di contenuto (>= 0) a offset nello spool, di dimensione massima fissa: indirizzamento
 * aperto con sondaggio limitato, senza un oggetto per voce. Cresce raddoppiando fino al tetto; oltre,
 * una chiave nuova prende il posto di una voce mai riusata della sua finestra, o non entra se sono tutte
 * state riusate: i contenuti che si ripetono davvero (il mare) restano, quelli irripetibili passano.
 */
private class ContentTable {
    private var keys = LongArray(INITIAL_SLOTS) { EMPTY }
    private var values = LongArray(INITIAL_SLOTS)
    private var size = 0

    val retainedBytes: Long get() = keys.size * 2L * Long.SIZE_BYTES

    /** Il valore di [key], o -1 se manca; la voce ritrovata conta come riusata. */
    fun get(key: Long): Long {
        var slot = slotOf(key, keys.size)
        var result = -1L
        var probe = 0
        while (probe < PROBES && keys[slot] != EMPTY) {
            if (keys[slot] == key) {
                values[slot] = values[slot] or REUSED
                result = values[slot] and REUSED.inv()
                break
            }
            slot = (slot + 1) and (keys.size - 1)
            probe++
        }
        return result
    }

    fun put(key: Long, value: Long) {
        require(key >= 0) { "Chiave negativa" }
        if (size * 2 > keys.size && keys.size < MAX_SLOTS) grow()
        insert(key, value)
    }

    private fun insert(key: Long, value: Long) {
        var slot = slotOf(key, keys.size)
        var victim = -1
        repeat(PROBES) {
            val stored = keys[slot]
            if (stored == EMPTY) {
                keys[slot] = key
                values[slot] = value
                size++
                return
            }
            if (stored == key) {
                values[slot] = value
                return
            }
            if (victim < 0 && values[slot] and REUSED == 0L) victim = slot
            slot = (slot + 1) and (keys.size - 1)
        }
        if (victim >= 0) {
            keys[victim] = key
            values[victim] = value
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = LongArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) {
            if (oldKeys[i] != EMPTY) insert(oldKeys[i], oldValues[i])
        }
    }

    // Moltiplicazione per la costante aurea a 64 bit: gli offset sono molto regolari e senza mescolarli si ammassano.
    private fun slotOf(key: Long, slots: Int): Int = ((key * GOLDEN_RATIO) ushr HASH_SHIFT).toInt() and (slots - 1)

    private companion object {
        const val EMPTY = -1L
        const val INITIAL_SLOTS = 1_024
        const val MAX_SLOTS = 1 shl 17 // 2 MiB tra chiavi e valori
        const val PROBES = 16
        const val REUSED = 1L shl 62 // gli offset dello spool restano sotto 2^62
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
    private const val COPY_BUFFER_BYTES = 64 * 1024

    // Fino a qui la directory prova a stare tutta nella root; oltre si passa subito alle leaf.
    private const val MAX_ROOT_ONLY_ENTRIES = 20_000L

    // Leaf attese per directory enormi: la root (un puntatore per leaf) resta sotto il suo limite al primo colpo.
    private const val TARGET_LEAVES = 1_000L

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
     * memoria). Il file temporaneo lo cancella chi possiede [tiles], chiudendolo. [checkActive] e' chiamato a
     * intervalli regolari (ordinamento, directory, copia dei dati) e deve lanciare un'eccezione per interrompere la scrittura.
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
        checkActive: () -> Unit = {},
    ) {
        require(tiles.tileCount > 0) { "Nessuna tile da scrivere" }
        val tileDataLength = tiles.finishData(checkActive)
        val leafFile = File.createTempFile(SPOOL_PREFIX, SPOOL_SUFFIX, tiles.dataFile.absoluteFile.parentFile)
        try {
            val directory = buildDirectory(tiles, leafFile, checkActive)
            writeArchive(outputFile, tiles, directory, leafFile, tileDataLength, metadataJson, tileCompression, tileType, minZoom, maxZoom, minLon, minLat, maxLon, maxLat, checkActive)
        } finally {
            leafFile.delete()
        }
    }

    private fun writeArchive(
        outputFile: File,
        tiles: PmtilesTileSpool,
        directory: Directory,
        leafFile: File,
        tileDataLength: Long,
        metadataJson: String,
        tileCompression: Byte,
        tileType: Byte,
        minZoom: Int,
        maxZoom: Int,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        checkActive: () -> Unit,
    ) {
        val rootDirCompressed = directory.root
        val metadataCompressed = gzip(metadataJson.toByteArray(Charsets.UTF_8))

        val rootDirOffset = HEADER_LENGTH.toLong()
        val metadataOffset = rootDirOffset + rootDirCompressed.size
        val leafDirOffset = metadataOffset + metadataCompressed.size
        val leafDirLength = directory.leafLength
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
            tileEntries = tiles.directoryEntryCount,
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

        var complete = false
        try {
            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                out.write(header)
                out.write(rootDirCompressed)
                out.write(metadataCompressed)
                leafFile.inputStream().use { copyChecked(it, out, checkActive) }
                tiles.dataFile.inputStream().use { copyChecked(it, out, checkActive) }
            }
            complete = true
        } finally {
            // Un archivio troncato non deve restare accanto a quelli buoni.
            if (!complete) outputFile.delete()
        }
    }

    private fun copyChecked(input: InputStream, output: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    /** Root directory gia' compressa e lunghezza delle leaf directory scritte su un file a parte. */
    private class Directory(val root: ByteArray, val leafLength: Long)

    /** Voci di directory in attesa di essere codificate: al massimo un blocco, mai la directory intera. */
    private class EntryChunk(capacity: Int) {
        private val ids = LongArray(capacity)
        private val runLengths = LongArray(capacity)
        private val lengths = LongArray(capacity)
        private val offsets = LongArray(capacity)
        var count = 0
            private set
        val isFull: Boolean get() = count == ids.size
        val firstId: Long get() = ids[0]

        fun add(tileId: Long, runLength: Long, offset: Long, length: Int) {
            ids[count] = tileId
            runLengths[count] = runLength
            lengths[count] = length.toLong()
            offsets[count] = offset
            count++
        }

        fun clear() {
            count = 0
        }

        fun encode(): ByteArray = encodeDirectory(ids.copyOf(count), runLengths.copyOf(count), lengths.copyOf(count), offsets.copyOf(count))
    }

    /**
     * Costruisce la directory leggendo le voci di [tiles] in streaming: con poche voci basta la root, altrimenti
     * le leaf directory vanno su [leafFile] man mano che si riempiono e in memoria restano solo le voci della root.
     */
    private fun buildDirectory(tiles: PmtilesTileSpool, leafFile: File, checkActive: () -> Unit): Directory {
        val entryCount = tiles.directoryEntryCount
        if (entryCount <= MAX_ROOT_ONLY_ENTRIES) {
            val chunk = EntryChunk(entryCount.toInt())
            tiles.forEachDirectoryEntry(checkActive) { id, runLength, offset, length -> chunk.add(id, runLength, offset, length) }
            val rootOnly = gzip(chunk.encode())
            if (rootOnly.size <= MAX_ROOT_DIR_BYTES) return Directory(rootOnly, 0L)
        }
        var chunkSize = maxOf(INITIAL_LEAF_CHUNK_SIZE.toLong(), (entryCount + TARGET_LEAVES - 1) / TARGET_LEAVES)
        while (true) {
            val built = writeLeaves(tiles, leafFile, chunkSize.toInt(), checkActive)
            if (built.root.size <= MAX_ROOT_DIR_BYTES) return built
            check(chunkSize < entryCount) { "Impossibile produrre una root directory valida (${built.root.size} byte)" }
            chunkSize *= 2
        }
    }

    /** Divide le entry in blocchi da leafChunkSize, ciascuno una leaf directory a parte;
     *  la root diventa una directory di soli puntatori (runLength=0) a quelle leaf. */
    private fun writeLeaves(tiles: PmtilesTileSpool, leafFile: File, leafChunkSize: Int, checkActive: () -> Unit): Directory {
        val rootIds = mutableListOf<Long>()
        val rootOffsets = mutableListOf<Long>()
        val rootLengths = mutableListOf<Long>()
        var cursor = 0L
        val chunk = EntryChunk(leafChunkSize)
        BufferedOutputStream(FileOutputStream(leafFile)).use { out ->
            fun flushLeaf() {
                if (chunk.count == 0) return
                val leafBytes = gzip(chunk.encode())
                out.write(leafBytes)
                rootIds += chunk.firstId
                rootOffsets += cursor
                rootLengths += leafBytes.size.toLong()
                cursor += leafBytes.size
                chunk.clear()
            }
            tiles.forEachDirectoryEntry(checkActive) { id, runLength, offset, length ->
                chunk.add(id, runLength, offset, length)
                if (chunk.isFull) flushLeaf()
            }
            flushLeaf()
        }
        val root = encodeDirectory(rootIds.toLongArray(), LongArray(rootIds.size), rootLengths.toLongArray(), rootOffsets.toLongArray())
        return Directory(gzip(root), cursor)
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
