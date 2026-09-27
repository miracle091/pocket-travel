package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import com.pockettravel.core.data.RegionStorage
import java.io.File
import javax.inject.Inject

/** Tile dei civici, sempre allo stesso zoom (GenerateAddresses, address-grid-plan.md). */
private const val ADDRESS_TILE_ZOOM = 14

/**
 * Ricostruisce addresses.pmtiles di una regione a partire dalle sue celle (address-grid-plan.md,
 * "App" 4): solo le celle nuove o cambiate si scaricano (vedi [plan]), le altre si tengono dal file
 * gia' installato. Una tile z14 appartiene a una cella se ne e' discendente: le celle sparite dalla
 * regione (es. divise in figlie dalla pipeline) non vengono ricopiate, spariscono col resto del file
 * precedente. Scrive anche [RegionStorage.ADDRESSES_CELLS_FILE] in staging, pronto per
 * RegionStorage.activatePackage come [RegionStorage.ADDRESSES_FILE].
 *
 * Le tile si leggono camminando la directory di ogni file ([PmtilesDirectoryReader]), mai provando
 * le coordinate z14 discendenti di una cella una per una: il seme ha celle molto rade nelle zone a
 * bassa densita' (z3-z6, address-grid-plan.md), dove una cella copre fino a centinaia di milioni di
 * tile quasi tutte vuote — ch.poole.geo.pmtiles.Reader.getTile(z,x,y) (l'unica API della libreria di
 * lettura) richiederebbe altrettante chiamate. Una tile trovata appartiene alla cella cercata se le
 * sue coordinate x,y (Hilbert invertito, [indexToXY]) ricadono nel suo intervallo discendente.
 */
class RegionAddressGridInstaller @Inject constructor(
    private val regionStorage: RegionStorage,
) {
    /** Celle da scaricare (nuove o con version diversa) e celle invariate (da tenere dal file installato). */
    data class Plan(val allCells: List<AddressGridCell>, val toDownload: List<AddressGridCell>, val unchanged: List<AddressGridCell>)

    fun plan(regionId: String, addressGrid: RegionAddressGridEntry): Plan {
        val installed = regionStorage.installedAddressCells(regionId)
        val (unchanged, toDownload) = addressGrid.cells.partition { installed[it.id] == it.version }
        return Plan(addressGrid.cells, toDownload, unchanged)
    }

    /**
     * [plan.toDownload] deve gia' essere scaricato e decompresso in [staging], con lo stesso nome del
     * manifest ([AddressGridCell.file].name) — RegionPackageInstaller se ne occupa insieme agli altri
     * pacchetti richiesti. Bloccante (letture di file): va chiamato fuori dal main thread.
     * [ensureActive] e' invocato piu' volte durante la copia delle tile e deve lanciare un'eccezione
     * per interrompere l'operazione (es. CoroutineScope.ensureActive del worker annullato).
     */
    fun mergeInto(regionId: String, mapSource: MapExtractionSource, plan: Plan, staging: File, ensureActive: () -> Unit = {}) {
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
    private fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / 3L
}

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
        t /= 4
        s *= 2
    }
    return x to y
}
