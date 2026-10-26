package com.pockettravel.core.data.alerts

private const val FULL_CIRCLE = 360.0

/**
 * Il rettangolo piu' piccolo che contiene i punti ([longitudine] to [latitudine]); nullo senza punti. Se i punti stanno
 * a cavallo dell'antimeridiano (180 gradi) il rettangolo lo attraversa e ha `minLon` maggiore di `maxLon`.
 */
internal fun boundsOf(points: List<Pair<Double, Double>>): AlertBounds? {
    if (points.isEmpty()) return null
    val lons = points.map { it.first }.sorted()
    // Il rettangolo piu' stretto lascia fuori il salto di longitudine piu' largo, anche quello che passa per i 180 gradi.
    var start = 0
    var widestGap = lons.first() + FULL_CIRCLE - lons.last()
    for (i in 0 until lons.lastIndex) {
        val gap = lons[i + 1] - lons[i]
        if (gap > widestGap) {
            widestGap = gap
            start = i + 1
        }
    }
    val east = if (start == 0) lons.last() else lons[start - 1]
    val lats = points.map { it.second }
    return AlertBounds(lons[start], lats.min(), east, lats.max())
}
