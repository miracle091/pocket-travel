package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Hilbert

// Livelli di zoom sotto una tile "sottoalbero": le sue (4^8) tile discendenti formano il blocco piu' grande.
private const val SUBTREE_LEVELS = 8
private const val SUBTREE_TILES = 1 shl (2 * SUBTREE_LEVELS)

/** Dimensione dopo la quale un blocco di tileId viene consegnato. Un blocco ne ha al piu' questi piu' [SUBTREE_TILES]. */
internal const val TILE_BLOCK_TARGET = 16_384

// 4 - 1: divisore della somma sum(4^k) in forma chiusa.
private const val GEOMETRIC_SERIES_DIVISOR = 3L

/**
 * Somma cumulativa delle tile di tutti gli zoom precedenti a `zoom` (0 = nessuna): stesso
 * offset che ch.poole.geo.pmtiles.Reader.getZoomOffset applica internamente, non esposto
 * pubblicamente dalla libreria, quindi replicato qui in forma chiusa: sum(4^k, k=0..z-1).
 */
internal fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / GEOMETRIC_SERIES_DIVISOR

/**
 * Consegna a [action], a blocchi, i tileId (zoomOffset incluso) di tutte le tile del rettangolo da [minZoom] a
 * [maxZoom]: in ordine crescente nel complesso, ciascun blocco ordinato. Per le regioni piu' grandi
 * (decine di milioni di tile) la lista intera non sta in memoria: i blocchi tengono al piu' [TILE_BLOCK_TARGET] +
 * 65536 id alla volta.
 *
 * I discendenti di una tile alla curva di Hilbert sono contigui: a ogni zoom il rettangolo si divide in
 * sottoalberi (la tile di 8 livelli piu' in alto che lo tocca), li si visita nell'ordine di Hilbert e dentro
 * ciascuno gli id si ordinano.
 */
internal fun forEachTileIdBlock(
    minLon: Double,
    minLat: Double,
    maxLon: Double,
    maxLat: Double,
    minZoom: Int,
    maxZoom: Int,
    action: (LongArray) -> Unit,
) {
    val buffer = LongArray(TILE_BLOCK_TARGET + SUBTREE_TILES)
    var count = 0
    for (zoom in minZoom..maxZoom) {
        val range = tileRangeFor(minLon, minLat, maxLon, maxLat, zoom)
        val levels = minOf(zoom, SUBTREE_LEVELS)
        val subtreeZoom = zoom - levels
        val xs = (range.minX shr levels)..(range.maxX shr levels)
        val ys = (range.minY shr levels)..(range.maxY shr levels)
        val subtreeCount = xs.count() * ys.count()
        val hilbert = LongArray(subtreeCount)
        val subtreeXs = IntArray(subtreeCount)
        val subtreeYs = IntArray(subtreeCount)
        var next = 0
        for (x in xs) {
            for (y in ys) {
                hilbert[next] = Hilbert.zxyToIndex(subtreeZoom, x.toLong(), y.toLong())
                subtreeXs[next] = x
                subtreeYs[next] = y
                next++
            }
        }
        val offset = zoomOffset(zoom)
        for (subtree in (0 until subtreeCount).sortedBy { hilbert[it] }) {
            val subtreeX = subtreeXs[subtree]
            val subtreeY = subtreeYs[subtree]
            val start = count
            for (x in maxOf(range.minX, subtreeX shl levels)..minOf(range.maxX, ((subtreeX + 1) shl levels) - 1)) {
                for (y in maxOf(range.minY, subtreeY shl levels)..minOf(range.maxY, ((subtreeY + 1) shl levels) - 1)) {
                    buffer[count++] = offset + Hilbert.zxyToIndex(zoom, x.toLong(), y.toLong())
                }
            }
            buffer.sort(start, count)
            if (count >= TILE_BLOCK_TARGET) {
                action(buffer.copyOf(count))
                count = 0
            }
        }
    }
    if (count > 0) action(buffer.copyOf(count))
}
