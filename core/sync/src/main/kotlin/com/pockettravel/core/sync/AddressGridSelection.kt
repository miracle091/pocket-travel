package com.pockettravel.core.sync

import java.security.MessageDigest

/** Nodo z/x/y del quadtree Web Mercator che identifica una cella dei civici (address-grid-plan.md, z <= 14 = tileZoom). */
internal data class CellId(val z: Int, val x: Int, val y: Int)

private const val MAX_CELL_ZOOM = 14

/** Interpreta "z/x/y", null se il formato o l'intervallo di x/y per quello zoom non e' valido. */
internal fun parseCellId(id: String): CellId? {
    val parts = id.split('/')
    if (parts.size != 3) return null
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
 * Celle di [index] che intersecano il riquadro della mappa di una regione (in coordinate tile,
 * stessa proiezione slippy-map di PmtilesExtractor): selezionate quelle la cui tile z/x/y ricade
 * nell'intervallo di tile che copre [bbox] allo stesso zoom della cella (address-grid-plan.md, "App" 2).
 */
fun regionGridCells(index: AddressGridIndex, bbox: MapExtractionSource): List<AddressGridCell> =
    index.cells.filter { cell ->
        val id = parseCellId(cell.id) ?: return@filter false
        val range = tileRangeFor(bbox.minLon, bbox.minLat, bbox.maxLon, bbox.maxLat, id.z)
        id.x in range.minX..range.maxX && id.y in range.minY..range.maxY
    }

/**
 * Versione dei civici di una regione dalle sue celle (address-grid-plan.md, "App" 3): "grid-" + i
 * primi 16 esadecimali dello SHA-256 di "id@version" delle celle, uno per riga, ordinati per id —
 * cosi' il confronto di versione gia' esistente (RegionListViewModel.outdatedKinds) vede un
 * aggiornamento ogni volta che una cella cambia, si aggiunge o sparisce (es. divisa in figlie),
 * senza bisogno di un formato di versione diverso.
 */
fun regionAddressesGridVersion(cells: List<AddressGridCell>): String {
    val lines = cells.map { "${it.id}@${it.version}" }.sorted().joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(lines.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "grid-" + hex.take(16)
}

/**
 * Arricchisce [entries] con le celle della griglia indirizzi (address-grid-plan.md, "App" 1-3):
 * regioni che hanno gia' [RegionManifestEntry.addresses] (percorso di oggi, congelato) o senza
 * celle nel loro riquadro restano invariate. [index] null (manifest senza addressGrid, o non
 * scaricato con successo) lascia tutte le regioni invariate: percorso di oggi.
 */
fun attachAddressGridCells(entries: List<RegionManifestEntry>, index: AddressGridIndex?): List<RegionManifestEntry> {
    if (index == null) return entries
    return entries.map { entry ->
        if (entry.addresses != null) return@map entry
        val cells = regionGridCells(index, entry.map.source)
        if (cells.isEmpty()) entry else entry.copy(addressGrid = RegionAddressGridEntry(cells))
    }
}
