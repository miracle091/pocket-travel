package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.mergeAddressSearchOnDevice
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Tile dei civici, sempre allo stesso zoom (GenerateAddresses). */
private const val ADDRESS_TILE_ZOOM = 14

// Figli di ogni tile del quadtree: ogni livello della curva di Hilbert consuma due bit dell'indice.
private const val QUADTREE_CHILDREN = 4L

/**
 * Ricostruisce addresses.pmtiles di una regione a partire dalle sue celle:
 * solo le celle nuove o cambiate si scaricano (vedi [plan]), le altre si tengono dal file
 * gia' installato. Una tile z14 appartiene a una cella se ne e' discendente: le celle sparite dalla
 * regione (es. divise in figlie dalla pipeline) non vengono ricopiate, spariscono col resto del file
 * precedente. Scrive anche [RegionStorage.ADDRESSES_CELLS_FILE] in staging, pronto per
 * RegionStorage.activatePackage come [RegionStorage.ADDRESSES_FILE].
 *
 * Le tile si leggono camminando la directory di ogni file ([PmtilesDirectoryReader]), mai provando
 * le coordinate z14 discendenti di una cella una per una: il seme ha celle molto rade nelle zone a
 * bassa densita' (z3-z6), dove una cella copre fino a centinaia di milioni di
 * tile quasi tutte vuote — ch.poole.geo.pmtiles.Reader.getTile(z,x,y) (l'unica API della libreria di
 * lettura) richiederebbe altrettante chiamate. Una tile trovata appartiene alla cella cercata se le
 * sue coordinate x,y (Hilbert invertito, [indexToXY]) ricadono nel suo intervallo discendente.
 */
class RegionAddressGridInstaller @Inject constructor(
    private val regionStorage: RegionStorage,
) {
    /** Celle da scaricare (nuove o con version diversa) e celle invariate (da tenere dal file installato). */
    data class Plan(
        val allCells: List<AddressGridCell>,
        val toDownload: List<AddressGridCell>,
        val unchanged: List<AddressGridCell>,
        // Celle di cui scaricare anche il database di ricerca: quelle da scaricare e quelle invariate che non
        // ce l'hanno ancora su disco (installate prima della ricerca). Solo celle con AddressGridCell.search.
        val searchToDownload: List<AddressGridCell> = emptyList(),
    )

    fun plan(regionId: String, addressGrid: RegionAddressGridEntry): Plan {
        val installed = regionStorage.installedAddressCells(regionId)
        val (unchanged, toDownload) = addressGrid.cells.partition { installed[it.id] == it.version }
        val installedSearch = regionStorage.addressSearchFiles(regionId).map { it.name }.toSet()
        val searchToDownload = addressGrid.cells.filter {
            // Installata come archivio .db.xz o, prima della compressione, come .db: in entrambi i casi non si riscarica.
            it.search != null && (
                it in toDownload ||
                    (RegionStorage.addressSearchArchiveName(it.id) !in installedSearch && RegionStorage.addressSearchFileName(it.id) !in installedSearch)
                )
        }
        return Plan(addressGrid.cells, toDownload, unchanged, searchToDownload)
    }

    /**
     * [plan.toDownload] deve gia' essere scaricato e decompresso in [staging], con lo stesso nome del
     * manifest ([AddressGridCell.file].name) — RegionPackageInstaller se ne occupa insieme agli altri
     * pacchetti richiesti. Bloccante (letture di file): va chiamato fuori dal main thread.
     * [ensureActive] e' invocato piu' volte durante la copia delle tile e deve lanciare un'eccezione
     * per interrompere l'operazione (es. CoroutineScope.ensureActive del worker annullato).
     * [mergeSearch] unisce i database di ricerca per cella nel database unico della regione (e' un parametro
     * solo perche' i test JVM non hanno android.database, vedi [assembleSearchDir]).
     */
    fun mergeInto(
        regionId: String,
        mapSource: MapExtractionSource,
        plan: Plan,
        staging: File,
        mergeSearch: (cells: List<File>, target: File) -> Unit = ::mergeAddressSearchOnDevice,
        ensureActive: () -> Unit = {},
    ) {
        require(plan.allCells.isNotEmpty()) { "Nessuna cella dei civici per $regionId" }
        val installedAddresses = File(regionStorage.directoryFor(regionId), RegionStorage.ADDRESSES_FILE)
        var metadataJson: String? = null
        var tileCompression: Byte = Constants.COMPRESSION_GZIP
        var tileType: Byte = Constants.TYPE_MVT

        PmtilesTileSpool(staging).use { tiles ->
            plan.toDownload.forEach { cell ->
                val cellFile = File(staging, cell.file.name)
                val range = cellRange(cell)
                PmtilesDirectoryReader(cellFile).use { reader ->
                    if (metadataJson == null) {
                        metadataJson = reader.metadata
                        tileCompression = reader.tileCompression
                        tileType = reader.tileType
                    }
                    reader.forEachTile(ensureActive) { tileId, data ->
                        if (belongsTo(tileId, range)) tiles.add(tileId, data)
                    }
                }
                // le sue tile sono ormai nello spool: il file per-cella non serve piu'.
                cellFile.delete()
            }
            if (plan.unchanged.isNotEmpty()) {
                require(installedAddresses.isFile) { "addresses.pmtiles installato mancante per $regionId" }
                val ranges = plan.unchanged.map(::cellRange)
                PmtilesDirectoryReader(installedAddresses).use { reader ->
                    if (metadataJson == null) {
                        metadataJson = reader.metadata
                        tileCompression = reader.tileCompression
                        tileType = reader.tileType
                    }
                    reader.forEachTile(ensureActive) { tileId, data ->
                        if (ranges.any { belongsTo(tileId, it) }) tiles.add(tileId, data)
                    }
                }
            }
            PmtilesWriter.write(
                outputFile = File(staging, RegionStorage.ADDRESSES_FILE),
                tiles = tiles,
                metadataJson = metadataJson ?: "{}",
                tileCompression = tileCompression,
                tileType = tileType,
                minZoom = ADDRESS_TILE_ZOOM,
                maxZoom = ADDRESS_TILE_ZOOM,
                minLon = mapSource.minLon,
                minLat = mapSource.minLat,
                maxLon = mapSource.maxLon,
                maxLat = mapSource.maxLat,
            )
        }

        File(staging, RegionStorage.ADDRESSES_CELLS_FILE).writeText(
            RegionStorage.encodeAddressCells(plan.allCells.associate { it.id to it.version }),
        )
        assembleSearchDir(regionId, plan, staging, mergeSearch, ensureActive)
    }

    /**
     * Prepara [RegionStorage.ADDRESSES_SEARCH_DIR] in [staging], pronta da attivare: per ogni cella con
     * ricerca l'archivio `.db.xz` appena scaricato (in [staging] col nome del manifest, vedi
     * [Plan.searchToDownload]) o, per le celle invariate, la copia di quello gia' installato. Un database non
     * compresso (manifest senza xz, o installazione fatta prima della compressione) si comprime qui, cosi' le
     * celle occupano su disco solo la loro forma compressa. Le celle senza ricerca restano fuori:
     * la cartella puo' essere vuota (manifest senza ricerca) e sostituisce comunque quella precedente.
     * Gli archivi per cella (che servono solo a scaricare in seguito le sole celle cambiate) si affiancano a
     * [RegionStorage.ADDRESSES_SEARCH_DB], l'unione di tutte le celle con cui l'app cerca: costruita qui, prima
     * dell'attivazione, cosi' archivi e file unito cambiano insieme, con la stessa sostituzione della cartella.
     * Per l'unione le celle si decomprimono in una cartella temporanea di [staging], cancellata a fine lavoro.
     */
    private fun assembleSearchDir(
        regionId: String,
        plan: Plan,
        staging: File,
        mergeSearch: (List<File>, File) -> Unit,
        ensureActive: () -> Unit,
    ) {
        val dir = File(staging, RegionStorage.ADDRESSES_SEARCH_DIR)
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Impossibile creare ${RegionStorage.ADDRESSES_SEARCH_DIR}" }
        val installedDir = File(regionStorage.directoryFor(regionId), RegionStorage.ADDRESSES_SEARCH_DIR)
        // Archivio di ogni cella con il database atteso dal manifest: la decompressione si ferma oltre la sua dimensione
        // e ne controlla lo sha256, come unpackXz (un xz piccolo puo' espandersi a dismisura).
        val archives = mutableListOf<Pair<File, RegionManifestFile>>()
        plan.allCells.forEach { cell ->
            val search = cell.search ?: return@forEach
            val target = File(dir, RegionStorage.addressSearchArchiveName(cell.id))
            val downloaded = File(staging, search.downloadFile.name)
            val installedXz = File(installedDir, target.name)
            val installedDb = File(installedDir, RegionStorage.addressSearchFileName(cell.id))
            ensureActive()
            when {
                cell in plan.searchToDownload && search.fileXz != null ->
                    check(downloaded.renameTo(target)) { "Impossibile installare la ricerca della cella ${cell.id}" }
                cell in plan.searchToDownload -> {
                    compressXz(downloaded, target)
                    downloaded.delete()
                }
                installedXz.isFile -> installedXz.copyTo(target)
                installedDb.isFile -> compressXz(installedDb, target)
            }
            if (target.isFile) archives += target to search.file
        }
        if (archives.isEmpty()) return
        val unpacked = File(staging, SEARCH_UNPACK_DIR)
        unpacked.deleteRecursively()
        check(unpacked.mkdirs()) { "Impossibile creare $SEARCH_UNPACK_DIR" }
        try {
            val cellDbs = archives.sortedBy { it.first.name }.map { (archive, expected) ->
                ensureActive()
                File(unpacked, archive.name.removeSuffix(".xz")).also { decompressXz(archive, it, expected) }
            }
            mergeSearch(cellDbs, File(dir, RegionStorage.ADDRESSES_SEARCH_DB))
        } finally {
            unpacked.deleteRecursively()
        }
    }

    private companion object {
        // 4 - 1: divisore della somma sum(4^k) in forma chiusa.
        const val GEOMETRIC_SERIES_DIVISOR = 3L
        const val SEARCH_UNPACK_DIR = "addresses-search-unpacked"
    }

    /** Intervallo (in coordinate x/y a zoom 14) delle tile discendenti di una cella. */
    private data class CellTileRange(val minX: Long, val maxX: Long, val minY: Long, val maxY: Long)

    private fun cellRange(cell: AddressGridCell): CellTileRange {
        val id = requireNotNull(parseCellId(cell.id)) { "id di cella non valido: ${cell.id}" }
        val shift = ADDRESS_TILE_ZOOM - id.z
        val size = 1L shl shift
        val baseX = id.x.toLong() shl shift
        val baseY = id.y.toLong() shl shift
        return CellTileRange(baseX, baseX + size - 1, baseY, baseY + size - 1)
    }

    private fun belongsTo(tileId: Long, range: CellTileRange): Boolean {
        val (x, y) = indexToXY(ADDRESS_TILE_ZOOM, tileId - zoomOffset(ADDRESS_TILE_ZOOM))
        return x in range.minX..range.maxX && y in range.minY..range.maxY
    }

    // Stesso calcolo di PmtilesExtractor.zoomOffset: sum(4^k, k=0..z-1), non esposto dalla libreria.
    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / GEOMETRIC_SERIES_DIVISOR
}

/** Comprime [source] in [target] (xz). Bloccante; su errore non lascia [target] parziale. */
internal fun compressXz(source: File, target: File) {
    val part = File(target.path + ".part")
    try {
        source.inputStream().buffered().use { input ->
            XZOutputStream(part.outputStream().buffered(), LZMA2Options(XZ_PRESET)).use { input.copyTo(it) }
        }
        check(part.renameTo(target)) { "Impossibile scrivere ${target.name}" }
    } finally {
        part.delete()
    }
}

/**
 * Decomprime [source] (xz) in [target], che deve essere [expected]: si ferma appena supera la sua dimensione e alla fine ne
 * controlla dimensione e sha256. Bloccante.
 */
internal fun decompressXz(source: File, target: File, expected: RegionManifestFile) {
    val digest = MessageDigest.getInstance("SHA-256")
    var written = 0L
    XZInputStream(source.inputStream().buffered()).use { input ->
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                written += read
                if (written > expected.sizeBytes) throw PermanentRegionPackageException("${expected.name} decompresso supera la dimensione del manifest")
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    if (written != expected.sizeBytes || !sha256.equals(expected.sha256, ignoreCase = true)) {
        throw PermanentRegionPackageException("${expected.name} decompresso non corrisponde al manifest")
    }
}

// Preset veloce: i database di ricerca si comprimono sul device, solo per le installazioni vecchie.
private const val XZ_PRESET = 3

/**
 * Inversa di ch.poole.geo.pmtiles.Hilbert.zxyToIndex(z,x,y): dato l'indice locale a zoom [z] (senza
 * zoomOffset), le coordinate x,y della tile. Stesso algoritmo (Wikipedia, "Hilbert curve", d2xy),
 * verificato per round-trip nei test con Hilbert.zxyToIndex — nessuna inversa nella libreria, che
 * offre solo la conversione in un senso.
 */
internal fun indexToXY(z: Int, index: Long): Pair<Long, Long> {
    val n = 1L shl z
    var t = index
    var x = 0L
    var y = 0L
    var s = 1L
    while (s < n) {
        val rx = (1L and (t / 2)).toInt()
        val ry = (1L and (t xor rx.toLong())).toInt()
        if (ry == 0) {
            if (rx == 1) {
                x = s - 1 - x
                y = s - 1 - y
            }
            val swap = x
            x = y
            y = swap
        }
        x += s * rx
        y += s * ry
        t /= QUADTREE_CHILDREN
        s *= 2
    }
    return x to y
}
