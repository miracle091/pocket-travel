package com.pockettravel.core.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Unisce i segmenti BRouter (.rd5) di piu' regioni per la navigazione tra regioni vicine.
 *
 * I .rd5 installati sono ritagliati sul riquadro della regione (tools/data-pipeline/scripts/clip_rd5.py):
 * due regioni confinanti hanno ritagli diversi della stessa tile (es. `E10_N40.rd5` di Italia e di San
 * Marino) e BRouter, che legge le tile per nome da una cartella, non puo' usarle insieme. Il formato pero'
 * lo permette: 200 byte di indice (25 long big-endian, uno per sotto-tile di 1x1 grado: 16 bit alti =
 * versione dei lookup, 48 bassi = fine del blocco), i 25 blocchi, poi in coda creationTime (long), CRC
 * dell'indice (int, xor 2 con divisor 32), 25 CRC degli indici dei blocchi (int), tipo di elevazione ed
 * eventuali estensioni. Ogni blocco inizia con un indice di divisor x divisor int (fine di ogni
 * micro-cella, relativa all'inizio del blocco) seguito dalle micro-celle, ognuna col suo CRC in coda,
 * quindi spostabili senza ricalcolo: si prende per ogni micro-cella quella non vuota e si riscrivono
 * indici e CRC.
 *
 * Con la stessa micro-cella in piu' file (ritagli di regioni sovrapposte) vince quella del file con
 * creationTime piu' recente: micro-celle di build diverse sono coerenti al loro interno, ma non fra loro.
 */
object Rd5Merger {
    const val EXTENSION = "rd5"

    /** File di BRouter nella cartella dei segmenti: `secondary_segment_dir=` dice dove cercare le tile che mancano. */
    const val STORAGE_CONFIG_FILE = "storageconfig.txt"

    private const val HEADER_SIZE = 200
    private const val SUB_TILES = 25
    // creationTime (8) + CRC dell'indice (4) + 25 CRC dei blocchi (100): il resto della coda si copia com'e'.
    private const val FOOTER_FIXED_SIZE = 8 + 4 + SUB_TILES * 4
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val KEY_LENGTH = 16
    private val MERGE_LOCK = Any()

    /**
     * Cartella con i segmenti uniti delle cartelle di percorsi [routingDirs] (`regions/<id>/routing`),
     * dentro [cacheRoot] col nome dato dai file di ogni regione (nome, dimensione, data): si rifa' solo
     * quando ne cambia uno, e le cartelle di combinazioni precedenti si cancellano.
     *
     * Per non copiare regioni intere (Italia: centinaia di MB) la regione con piu' dati resta dov'e' e
     * BRouter la trova come cartella secondaria (`secondary_segment_dir` in [STORAGE_CONFIG_FILE]),
     * cercata solo per le tile che mancano nella cartella unita: qui ci sono le tile presenti in piu'
     * regioni (unite) e quelle presenti solo nelle altre (copiate). IOException se un segmento non e' nel
     * formato atteso.
     */
    fun mergedDirectory(cacheRoot: File, routingDirs: List<File>): File = synchronized(MERGE_LOCK) {
        val tiles = routingDirs.flatMap { dir ->
            dir.listFiles { file -> file.isFile && file.extension == EXTENSION }.orEmpty().map { dir to it }
        }
        val key = cacheKey(tiles)
        val target = File(cacheRoot, key)
        // Riusata solo se completa: la cache puo' perdere file quando il sistema libera spazio.
        if (File(target, STORAGE_CONFIG_FILE).isFile) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        // Prima di costruire: si libera lo spazio delle combinazioni precedenti e delle unioni interrotte,
        // tranne l'ultima usata, che un calcolo ancora in corso potrebbe leggere.
        val previous = cacheRoot.listFiles().orEmpty().filter { it.isDirectory && !it.name.endsWith(".tmp") && it != target }
            .maxByOrNull { it.lastModified() }
        cacheRoot.listFiles().orEmpty().filter { it != previous }.forEach { it.deleteRecursively() }
        val staging = File(cacheRoot, "$key.tmp")
        staging.mkdirs()
        try {
            val secondary = routingDirs.maxBy { dir -> tiles.filter { it.first == dir }.sumOf { it.second.length() } }
            tiles.groupBy({ it.second.name }, { it.first to it.second }).forEach { (name, owners) ->
                if (owners.all { it.first == secondary }) return@forEach
                merge(owners.map { it.second }, File(staging, name))
            }
            // Percorso relativo alla cartella unita: BRouter lo risolve da li' (uno assoluto solo se comincia con "/").
            val secondaryPath = target.absoluteFile.toPath().relativize(secondary.absoluteFile.toPath())
            File(staging, STORAGE_CONFIG_FILE).writeText("secondary_segment_dir=$secondaryPath\n")
            if (!staging.renameTo(target)) throw IOException("Impossibile creare ${target.path}")
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
        target
    }

    private fun cacheKey(tiles: List<Pair<File, File>>): String {
        val description = tiles
            .map { (dir, file) -> "${dir.parentFile?.name}/${file.name}:${file.length()}:${file.lastModified()}" }
            .sorted()
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(description.toByteArray()).joinToString("") { "%02x".format(it) }.take(KEY_LENGTH)
    }

    /** Unisce i ritagli [sources] della stessa tile in [target] (vedi la descrizione dell'oggetto). */
    fun merge(sources: List<File>, target: File) {
        require(sources.isNotEmpty()) { "Nessun segmento da unire" }
        if (sources.size == 1) {
            sources.single().copyTo(target, overwrite = true)
            return
        }
        val files = mutableListOf<Rd5>()
        try {
            sources.forEach { files += Rd5(it) }
            write(files, target)
        } finally {
            files.forEach { it.close() }
        }
    }

    private fun write(files: List<Rd5>, target: File) {
        val divisor = files.first().divisor
        if (files.any { it.divisor != divisor }) throw IOException("Segmenti con divisori diversi: ${files.joinToString { it.file.path }}")
        // Piu' recente per primo (a parita' l'ordine dato): e' quello che vince e da cui si copia la coda.
        val newest = files.sortedByDescending { it.creationTime }
        val indexSize = divisor * divisor * 4

        val versions = LongArray(SUB_TILES)
        val indexes = arrayOfNulls<ByteArray>(SUB_TILES)
        val segments = List(SUB_TILES) { mutableListOf<Segment>() }
        val blockSizes = LongArray(SUB_TILES)
        for (sub in 0 until SUB_TILES) {
            val holders = newest.filter { it.hasBlock(sub) }
            if (holders.isEmpty()) {
                versions[sub] = newest.first().versions[sub]
                continue
            }
            // La versione dei lookup e' del blocco intero: micro-celle di un'altra versione non si mescolano.
            versions[sub] = holders.first().versions[sub]
            val usable = holders.filter { it.versions[sub] == versions[sub] }
            val cellEnds = usable.map { it.cellEnds(sub) }
            val newIndex = ByteBuffer.allocate(indexSize)
            var size = indexSize
            for (cell in 0 until divisor * divisor) {
                val owner = usable.indices.firstOrNull { cellEnds[it].size(cell, indexSize) > 0 }
                if (owner != null) {
                    val start = cellEnds[owner].start(cell, indexSize)
                    val end = cellEnds[owner].end[cell]
                    val from = usable[owner].blockStart(sub) + start
                    val last = segments[sub].lastOrNull()
                    if (last != null && last.file === usable[owner] && last.end == from) last.end = from + (end - start)
                    else segments[sub] += Segment(usable[owner], from, from + (end - start))
                    size += end - start
                }
                newIndex.putInt(size)
            }
            indexes[sub] = newIndex.array()
            blockSizes[sub] = size.toLong()
        }
        // Un blocco senza nessuna micro-cella e' vuoto come in clip_rd5.py (lunghezza zero, CRC zero).
        for (sub in 0 until SUB_TILES) {
            if (segments[sub].isEmpty()) { indexes[sub] = null; blockSizes[sub] = 0 }
        }

        val header = ByteBuffer.allocate(HEADER_SIZE)
        var position = HEADER_SIZE.toLong()
        for (sub in 0 until SUB_TILES) {
            position += blockSizes[sub]
            header.putLong((versions[sub] shl 48) or position)
        }
        val headerBytes = header.array()
        val footer = ByteBuffer.allocate(FOOTER_FIXED_SIZE)
        footer.putLong(newest.first().creationTime)
        footer.putInt(crc(headerBytes) xor (if (divisor == 32) 2 else 0))
        for (sub in 0 until SUB_TILES) footer.putInt(indexes[sub]?.let(::crc) ?: 0)

        target.outputStream().buffered().use { out ->
            out.write(headerBytes)
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            for (sub in 0 until SUB_TILES) {
                indexes[sub]?.let(out::write)
                for (segment in segments[sub]) segment.file.copyTo(segment.start, segment.end, out, buffer)
            }
            out.write(footer.array())
            out.write(newest.first().tail)
        }
    }

    /** Tratto contiguo di [file] (byte [start] .. [end]) da copiare nel file unito. */
    private class Segment(val file: Rd5, val start: Long, var end: Long)

    /** Fine di ogni micro-cella di un blocco, relativa all'inizio del blocco. */
    private class CellEnds(val end: IntArray) {
        fun start(cell: Int, indexSize: Int): Int = if (cell == 0) indexSize else end[cell - 1]
        fun size(cell: Int, indexSize: Int): Int = end[cell] - start(cell, indexSize)
    }

    /** Un .rd5 aperto in lettura: solo indice e coda in memoria, i dati si leggono a richiesta. */
    private class Rd5(val file: File) {
        private val raf = RandomAccessFile(file, "r")
        val divisor: Int
        val creationTime: Long
        val tail: ByteArray
        val versions = LongArray(SUB_TILES)
        private val ends = LongArray(SUB_TILES)

        init {
            try {
                if (raf.length() < HEADER_SIZE) throw IOException("${file.name}: file troncato")
                val header = ByteArray(HEADER_SIZE).also { raf.readFully(it) }
                val index = ByteBuffer.wrap(header)
                for (sub in 0 until SUB_TILES) {
                    val value = index.getLong()
                    versions[sub] = value ushr 48
                    ends[sub] = value and 0xFFFFFFFFFFFFL
                }
                val footerStart = ends[SUB_TILES - 1]
                if (raf.length() < footerStart + FOOTER_FIXED_SIZE) throw IOException("${file.name}: manca la coda con i CRC")
                val footer = ByteArray((raf.length() - footerStart).toInt()).also { raf.seek(footerStart); raf.readFully(it) }
                val buffer = ByteBuffer.wrap(footer)
                creationTime = buffer.getLong()
                val crcIndex = buffer.getInt()
                val actual = crc(header)
                divisor = when (crcIndex) {
                    actual -> 80
                    actual xor 2 -> 32
                    else -> throw IOException("${file.name}: CRC dell'indice non valido")
                }
                tail = footer.copyOfRange(FOOTER_FIXED_SIZE, footer.size)
            } catch (error: Exception) {
                raf.close()
                throw error
            }
        }

        fun blockStart(sub: Int): Long = if (sub == 0) HEADER_SIZE.toLong() else ends[sub - 1]

        fun hasBlock(sub: Int): Boolean = ends[sub] > blockStart(sub)

        fun cellEnds(sub: Int): CellEnds {
            val indexSize = divisor * divisor * 4
            if (ends[sub] - blockStart(sub) < indexSize) throw IOException("${file.name}: blocco $sub troncato")
            val bytes = ByteArray(indexSize).also { raf.seek(blockStart(sub)); raf.readFully(it) }
            val buffer = ByteBuffer.wrap(bytes)
            return CellEnds(IntArray(divisor * divisor) { buffer.getInt() })
        }

        fun copyTo(start: Long, end: Long, out: java.io.OutputStream, buffer: ByteArray) {
            raf.seek(start)
            var left = end - start
            while (left > 0) {
                val count = raf.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (count < 0) throw IOException("${file.name}: fine del file inattesa")
                out.write(buffer, 0, count)
                left -= count
            }
        }

        fun close() = raf.close()
    }

    // btools.util.Crc32: CRC-32 standard senza lo xor finale.
    private fun crc(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt() xor -1
}
