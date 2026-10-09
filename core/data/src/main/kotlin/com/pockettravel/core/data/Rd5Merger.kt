package com.pockettravel.core.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.abs

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
 * Con la stessa micro-cella in piu' file (ritagli di regioni sovrapposte) vince quella della regione
 * piu' recente (creationTime), tranne vicino ai bordi fra regioni (vedi [Plan.moveSeams]).
 */
object Rd5Merger {
    const val EXTENSION = "rd5"

    /** File di BRouter nella cartella dei segmenti: `secondary_segment_dir=` dice dove cercare le tile che mancano. */
    const val STORAGE_CONFIG_FILE = "storageconfig.txt"

    /**
     * Le micro-celle raggiunte dalle strade che partono dalla micro-cella ([lonIdx], [latIdx]; [bytes] nel
     * formato di BRouter), come [cellKey]. Le legge chi conosce il formato (BRouter, in feature:map).
     */
    fun interface Links {
        fun targets(lonIdx: Int, latIdx: Int, divisor: Int, bytes: ByteArray): Collection<Long>
    }

    /**
     * Chiave di una micro-cella: indici di longitudine e latitudine contati da -180/-90 gradi in micro-celle
     * (in BRouter `ilon / (1_000_000 / divisor)`, con ilon = (lon + 180) * 1_000_000).
     */
    fun cellKey(lonIdx: Int, latIdx: Int): Long = (lonIdx.toLong() shl Int.SIZE_BITS) or latIdx.toLong()

    private const val HEADER_SIZE = 200
    private const val SUB_TILES = 25
    // Sotto-tile per lato (5 x 5 gradi): indice = lon % 5 * 5 + lat % 5.
    private const val SUB_TILES_PER_SIDE = 5
    // creationTime (8) + CRC dell'indice (4) + 25 CRC dei blocchi (100): il resto della coda si copia com'e'.
    private const val FOOTER_FIXED_SIZE = 8 + 4 + SUB_TILES * 4
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val KEY_LENGTH = 16
    // Cambia quando cambia il modo di unire: le cartelle unite prima si rifanno.
    private const val MERGE_VERSION = 2
    // Indice della tile: 16 bit alti = versione dei lookup. Con divisor 32 il CRC dell'indice ha xor 2.
    private const val VERSION_SHIFT = 48
    private const val DIVISOR_32 = 32
    private const val LON_OFFSET = 180
    private const val LAT_OFFSET = 90
    private val TILE_NAME = Regex("([EW])(\\d+)_([NS])(\\d+)\\.rd5")
    private val MERGE_LOCK = Any()

    /**
     * Cartella con i segmenti uniti delle cartelle della rete stradale [routingDirs] (`regions/<id>/routing`),
     * dentro [cacheRoot] col nome dato dai file di ogni regione (nome, dimensione, data): si rifa' solo
     * quando ne cambia uno, e le cartelle di combinazioni precedenti si cancellano.
     *
     * Per non copiare regioni intere (Italia: centinaia di MB) la regione con piu' dati resta dov'e' e
     * BRouter la trova come cartella secondaria (`secondary_segment_dir` in [STORAGE_CONFIG_FILE]),
     * cercata solo per le tile che mancano nella cartella unita: qui ci sono le tile presenti in piu'
     * regioni (unite) e quelle presenti solo nelle altre (copiate). [links] legge le strade fra micro-celle
     * (vedi [Plan.moveSeams]). IOException se un segmento non e' nel formato atteso.
     */
    fun mergedDirectory(cacheRoot: File, routingDirs: List<File>, links: Links): File = synchronized(MERGE_LOCK) {
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
            Plan(planRegions(routingDirs, tiles, secondary), links).use { plan ->
                tiles.groupBy({ it.second.name }, { it.first to it.second }).forEach { (name, owners) ->
                    when {
                        owners.all { it.first == secondary } -> Unit
                        owners.size == 1 -> owners.single().second.copyTo(File(staging, name), overwrite = true)
                        else -> plan.write(name, File(staging, name))
                    }
                }
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

    /**
     * Le tile di ogni regione (nome -> file) da mettere nel piano. Della regione con piu' dati ([secondary])
     * solo quelle accanto alle tile delle altre (stessa tile o vicina): i bordi stanno li', e l'Italia ha
     * decine di tile lontane da San Marino.
     */
    private fun planRegions(routingDirs: List<File>, tiles: List<Pair<File, File>>, secondary: File): List<Map<String, File>> {
        val others = tiles.filter { it.first != secondary }.map { tileOrigin(it.second.name) }
        fun nearOthers(file: File): Boolean = tileOrigin(file.name).let { (lon, lat) ->
            others.any { (otherLon, otherLat) -> abs(otherLon - lon) <= SUB_TILES_PER_SIDE && abs(otherLat - lat) <= SUB_TILES_PER_SIDE }
        }
        return routingDirs.map { dir ->
            tiles.filter { (owner, file) -> owner == dir && (dir != secondary || nearOthers(file)) }.associate { it.second.name to it.second }
        }
    }

    private fun cacheKey(tiles: List<Pair<File, File>>): String {
        val description = tiles
            .map { (dir, file) -> "${dir.parentFile?.name}/${file.name}:${file.length()}:${file.lastModified()}" }
            .sorted()
            .joinToString("\n", prefix = "v$MERGE_VERSION\n")
        return MessageDigest.getInstance("SHA-256").digest(description.toByteArray()).joinToString("") { "%02x".format(it) }.take(KEY_LENGTH)
    }

    /**
     * Unisce i ritagli [sources] della stessa tile in [target], che deve avere il nome della tile
     * (es. `E10_N45.rd5`): ogni ritaglio conta come una regione (vedi la descrizione dell'oggetto).
     */
    fun merge(sources: List<File>, target: File, links: Links) {
        require(sources.isNotEmpty()) { "Nessun segmento da unire" }
        if (sources.size == 1) {
            sources.single().copyTo(target, overwrite = true)
            return
        }
        Plan(sources.map { mapOf(target.name to it) }, links).use { it.write(target.name, target) }
    }

    /** Una tile aperta in ogni regione che ce l'ha ([files], indice = regione); micro-celle per indice sub * divisor^2 + cella. */
    private class Tile(val lonIdx0: Int, val latIdx0: Int, val divisor: Int, val files: Array<Rd5?>) {
        val cells = divisor * divisor
        val indexSize = cells * Int.SIZE_BYTES
        val versions = LongArray(SUB_TILES)
        // Per sotto-tile e regione: fine delle micro-celle, null se la regione non ha il blocco con la versione scelta.
        val cellEnds = Array(SUB_TILES) { arrayOfNulls<CellEnds>(files.size) }

        fun has(index: Int, region: Int): Boolean =
            cellEnds[index / cells][region]?.let { it.size(index % cells, indexSize) > 0 } == true

        fun bytes(index: Int, region: Int): ByteArray {
            val sub = index / cells
            val ends = checkNotNull(cellEnds[sub][region]) { "micro-cella senza file" }
            val start = ends.start(index % cells, indexSize)
            val file = checkNotNull(files[region])
            return file.read(file.blockStart(sub) + start, ends.end[index % cells] - start)
        }

        fun key(index: Int): Long {
            val sub = index / cells
            val cell = index % cells
            return cellKey(lonIdx0 + sub / SUB_TILES_PER_SIDE * divisor + cell % divisor, latIdx0 + sub % SUB_TILES_PER_SIDE * divisor + cell / divisor)
        }
    }

    /**
     * Da quale regione prendere ogni micro-cella delle tile di [regions] (per regione: nome della tile -> file),
     * su un'unica griglia, cosi' i bordi si controllano anche fra una tile e l'altra.
     */
    private class Plan(regions: List<Map<String, File>>, private val links: Links) : Closeable {
        private val opened = mutableListOf<Rd5>()
        private val tiles = HashMap<Long, Tile>()
        // Posizione di ogni regione per recenza: 0 = la piu' recente (a parita' l'ordine dato).
        private val rank = IntArray(regions.size)
        // Regione da cui si prende ogni micro-cella non vuota.
        private val owner = HashMap<Long, Int>()
        // Micro-celle collegate da una strada, nei due versi.
        private val linked = HashMap<Long, MutableSet<Long>>()
        private val sameCache = HashMap<Triple<Long, Int, Int>, Boolean>()

        init {
            var ready = false
            try {
                regions.forEachIndexed { region, byName ->
                    byName.forEach { (name, file) ->
                        val rd5 = Rd5(file).also { opened += it }
                        val (lon, lat) = tileOrigin(name)
                        val tile = tiles.getOrPut(cellKey(lon, lat)) { Tile(lon * rd5.divisor, lat * rd5.divisor, rd5.divisor, arrayOfNulls(regions.size)) }
                        tile.files[region] = rd5
                    }
                }
                // Una sola griglia: tutte le tile con lo stesso divisor (brouter.de usa 32).
                if (opened.map { it.divisor }.distinct().size > 1) throw IOException("Segmenti con divisori diversi: ${opened.joinToString { it.file.path }}")
                regions.indices.sortedByDescending { region -> tiles.values.mapNotNull { it.files[region]?.creationTime }.maxOrNull() ?: Long.MIN_VALUE }
                    .forEachIndexed { position, region -> rank[region] = position }
                tiles.values.forEach(::readIndexes)
                readLinks()
                moveSeams()
                ready = true
            } finally {
                if (!ready) close()
            }
        }

        private fun readIndexes(tile: Tile) {
            val byRank = tile.files.indices.filter { tile.files[it] != null }.sortedBy { rank[it] }
            for (sub in 0 until SUB_TILES) {
                val holders = byRank.filter { checkNotNull(tile.files[it]).hasBlock(sub) }
                // La versione dei lookup e' del blocco intero: micro-celle di un'altra versione non si mescolano.
                tile.versions[sub] = checkNotNull(tile.files[holders.firstOrNull() ?: byRank.first()]).versions[sub]
                for (region in holders) {
                    val file = checkNotNull(tile.files[region])
                    if (file.versions[sub] == tile.versions[sub]) tile.cellEnds[sub][region] = file.cellEnds(sub)
                }
                for (index in sub * tile.cells until (sub + 1) * tile.cells) {
                    holders.firstOrNull { tile.has(index, it) }?.let { owner[tile.key(index)] = it }
                }
            }
        }

        /**
         * Strade lunghe fra micro-celle (oltre quelle accanto, gia' controllate da [neighbours]), lette solo
         * nella fascia dove i ritagli si sovrappongono: ogni versione delle micro-celle presenti in almeno due
         * regioni. Basta: una strada da P (regione r) a Q (regione s) punta a un nodo di Q nella build di r;
         * se r non ha Q, la strada mancava gia' nel ritaglio di r; se ce l'ha, Q sta nella fascia e la sua
         * versione di r ha la strada di ritorno verso P. Cosi' non si leggono regioni intere (Italia:
         * centinaia di MB), solo la fascia lungo i confini.
         */
        private fun readLinks() {
            for (tile in tiles.values) {
                for (index in 0 until SUB_TILES * tile.cells) {
                    val holders = tile.files.indices.filter { tile.has(index, it) }
                    if (holders.size > 1) holders.forEach { region -> readLinks(tile, index, region) }
                }
            }
        }

        private fun readLinks(tile: Tile, index: Int, region: Int) {
            val from = tile.key(index)
            val lonIdx = (from shr Int.SIZE_BITS).toInt()
            val latIdx = from.toInt()
            val far = links.targets(lonIdx, latIdx, tile.divisor, tile.bytes(index, region)).filter { to ->
                abs((to shr Int.SIZE_BITS).toInt() - lonIdx) > 1 || abs(to.toInt() - latIdx) > 1
            }
            for (to in far.filter { it in owner }) {
                linked.getOrPut(from) { HashSet() } += to
                linked.getOrPut(to) { HashSet() } += from
            }
        }

        /**
         * Sposta i bordi fra micro-celle di regioni diverse dove le due build potrebbero non combaciare.
         * Le regioni si pubblicano in giorni diversi, quindi i loro .rd5 vengono da build di brouter.de
         * diverse: una strada che passa da una micro-cella all'altra punta a un nodo dell'altra, che
         * nell'altra build puo' non esserci piu' (strada modificata in OSM), e BRouter non la percorre.
         * Una micro-cella identica nelle due build combacia con entrambe: due micro-celle vicine (anche in
         * diagonale, anche in tile diverse) o unite da una strada ([linked]), prese da regioni diverse, vanno
         * bene se almeno una delle due e' identica nelle due regioni. Altrimenti quella della regione piu'
         * recente passa alla piu' vecchia, se ce l'ha (nella fascia dove i ritagli si sovrappongono), e si
         * ricontrollano le sue vicine: il bordo si sposta finche' trova micro-celle uguali o finisce la
         * sovrapposizione. Ogni passaggio va verso una regione piu' vecchia, quindi finisce.
         */
        private fun moveSeams() {
            val pending = ArrayDeque(owner.keys)
            while (pending.isNotEmpty()) {
                val key = pending.removeFirst()
                neighbours(key).asSequence().filter { mismatched(key, it) }.mapNotNullTo(pending) { moveNewer(key, it) }
            }
        }

        /** Le micro-celle attorno a [key], diagonali comprese, e quelle unite da una strada. */
        private fun neighbours(key: Long): List<Long> {
            val lonIdx = (key shr Int.SIZE_BITS).toInt()
            val latIdx = key.toInt()
            val around = (lonIdx - 1..lonIdx + 1).flatMap { x -> (latIdx - 1..latIdx + 1).map { y -> cellKey(x, y) } }
            return around.filter { it != key } + linked[key].orEmpty()
        }

        /** Le due micro-celle vengono da regioni diverse e nessuna delle due e' uguale nelle due regioni. */
        private fun mismatched(key: Long, other: Long): Boolean {
            val a = owner.getValue(key)
            val b = owner[other] ?: return false
            return a != b && !same(key, a, b) && !same(other, a, b)
        }

        private fun same(key: Long, a: Int, b: Int): Boolean = sameCache.getOrPut(Triple(key, minOf(a, b), maxOf(a, b))) {
            locate(tiles, key)?.let { (tile, index) -> tile.has(index, a) && tile.has(index, b) && tile.bytes(index, a).contentEquals(tile.bytes(index, b)) } == true
        }

        /** La micro-cella della regione piu' recente passa alla piu' vecchia, se ce l'ha: la sua chiave, o null. */
        private fun moveNewer(key: Long, other: Long): Long? {
            val a = owner.getValue(key)
            val b = owner.getValue(other)
            val newer = if (rank[a] < rank[b]) key else other
            val older = if (rank[a] < rank[b]) b else a
            return locate(tiles, newer)?.takeIf { (tile, index) -> tile.has(index, older) }?.let {
                owner[newer] = older
                newer
            }
        }

        /** Scrive in [target] la tile [name] con le micro-celle scelte. */
        fun write(name: String, target: File) {
            val (lon, lat) = tileOrigin(name)
            val tile = checkNotNull(tiles[cellKey(lon, lat)]) { "tile $name non aperta" }
            val indexes = arrayOfNulls<ByteArray>(SUB_TILES)
            val segments = List(SUB_TILES) { mutableListOf<Segment>() }
            for (sub in 0 until SUB_TILES) {
                val newIndex = ByteBuffer.allocate(tile.indexSize)
                var size = tile.indexSize
                for (cell in 0 until tile.cells) {
                    val region = owner[tile.key(sub * tile.cells + cell)]
                    if (region != null) {
                        val file = checkNotNull(tile.files[region])
                        val ends = checkNotNull(tile.cellEnds[sub][region]) { "micro-cella senza file" }
                        val start = ends.start(cell, tile.indexSize)
                        val from = file.blockStart(sub) + start
                        val last = segments[sub].lastOrNull()
                        if (last != null && last.file === file && last.end == from) last.end = from + (ends.end[cell] - start)
                        else segments[sub] += Segment(file, from, from + (ends.end[cell] - start))
                        size += ends.end[cell] - start
                    }
                    newIndex.putInt(size)
                }
                // Un blocco senza nessuna micro-cella e' vuoto come in clip_rd5.py (lunghezza zero, CRC zero).
                indexes[sub] = newIndex.array().takeIf { segments[sub].isNotEmpty() }
            }
            writeTile(target, tile, indexes, segments)
        }

        override fun close() = opened.forEach { it.close() }
    }

    /** La tile e l'indice (sub * divisor^2 + cella) della micro-cella [key], se la tile e' aperta. */
    private fun locate(tiles: Map<Long, Tile>, key: Long): Pair<Tile, Int>? {
        val lonIdx = (key shr Int.SIZE_BITS).toInt()
        val latIdx = key.toInt()
        val divisor = tiles.values.first().divisor
        val lonDeg = lonIdx / divisor
        val latDeg = latIdx / divisor
        val tile = tiles[cellKey(lonDeg - lonDeg % SUB_TILES_PER_SIDE, latDeg - latDeg % SUB_TILES_PER_SIDE)] ?: return null
        val sub = lonDeg % SUB_TILES_PER_SIDE * SUB_TILES_PER_SIDE + latDeg % SUB_TILES_PER_SIDE
        return tile to sub * tile.cells + latIdx % divisor * divisor + lonIdx % divisor
    }

    /**
     * Scrive la tile: indice delle sotto-tile ([indexes] = indice delle micro-celle di ogni blocco, null se vuoto),
     * i blocchi copiati da [segments], la coda con creationTime e coda del file piu' recente della tile.
     */
    private fun writeTile(target: File, tile: Tile, indexes: Array<ByteArray?>, segments: List<List<Segment>>) {
        val header = ByteBuffer.allocate(HEADER_SIZE)
        var position = HEADER_SIZE.toLong()
        for (sub in 0 until SUB_TILES) {
            // L'ultimo int dell'indice e' la fine dell'ultima micro-cella, cioe' la lunghezza del blocco.
            position += indexes[sub]?.let { ByteBuffer.wrap(it).getInt(it.size - Int.SIZE_BYTES) } ?: 0
            header.putLong((tile.versions[sub] shl VERSION_SHIFT) or position)
        }
        val headerBytes = header.array()
        val newest = tile.files.filterNotNull().maxBy { it.creationTime }
        val footer = ByteBuffer.allocate(FOOTER_FIXED_SIZE)
        footer.putLong(newest.creationTime)
        footer.putInt(crc(headerBytes) xor (if (tile.divisor == DIVISOR_32) 2 else 0))
        for (sub in 0 until SUB_TILES) footer.putInt(indexes[sub]?.let(::crc) ?: 0)

        target.outputStream().buffered().use { out ->
            out.write(headerBytes)
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            for (sub in 0 until SUB_TILES) {
                indexes[sub]?.let(out::write)
                for (segment in segments[sub]) segment.file.copyTo(segment.start, segment.end, out, buffer)
            }
            out.write(footer.array())
            out.write(newest.tail)
        }
    }

    /** Angolo in basso a sinistra della tile in gradi da -180/-90: `E10_N45.rd5` -> (190, 135). */
    private fun tileOrigin(name: String): Pair<Int, Int> {
        val groups = (TILE_NAME.matchEntire(name) ?: throw IOException("Nome di tile inatteso: $name")).groupValues
        val lon = groups[2].toInt() * (if (groups[1] == "E") 1 else -1) + LON_OFFSET
        val lat = groups[4].toInt() * (if (groups[3] == "N") 1 else -1) + LAT_OFFSET
        return lon to lat
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

        fun read(start: Long, size: Int): ByteArray = ByteArray(size).also { raf.seek(start); raf.readFully(it) }

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
