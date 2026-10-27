package com.pockettravel.core.tiles

// Livelli di zoom sotto una tile "sottoalbero": le sue (4^8) tile discendenti formano il blocco piu' grande.
private const val SUBTREE_LEVELS = 8
private const val SUBTREE_TILES = 1 shl (2 * SUBTREE_LEVELS)

/** Dimensione dopo la quale un blocco di tileId viene consegnato. Un blocco ne ha al piu' questi piu' 4^8. */
const val TILE_BLOCK_TARGET = 16_384

// Nella curva di Hilbert (3 * rx) xor ry da' il quadrante, 0..3, nell'ordine di visita.
private const val QUADRANT_STEP = 3L

// 4 - 1: divisore della somma sum(4^k) in forma chiusa.
private const val GEOMETRIC_SERIES_DIVISOR = 3L

/**
 * Somma cumulativa delle tile di tutti gli zoom precedenti a `zoom` (0 = nessuna): stesso
 * offset che ch.poole.geo.pmtiles.Reader.getZoomOffset applica internamente, in forma chiusa: sum(4^k, k=0..z-1).
 */
fun zoomOffset(zoom: Int): Long = ((1L shl (2 * zoom)) - 1L) / GEOMETRIC_SERIES_DIVISOR

/**
 * Posizione della tile [x],[y] sulla curva di Hilbert dello zoom [zoom], senza zoomOffset: la curva degli indici
 * PMTiles (Wikipedia, "Hilbert curve", xy2d). Tutto in Long: da z16 gli indici superano 2^31, e
 * ch.poole.geo.pmtiles.Hilbert.zxyToIndex li restituisce troncati a 32 bit.
 */
fun hilbertIndex(zoom: Int, x: Long, y: Long): Long {
    val n = 1L shl zoom
    var tx = x
    var ty = y
    var index = 0L
    var s = n / 2
    while (s > 0) {
        val rx = if (tx and s != 0L) 1L else 0L
        val ry = if (ty and s != 0L) 1L else 0L
        index += s * s * ((QUADRANT_STEP * rx) xor ry)
        if (ry == 0L) {
            if (rx == 1L) {
                tx = n - 1 - tx
                ty = n - 1 - ty
            }
            val swap = tx
            tx = ty
            ty = swap
        }
        s /= 2
    }
    return index
}

/**
 * I tileId (zoomOffset incluso, come negli indici PMTiles) di tutte le tile del rettangolo da [minZoom] a
 * [maxZoom], a blocchi: in ordine crescente nel complesso, ciascun blocco ordinato. Per le regioni piu' grandi
 * (decine di milioni di tile) la lista intera non sta in memoria: i blocchi tengono al piu' [TILE_BLOCK_TARGET] +
 * 65536 id alla volta.
 *
 * I discendenti di una tile alla curva di Hilbert sono contigui: a ogni zoom il rettangolo si divide in
 * sottoalberi (la tile di 8 livelli piu' in alto che lo tocca), li si visita nell'ordine di Hilbert e dentro
 * ciascuno gli id si ordinano.
 */
fun tileIdBlocks(
    minLon: Double,
    minLat: Double,
    maxLon: Double,
    maxLat: Double,
    minZoom: Int,
    maxZoom: Int,
): Sequence<LongArray> = sequence {
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
                hilbert[next] = hilbertIndex(subtreeZoom, x.toLong(), y.toLong())
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
                    buffer[count++] = offset + hilbertIndex(zoom, x.toLong(), y.toLong())
                }
            }
            buffer.sort(start, count)
            if (count >= TILE_BLOCK_TARGET) {
                yield(buffer.copyOf(count))
                count = 0
            }
        }
    }
    if (count > 0) yield(buffer.copyOf(count))
}
