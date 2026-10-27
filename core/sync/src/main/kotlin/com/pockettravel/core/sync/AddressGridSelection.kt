package com.pockettravel.core.sync

import com.pockettravel.core.tiles.TileRange
import com.pockettravel.core.tiles.tileRangeFor
import java.security.MessageDigest

/** Nodo z/x/y del quadtree Web Mercator che identifica una cella dei civici (z <= 14 = tileZoom). */
internal data class CellId(val z: Int, val x: Int, val y: Int)

private const val MAX_CELL_ZOOM = 14
private const val CELL_ID_PARTS = 3
private const val GRID_VERSION_HASH_CHARS = 16

/** Interpreta "z/x/y", null se il formato o l'intervallo di x/y per quello zoom non e' valido. */
internal fun parseCellId(id: String): CellId? {
    val parts = id.split('/')
    if (parts.size != CELL_ID_PARTS) return null
    val z = parts[0].toIntOrNull() ?: return null
    val x = parts[1].toIntOrNull() ?: return null
    val y = parts[2].toIntOrNull() ?: return null
    if (z !in 0..MAX_CELL_ZOOM) return null
    val tilesPerAxis = 1 shl z
    if (x !in 0 until tilesPerAxis || y !in 0 until tilesPerAxis) return null
    return CellId(z, x, y)
}

/** true se questa tile e' [ancestor] stessa o una sua discendente (stesso ramo del quadtree, zoom uguale o piu' alto). */
internal fun CellId.isSameOrDescendantOf(ancestor: CellId): Boolean {
    if (z < ancestor.z) return false
    val shift = z - ancestor.z
    return (x shr shift) == ancestor.x && (y shr shift) == ancestor.y
}

/**
 * Celle di [index] che intersecano il rettangolo della mappa di una regione (in coordinate tile,
 * stessa proiezione slippy-map di PmtilesExtractor): selezionate quelle la cui tile z/x/y ricade
 * nell'intervallo di tile che copre [bbox] allo stesso zoom della cella.
 */
fun regionGridCells(index: AddressGridIndex, bbox: MapExtractionSource): List<AddressGridCell> =
    cellsInBbox(parsedCells(index.cells), bbox)

/** Celle con l'id gia' letto: gli id non validi restano fuori. */
private fun parsedCells(cells: List<AddressGridCell>): List<Pair<CellId, AddressGridCell>> =
    cells.mapNotNull { cell -> parseCellId(cell.id)?.let { it to cell } }

/** L'intervallo di tile del rettangolo si calcola una volta per zoom, non per cella. */
private fun cellsInBbox(cells: List<Pair<CellId, AddressGridCell>>, bbox: MapExtractionSource): List<AddressGridCell> {
    val ranges = HashMap<Int, TileRange>()
    return cells.mapNotNull { (id, cell) ->
        val range = ranges.getOrPut(id.z) { tileRangeFor(bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat, id.z) }
        cell.takeIf { id.x in range.minX..range.maxX && id.y in range.minY..range.maxY }
    }
}

/**
 * Versione dei civici di una regione dalle sue celle: "grid-" + i
 * primi 16 esadecimali dello SHA-256 di "id@version" delle celle, uno per riga, ordinati per id —
 * cosi' il confronto di versione gia' esistente (RegionListViewModel.outdatedKinds) vede un
 * aggiornamento ogni volta che una cella cambia, si aggiunge o sparisce (es. divisa in figlie),
 * senza bisogno di un formato di versione diverso. Una cella con [AddressGridCell.search] conta come
 * un'altra versione ("+search"): chi ha gia' i civici la vede aggiornarsi e prende il database di
 * ricerca anche se la pipeline non ha cambiato la version della cella. Senza ricerca la stringa
 * (e quindi la versione) non cambia.
 */
fun regionAddressesGridVersion(cells: List<AddressGridCell>): String {
    val lines = cells.map { "${it.id}@${it.version}" + if (it.search != null) "+search" else "" }.sorted().joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(lines.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "grid-" + hex.take(GRID_VERSION_HASH_CHARS)
}

/**
 * Arricchisce [entries] con le celle della griglia indirizzi:
 * regioni senza celle nel loro rettangolo restano invariate. [index] null (catalogo senza
 * addressGrid, o non scaricato con successo) lascia tutte le regioni invariate: i civici non sono
 * disponibili per nessuna regione, come le regioni senza celle nel loro rettangolo.
 */
fun attachAddressGridCells(entries: List<RegionManifestEntry>, index: AddressGridIndex?): List<RegionManifestEntry> {
    if (index == null) return entries
    // Gli id si leggono una volta sola: con centinaia di regioni e migliaia di celle, rileggerli per ogni
    // regione costava secondi.
    val parsed = parsedCells(index.cells)
    return entries.map { entry ->
        val cells = cellsInBbox(parsed, entry.map.source)
        if (cells.isEmpty()) entry else entry.copy(addressGrid = RegionAddressGridEntry(cells))
    }
}
